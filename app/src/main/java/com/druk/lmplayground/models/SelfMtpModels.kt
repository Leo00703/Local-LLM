package com.druk.lmplayground.models

/**
 * Which local llama.cpp models can use the model's own MTP head for speculative decoding.
 *
 * Only the Qwen 3.5 / 3.6 family builds its MTP head inside the same GGUF; the native side
 * (`LlamaGenerationSession`) creates the draft context on the SAME model and logs
 * "no MTP head" when a particular GGUF was converted without it, so a matching name is a
 * necessary condition, not a promise. Gemma 4 keeps its head in a separate assistant model
 * and is not covered here.
 */
object SelfMtpModels {
    private val FAMILY = Regex("(?i)qwen3\\.[56]")

    fun supports(filename: String?): Boolean {
        if (filename.isNullOrEmpty()) return false
        if (!filename.endsWith(".gguf", ignoreCase = true)) return false   // not remote:, not .litertlm
        if (filename.startsWith("mmproj", ignoreCase = true)) return false // a projector, not a model
        return FAMILY.containsMatchIn(filename)
    }
}
