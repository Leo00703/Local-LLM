@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.druk.lmplayground.litert

import android.os.Handler
import android.os.HandlerThread
import com.google.ai.edge.litertlm.Backend
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
    fun startConversation(topK: Int, topP: Double, temperature: Double) {
        val e = engine ?: error("LiteRtEngine.load() must be called before startConversation()")
        conversation?.close()
        conversation = e.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = topK, topP = topP, temperature = temperature),
            )
        )
    }

    /**
     * Send one turn on the persistent conversation opened by [startConversation],
     * streaming the reply. The caller collects on a background dispatcher. Turns
     * accumulate in the same conversation (multi-turn memory).
     */
    fun sendMessage(text: String): Flow<String> {
        val convo = conversation ?: error("startConversation() must be called before sendMessage()")
        // The Flow overload of sendMessageAsync delivers the whole reply in one
        // burst at the end (no visible streaming). The MessageCallback overload
        // fires onMessage per token, BUT LiteRT delivers those callbacks on the
        // Looper of the thread that called sendMessageAsync. A plain background
        // thread has no Looper, so the callbacks never fire (v1.9.92 generated
        // nothing). Run the call on a HandlerThread, whose Looper stays free to
        // dispatch each onMessage as the token is produced -> live streaming.
        return callbackFlow {
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) { trySendBlocking(message.toString()) }
                override fun onDone() { close() }
                override fun onError(error: Throwable) { close(error) }
            }
            val handlerThread = HandlerThread("litert-infer").apply { start() }
            Handler(handlerThread.looper).post {
                try {
                    convo.sendMessageAsync(text, callback)
                } catch (t: Throwable) {
                    close(t)
                }
            }
            awaitClose { handlerThread.quitSafely() }
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
