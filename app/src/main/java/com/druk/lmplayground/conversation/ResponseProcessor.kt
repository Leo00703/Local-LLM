package com.druk.lmplayground.conversation

/**
 * Processes raw model response text for display in the UI.
 */
object ResponseProcessor {

    // Hoisted: this is called once per streamed token with the full accumulated
    // response, so compiling the constant pattern inline burned a fresh Pattern
    // on every token of every thinking-model reply. Compile it once.
    private val SEPARATOR = Regex("""^\s*[-—_]{2,}\s*""")

    /**
     * Process raw model response: clean up thinking/response separators.
     */
    fun process(raw: String): String {
        return removeThinkingSeparator(raw)
    }

    /**
     * Remove separator lines (e.g. "---", "———") that some models generate
     * between the </think> block and the actual response.
     */
    fun removeThinkingSeparator(text: String): String {
        val closeIdx = text.indexOf("</think>")
        if (closeIdx == -1) return text
        val afterThink = closeIdx + "</think>".length
        val rest = text.substring(afterThink)
        val cleaned = rest.replaceFirst(SEPARATOR, "\n\n")
        return text.substring(0, afterThink) + cleaned
    }
}
