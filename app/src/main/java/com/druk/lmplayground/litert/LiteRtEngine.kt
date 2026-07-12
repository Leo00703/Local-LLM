@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.druk.lmplayground.litert

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

/**
 * One streamed piece of a LiteRT reply: a thought-channel delta ([isThought]),
 * an answer delta, or a batch of [toolCalls] the model requested (native-parsed).
 */
data class LiteRtChunk(
    val text: String = "",
    val isThought: Boolean = false,
    val toolCalls: List<ToolCall> = emptyList(),
)

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

    // Conversation-creation params, remembered so the conversation can be rebuilt
    // when the enabled tool set changes (tools are fixed at createConversation time).
    private var convoTopK = 64
    private var convoTopP = 0.95
    private var convoTemp = 1.0
    private var convoSystemPrompt = ""
    private var convoTools: List<ToolProvider> = emptyList()

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
        enableVision: Boolean = false,
        visualTokenBudget: Int? = null,
    ) {
        ExperimentalFlags.enableSpeculativeDecoding = useMtp
        // Read per-send by the runtime; caps image detail (Gemma 4 budgets: 70/140/
        // 280/560/1120). null = full detail. Only affects turns that carry an image.
        ExperimentalFlags.visualTokenBudget = visualTokenBudget
        engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = if (useGpu) Backend.GPU() else Backend.CPU(),
                // Vision encoder is initialized EAGERLY at Engine.initialize() when
                // visionBackend != null (and stays resident, so the memory guard must
                // budget for it). Edge Gallery forces GPU for the Gemma encoder ("must
                // be GPU for Gemma 3n"), so we use GPU regardless of the text backend
                // (vision then works even when the LLM text backend is CPU).
                visionBackend = if (enableVision) Backend.GPU() else null,
                maxNumImages = if (enableVision) 1 else null, // one image per turn (matches UI)
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
        conversation?.close()
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
        convoTopK = topK
        convoTopP = topP
        convoTemp = temperature
        convoSystemPrompt = systemPrompt
        convoTools = emptyList()   // a fresh session starts toolless; setTools re-adds them
        rebuildConversation()
    }

    /**
     * Replace the enabled tool set. Tools are fixed at createConversation time in
     * 0.13.1, so this rebuilds the conversation with the new tools (losing native KV
     * / history). The caller (LiteRtBackend) only invokes this when the set actually
     * changes, so a stable-tools chat keeps its history. automaticToolCalling stays
     * false, so the runtime returns tool calls to us instead of executing them.
     */
    fun setTools(tools: List<ToolProvider>) {
        convoTools = tools
        rebuildConversation()
    }

    private fun rebuildConversation() {
        val e = engine ?: error("LiteRtEngine.load() must be called before startConversation()")
        conversation?.close()
        conversation = e.createConversation(
            ConversationConfig(
                // Real system instruction: LiteRT renders it as a SYSTEM message at
                // position 0 and folds it into Gemma's own chat template (Gemma has
                // no system role, so the template merges it into the first user turn).
                systemInstruction = convoSystemPrompt.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
                tools = convoTools,
                automaticToolCalling = false,
                samplerConfig = SamplerConfig(topK = convoTopK, topP = convoTopP, temperature = convoTemp),
            )
        )
    }

    /**
     * Send one user turn on the persistent conversation, streaming the reply as
     * [LiteRtChunk]s (thought / answer / tool-call). Turns accumulate in the same
     * conversation (multi-turn memory).
     */
    fun sendMessage(text: String, enableThinking: Boolean): Flow<LiteRtChunk> {
        val convo = conversation ?: error("startConversation() must be called before sendMessage()")
        return stream(convo, enableThinking) { cb, ctx -> convo.sendMessageAsync(text, cb, ctx) }
    }

    /**
     * Send one user turn carrying an ENCODED image (JPEG/PNG) + optional text.
     * The native runtime STB-decodes the bytes, so our transcoded-JPEG attach bytes
     * go straight in (no bitmap/RGB re-encode). Image goes BEFORE text in the Contents
     * list (Edge Gallery: "add the text after image for the accurate last token").
     * Requires the engine to have been loaded with enableVision = true. Falls back to
     * the plain text path when [imageBytes] is null.
     */
    fun sendMessage(text: String, imageBytes: ByteArray?, enableThinking: Boolean): Flow<LiteRtChunk> {
        val convo = conversation ?: error("startConversation() must be called before sendMessage()")
        if (imageBytes == null) {
            return stream(convo, enableThinking) { cb, ctx -> convo.sendMessageAsync(text, cb, ctx) }
        }
        val contents = Contents.of(
            buildList {
                add(Content.ImageBytes(imageBytes))
                if (text.isNotBlank()) add(Content.Text(text))
            }
        )
        return stream(convo, enableThinking) { cb, ctx -> convo.sendMessageAsync(contents, cb, ctx) }
    }

    /**
     * Continue the turn after tool execution: send the tool-result [message] (a TOOL
     * Message built from Content.ToolResponse) on the same conversation so the model
     * produces its final answer with the tool output in context.
     */
    fun sendToolResults(message: Message, enableThinking: Boolean): Flow<LiteRtChunk> {
        val convo = conversation ?: error("startConversation() must be called before sendToolResults()")
        return stream(convo, enableThinking) { cb, ctx -> convo.sendMessageAsync(message, cb, ctx) }
    }

    /**
     * Shared streaming bridge. [send] invokes the right sendMessageAsync overload
     * (user text or tool-result Message) on a dedicated daemon thread; each onMessage
     * is split into thought / answer / tool-call [LiteRtChunk]s with backpressure.
     *
     * onMessage fires per token in real time on LiteRT's own native decode thread
     * (no Looper involved). Never call a status method (e.g. getTokenCount) inside the
     * collector: it blocks on the decode lock and re-batches the whole stream (see
     * LiteRtBackend). Thinking is driven by the `enable_thinking` template variable
     * passed via extraContext as a real Boolean (a String "false" is truthy in Jinja).
     */
    private fun stream(
        convo: Conversation,
        enableThinking: Boolean,
        send: (MessageCallback, Map<String, Any>) -> Unit,
    ): Flow<LiteRtChunk> {
        val extraContext: Map<String, Any> =
            if (enableThinking) mapOf("enable_thinking" to true) else emptyMap()
        return callbackFlow {
            // True once the generation finished on its own (onDone/onError). Used so
            // awaitClose only cancels the native decode on a REAL cancellation (Stop /
            // scope cancel). Calling cancelProcess() after a normal onDone races with
            // the native decode thread still tearing down the same sendMessageAsync and
            // can crash the runtime -- and the tool loop fires two generations back to
            // back (call + continuation), which made that race hit mid tool search.
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    // A message may carry tool calls (native-parsed) and/or a thought
                    // delta and/or an answer delta. Named channels stream live (not
                    // buffered), so the thought arrives token by token like the answer.
                    if (message.toolCalls.isNotEmpty()) {
                        trySendBlocking(LiteRtChunk(toolCalls = message.toolCalls))
                    }
                    message.channels[THOUGHT_CHANNEL]?.takeIf { it.isNotEmpty() }?.let {
                        trySendBlocking(LiteRtChunk(text = it, isThought = true))
                    }
                    val answer = message.toString()
                    if (answer.isNotEmpty()) {
                        trySendBlocking(LiteRtChunk(text = answer))
                    }
                }
                override fun onDone() { finished.set(true); close() }
                override fun onError(error: Throwable) { finished.set(true); close(error) }
            }
            val worker = Thread({
                try {
                    send(callback, extraContext)
                } catch (t: Throwable) {
                    finished.set(true)
                    close(t)
                }
            }, "litert-decode").apply { isDaemon = true }
            worker.start()
            awaitClose {
                if (!finished.get()) runCatching { convo.cancelProcess() }
            }
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
}
