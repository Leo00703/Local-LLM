package com.druk.llamacpp

/** Turns the raw ERROR lines the native side remembered into a short note for the UI. */
object NativeErrors {

    /**
     * The last [maxLines] distinct lines, each cut to [maxChars]. Consecutive repeats collapse
     * (a failing backend tends to print the same line for every tensor) and blank lines go.
     */
    fun summarize(raw: String, maxLines: Int = 3, maxChars: Int = 220): String {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val distinct = lines.filterIndexed { i, line -> i == 0 || line != lines[i - 1] }
        return distinct.takeLast(maxLines)
            .joinToString("\n") { if (it.length > maxChars) it.take(maxChars - 1) + "\u2026" else it }
    }
}
