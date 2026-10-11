package com.druk.llamacpp

import android.app.ApplicationExitInfo

/** Turns the raw ERROR lines the native side remembered into a short note for the UI. */
object NativeErrors {

    private val SIGNALS = mapOf(
        4 to "SIGILL", 6 to "SIGABRT", 7 to "SIGBUS", 8 to "SIGFPE",
        9 to "SIGKILL", 11 to "SIGSEGV", 15 to "SIGTERM",
    )

    /**
     * One sentence on how the engine process ended, from the fields of Android's own exit
     * record ([ApplicationExitInfo]). A process that dies mid-load says nothing on its own
     * (no log line, no abort note for a SIGSEGV or a low-memory kill), so this is the only
     * trace of why. [status] is the signal number for native crashes and kills.
     */
    fun describeProcessExit(reason: Int, status: Int, rssBytes: Long, description: String?): String {
        val signal = SIGNALS[status]?.let { "$it, signal $status" } ?: "signal $status"
        val what = when (reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed natively ($signal)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "was killed by Android to free memory"
            ApplicationExitInfo.REASON_SIGNALED -> "was killed ($signal)"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "was stopped for using too many resources"
            ApplicationExitInfo.REASON_CRASH -> "crashed (uncaught exception)"
            ApplicationExitInfo.REASON_EXIT_SELF -> "exited by itself (code $status)"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
            else -> "ended (reason $reason)"
        }
        val mb = rssBytes / (1024 * 1024)
        val using = if (mb > 0) ", using $mb MB" else ""
        val note = description?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
        return "The engine process $what$using.$note"
    }

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
