@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.druk.lmplayground.litert

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.Flow
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
    fun load(modelPath: String, cacheDir: String, useGpu: Boolean, useMtp: Boolean) {
        ExperimentalFlags.enableSpeculativeDecoding = useMtp
        engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = if (useGpu) Backend.GPU() else Backend.CPU(),
                cacheDir = cacheDir,
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
        return convo.sendMessageAsync(prompt).map { it.toString() }
    }

    /** Free the native engine + KV/VRAM. Safe to call more than once. */
    fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }
}
