package com.druk.lmplayground.models

/**
 * Which local llama.cpp models can use MTP speculative decoding, and how.
 *
 *  - Qwen 3.5 / 3.6 build the MTP head inside the same GGUF ([hasOwnHead]). The native side
 *    (`LlamaGenerationSession`) creates the draft context on the SAME model and logs
 *    "no MTP head" when a particular GGUF was converted without it, so a matching name is a
 *    necessary condition, not a promise.
 *  - Gemma 4 keeps its head in a separate, small assistant GGUF (the "drafter",
 *    `mtp-gemma-4-*.gguf`) that is loaded next to the model and reads the model's KV cache
 *    ([needsDrafter]); see `ModelInfo.drafterAddOn` for where each catalog model gets it.
 */
object MtpModels {
    private val OWN_HEAD = Regex("(?i)qwen3\\.[56]")
    private val GEMMA4 = Regex("(?i)gemma-?4")

    /** A real local model file: a .gguf that is neither a projector nor a drafter. */
    private fun isLocalModelFile(filename: String?): Boolean {
        if (filename.isNullOrEmpty()) return false
        if (!filename.endsWith(".gguf", ignoreCase = true)) return false   // not remote:, not .litertlm
        if (filename.startsWith("mmproj", ignoreCase = true)) return false // a projector, not a model
        if (isDrafterFile(filename)) return false                          // a drafter, not a model
        return true
    }

    /** The MTP head is inside the model file (Qwen 3.5 / 3.6). */
    fun hasOwnHead(filename: String?): Boolean =
        filename != null && isLocalModelFile(filename) && OWN_HEAD.containsMatchIn(filename)

    /** The MTP head is a separate assistant file that has to be downloaded (Gemma 4). */
    fun needsDrafter(filename: String?): Boolean =
        filename != null && isLocalModelFile(filename) && GEMMA4.containsMatchIn(filename)

    fun supports(filename: String?): Boolean = hasOwnHead(filename) || needsDrafter(filename)

    /**
     * An MTP assistant file (`mtp-*.gguf`). It lands in the models folder next to the models
     * it serves, so the model list must not mistake it for a model of its own.
     */
    fun isDrafterFile(filename: String): Boolean =
        filename.lowercase().let { it.endsWith(".gguf") && it.startsWith("mtp-") }
}
