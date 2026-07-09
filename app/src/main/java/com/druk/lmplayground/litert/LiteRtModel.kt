package com.druk.lmplayground.litert

import com.druk.llamacpp.GenerationBackend
import com.druk.llamacpp.GenerationModel

/**
 * [GenerationModel] adapter for the LiteRT-LM engine (Gemma 4 `.litertlm`). No
 * GGUF is loaded; size/capability info is synthesized. Built the same way as
 * [com.druk.lmplayground.remote.RemoteOpenAiModel]: an in-process, stream-based
 * adapter that plugs into the shared chat pipeline behind the
 * [GenerationModel] / [GenerationBackend] abstraction, so the ViewModel's
 * generation loop is unchanged.
 *
 * [createSession] opens the engine's persistent conversation (multi-turn
 * memory) and returns a [LiteRtBackend] bound to it.
 */
class LiteRtModel(
    private val engine: LiteRtEngine,
    private val contextTrainSize: Int,
) : GenerationModel {

    override fun getModelSize(): Long = 0L

    override fun getModelReport(): String = "Google LiteRT-LM (Gemma 4)"

    override fun getContextTrainSize(): Int = contextTrainSize

    override fun supportsThinking(): Boolean = true

    override fun supportsToolCalling(): Boolean = false

    override fun unloadModel() {
        engine.close()
    }

    override fun createSession(
        contextSize: Int,
        temperature: Float,
        topP: Float,
        repetitionPenalty: Float,
        topK: Int,
        minP: Float,
        seed: Int,
        thinkingBudget: Int,
        systemPrompt: String,
        kvCacheType: Int,
        speculativeEnabled: Boolean,
        specNDraft: Int,
    ): GenerationBackend? {
        engine.startConversation(topK, topP.toDouble(), temperature.toDouble(), systemPrompt)
        return LiteRtBackend(engine)
    }
}
