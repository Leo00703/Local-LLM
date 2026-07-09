@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.druk.lmplayground.litert

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.benchmark
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

/** One streamed piece of a LiteRT reply: a thought-channel delta or an answer delta. */
data class LiteRtChunk(val text: String, val isThought: Boolean)

/** Gemma metadata channel carrying reasoning; matches Edge Gallery's THOUGHT_CHANNEL. */
private const val THOUGHT_CHANNEL = "thought"

/**
 * Thin wrapper over the LiteRT-LM runtime (Google AI Edge), the SECOND on-device
 * engine beside llama.cpp. It runs Gemma 4 `.litertlm` models with their built-in
 * MTP speculative-decoding drafter, targeting the real 2-3x decode speedup that
 * llama.cpp self-MTP could only bring to break-even on this hardware.
 *
 * Model routing is by file extension: `.gguf` -> llama.cpp, `.litertlm` -> here.
 * The two engines never run resident at the same time (one is unloaded before the
 * other loads) to avoid two native runtimes + two OpenCL contexts co-existing.
 *
 * NOTE: this is the Step 1 wrapper. It compiles and pins the exact LiteRT-LM API
 * but is not yet driven by a service. The `:litert` AIDL service + on-device load
 * and the real Message->token-text mapping land in the following steps.
 */
class LiteRtEngine {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /**
     * Load a `.litertlm` model. MTP is a global experimental flag that must be set
     * BEFORE [Engine.initialize]. [initialize] blocks for up to ~10s, so call this
     * off the main thread.
     */
    fun load(
        modelPath: String,
        cacheDir: String,
        useGpu: Boolean,
        useMtp: Boolean,
        maxNumTokens: Int = 4096,
    ) {
        ExperimentalFlags.enableSpeculativeDecoding = useMtp
        engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = if (useGpu) Backend.GPU() else Backend.CPU(),
                cacheDir = cacheDir,
                maxNumTokens = maxNumTokens,
            )
        ).also { it.initialize() }
    }

    /**
     * Stream a reply. The caller collects on a background dispatcher.
     * TODO(step2): map [com.google.ai.edge.litertlm.Message] to token text
     * correctly (delta vs accumulated) once verified on device.
     */
    fun generate(prompt: String, topK: Int, topP: Double, temperature: Double): Flow<String> {
        val e = engine ?: error("LiteRtEngine.load() must be called before generate()")
        val convo = e.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = topK, topP = topP, temperature = temperature),
            )
        )
        conversation = convo
        return convo.sendMessageAsync(prompt).map { it.toString() }.buffer(Channel.UNLIMITED)
    }

    /**
     * Open a PERSISTENT conversation for multi-turn chat. Unlike [generate]
     * (which makes a fresh, single-shot conversation each call), this keeps the
     * conversation resident so subsequent [sendMessage] turns accumulate history.
     * Closes any previous conversation first. Call after [load].
     */
    fun startConversation(topK: Int, topP: Double, temperature: Double, systemPrompt: String) {
        val e = engine ?: error("LiteRtEngine.load() must be called before startConversation()")
        conversation?.close()
        conversation = e.createConversation(
            ConversationConfig(
                // Real system instruction: LiteRT renders it as a SYSTEM message at
                // position 0 and folds it into Gemma's own chat template (Gemma has
                // no system role, so the template merges it into the first user turn).
                // Cleaner + template-correct vs the old "prepend to the first turn" hack.
                systemInstruction = systemPrompt.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
                samplerConfig = SamplerConfig(topK = topK, topP = topP, temperature = temperature),
            )
        )
    }

    /**
     * Send one turn on the persistent conversation opened by [startConversation],
     * streaming the reply as [LiteRtChunk]s (thought-channel deltas tagged
     * [LiteRtChunk.isThought], answer deltas otherwise). The caller collects on a
     * background dispatcher. Turns accumulate in the same conversation (multi-turn).
     *
     * onMessage fires per token in real time on LiteRT's own native decode thread
     * (no Looper involved), so we bridge each token with backpressure and never call
     * a status method (e.g. getTokenCount) inside the collector, which would block on
     * the decode lock and re-batch the whole stream (see LiteRtBackend).
     *
     * Thinking: enabled by the prompt-template variable `enable_thinking` passed via
     * the 3-arg sendMessageAsync overload. It must be a real Boolean (a String
     * "false" is truthy in the Jinja template). When on, Gemma streams its reasoning
     * live on the model-metadata "thought" channel ([Message.channels]); the answer
     * is [Message.toString]. 0.13.1 has no ThinkingConfig, so extraContext is the lever.
     */
    fun sendMessage(text: String, enableThinking: Boolean): Flow<LiteRtChunk> {
        val convo = conversation ?: error("startConversation() must be called before sendMessage()")
        val extraContext: Map<String, Any> =
            if (enableThinking) mapOf("enable_thinking" to true) else emptyMap()
        return callbackFlow {
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    // Each callback carries EITHER a thought delta OR an answer delta;
                    // named channels stream incrementally (not buffered), so the
                    // thought arrives live, token by token, just like the answer.
                    message.channels[THOUGHT_CHANNEL]?.takeIf { it.isNotEmpty() }?.let {
                        trySendBlocking(LiteRtChunk(it, isThought = true))
                    }
                    val answer = message.toString()
                    if (answer.isNotEmpty()) {
                        trySendBlocking(LiteRtChunk(answer, isThought = false))
                    }
                }
                override fun onDone() { close() }
                override fun onError(error: Throwable) { close(error) }
            }
            val worker = Thread({
                try {
                    convo.sendMessageAsync(text, callback, extraContext)
                } catch (t: Throwable) {
                    close(t)
                }
            }, "litert-decode").apply { isDaemon = true }
            worker.start()
            awaitClose { runCatching { convo.cancelProcess() } }
        }.buffer(Channel.UNLIMITED)
    }

    /**
     * Running token total of the persistent conversation (prompt + generated so
     * far), read straight from the LiteRT runtime. Used to count real completion
     * tokens for stats: under MTP each stream emission carries ~3.7 tokens, so
     * counting callbacks undercounts; this is the authoritative count. Returns 0
     * before a conversation exists.
     */
    fun tokenCount(): Int = conversation?.getTokenCount()?.toInt() ?: 0

    /** Free the native engine + KV/VRAM. Safe to call more than once. */
    fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }

    companion object {
        /**
         * Run LiteRT-LM's built-in synthetic decode benchmark (the same API Google's
         * Edge Gallery uses) and return real decode/prefill tokens-per-second, so we
         * can report tok/s (not just chars/sec) and compare 1:1 with Edge Gallery.
         *
         * Creates and frees its OWN engine, so call it with no other engine resident
         * (one native runtime + one OpenCL context at a time). MTP is applied via the
         * global [ExperimentalFlags] set here before the run.
         *
         * Returns [decode tok/s, prefill tok/s, decode token count, TTFT seconds].
         */
        fun benchmarkTps(
            modelPath: String,
            cacheDir: String,
            useGpu: Boolean,
            useMtp: Boolean,
            prefillTokens: Int = 128,
            decodeTokens: Int = 256,
        ): DoubleArray {
            ExperimentalFlags.enableSpeculativeDecoding = useMtp
            val info = benchmark(
                modelPath,
                if (useGpu) Backend.GPU() else Backend.CPU(),
                prefillTokens,
                decodeTokens,
                cacheDir,
            )
            return doubleArrayOf(
                info.lastDecodeTokensPerSecond.toDouble(),
                info.lastPrefillTokensPerSecond.toDouble(),
                info.lastDecodeTokenCount.toDouble(),
                info.timeToFirstTokenInSecond.toDouble(),
            )
        }
    }
}
