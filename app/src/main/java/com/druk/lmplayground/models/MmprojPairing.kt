package com.druk.lmplayground.models

/**
 * Pure name-based pairing of a sideloaded model with its multimodal projector
 * (mmproj) sibling in the same folder. Vision models ship as two files — the
 * text GGUF plus a separate `mmproj-*.gguf` projector — so we pair them by
 * shared name tokens (they're published together, e.g. `gemma-3-4b-…` +
 * `mmproj-gemma-3-4b-…`).
 */
object MmprojPairing {

    /** True if a filename looks like a multimodal projector. */
    fun isMmproj(filename: String): Boolean =
        filename.lowercase().let { it.endsWith(".gguf") && it.contains("mmproj") }

    /**
     * Pick the mmproj that best pairs with [modelFilename] from [mmprojFilenames]
     * (bare names): the one sharing the most meaningful name tokens. Requires at
     * least one shared token — we deliberately do NOT fall back to "the only
     * projector in the folder", since that would wrongly tag an unrelated model
     * as vision (and then try to attach an incompatible projector to it).
     * Returns null when nothing overlaps.
     */
    fun findMmprojFor(modelFilename: String, mmprojFilenames: List<String>): String? {
        if (mmprojFilenames.isEmpty()) return null
        val modelTokens = tokens(modelFilename)
        val best = mmprojFilenames
            .map { it to tokens(it).count { t -> t in modelTokens } }
            .maxByOrNull { it.second }
        return best?.takeIf { it.second > 0 }?.first
    }

    // Tokens that don't help distinguish a model family (quant levels, format
    // tags, generic instruct/qat markers, the mmproj word, extension). Stripping
    // them leaves the identifying family/size tokens (e.g. gemma, 4, e2b).
    private val NOISE = setOf(
        "mmproj", "gguf", "q2", "q3", "q4", "q5", "q6", "q8", "iq2", "iq3", "iq4",
        "f16", "f32", "bf16", "fp16", "k", "m", "s", "l", "xl", "0", "1",
        "it", "qat", "instruct", "chat", "base", "model",
    )

    private fun tokens(filename: String): Set<String> =
        filename.lowercase()
            .removeSuffix(".gguf")
            .split('-', '_', '.', ' ')
            .filter { it.isNotBlank() && it !in NOISE }
            .toSet()
}
