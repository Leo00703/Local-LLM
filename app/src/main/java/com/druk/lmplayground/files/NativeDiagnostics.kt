package com.druk.lmplayground.files

import android.app.ActivityManager
import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Best-effort capture of what the native `:llama` inference process logged right
 * before it died (used when a projector load crashes the service — the binder
 * call throws DeadObjectException and the on-device native reason is otherwise
 * lost to logcat the user can't read). An app can read logcat entries produced
 * by its OWN uid without any permission, and the `:llama` service shares this
 * app's uid, so its ggml/mtmd/clip lines and any crash tombstone are visible.
 */
object NativeDiagnostics {

    // Native progress lines from the main buffer — how far clip/mtmd got before
    // the crash (which tensor/layer, allocation sizes, the last error).
    private val PROGRESS = listOf(
        "llama-android", "mtmd", "clip", "ggml", "load_hparams", "load_tensors",
        "n_tensors", "alloc", "projector", "failed", "error", "unknown",
        "assert", "not supported", "unsupported",
    )

    /** A compact memory snapshot line for context (availability drives OOM). */
    fun memorySnapshot(context: Context): String = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val mb = 1024L * 1024L
        "RAM: avail ${mi.availMem / mb} MB / total ${mi.totalMem / mb} MB" +
            " (low=${mi.lowMemory}, threshold ${mi.threshold / mb} MB)"
    } catch (t: Throwable) {
        "RAM: unavailable (${t.message})"
    }

    /**
     * How the `:llama` process last ended, from Android's own exit records (API 30+), or ""
     * when it has not ended since [sinceMs] or the record cannot be read. This is the only
     * trace of a SIGSEGV or a low-memory kill, which leave no log line and no abort note.
     * The record is written a moment after the process dies, so [await] retries briefly.
     */
    fun lastEngineExit(context: Context, sinceMs: Long, await: Boolean = false): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return ""
        repeat(if (await) 6 else 1) { attempt ->
            try {
                am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                    .firstOrNull { it.processName.endsWith(":llama") && it.timestamp >= sinceMs }
                    ?.let {
                        return NativeErrors.describeProcessExit(it.reason, it.status, it.rss, it.description)
                    }
            } catch (_: Throwable) {
                return ""
            }
            if (attempt < 5) Thread.sleep(500)
        }
        return ""
    }

    /**
     * The last few native log lines of this app (from logcat, own uid) that say how far the
     * engine got before it died: the load stage, then the top frames of a crash if one was
     * written. Short enough for a dialog. Blocking: call off the main thread.
     */
    fun engineLogTail(maxLines: Int = 6): String {
        val main = runLogcat(listOf("logcat", "-d", "-b", "main", "-v", "brief", "-t", "4000"))
        val progress = main
            .filter { l -> !l.contains("DEBUG") && PROGRESS.any { l.contains(it, ignoreCase = true) } }
            .map { it.substringAfter("): ", it).trim() }
            .takeLast(maxLines)
        val crash = lastTombstoneTop(runLogcat(listOf("logcat", "-d", "-b", "crash", "-v", "brief", "-t", "600")))
            .lineSequence()
            .filter { it.contains("Abort message") || Regex("#0[0-3]\\b").containsMatchIn(it) }
            .map { it.substringAfter("): ", it).trim() }
            .take(4)
            .toList()
        return (progress + crash).joinToString("\n")
    }

    /**
     * The native crash tombstone (abort message + top backtrace frames, which
     * name the crashing function) plus the last clip/mtmd progress lines. Empty
     * string if logcat can't be read. Runs blocking processes — call off-main.
     */
    fun captureRecentLog(): String {
        val sb = StringBuilder()

        // 1) Native progress from the main buffer (how far clip got).
        val main = runLogcat(listOf("logcat", "-d", "-b", "main", "-v", "brief", "-t", "4000"))
        val progress = main
            .filter { l -> !l.contains("DEBUG") && PROGRESS.any { l.contains(it, ignoreCase = true) } }
            .takeLast(20)
        if (progress.isNotEmpty()) {
            sb.append("── native log (last lines before crash) ──\n")
            sb.append(progress.joinToString("\n")).append("\n\n")
        }

        // 2) The crash tombstone: from the LAST "Fatal signal" FORWARD, so we
        //    keep the abort message + top frames #00.. (the crash location),
        //    NOT the bottom binder frames.
        val crash = runLogcat(listOf("logcat", "-d", "-b", "crash", "-v", "brief", "-t", "600"))
        val tomb = lastTombstoneTop(crash)
        if (tomb.isNotEmpty()) {
            sb.append("── crash (native) ──\n").append(tomb)
        } else if (progress.isEmpty()) {
            sb.append("(no tombstone / native log captured; logcat may be restricted on this build)")
        }
        return sb.toString().ifBlank { "(no native log captured)" }
    }

    /** From the most recent "Fatal signal", the next ~90 lines (abort + frames). */
    private fun lastTombstoneTop(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        val start = lines.indexOfLast { it.contains("Fatal signal") || it.contains("signal ") }
        if (start < 0) {
            // No signal marker matched; fall back to any DEBUG/abort lines.
            return lines.filter { it.contains("DEBUG") || it.contains("Abort message") }
                .takeLast(60).joinToString("\n")
        }
        return lines.subList(start, minOf(start + 90, lines.size)).joinToString("\n")
    }

    private fun runLogcat(cmd: List<String>): List<String> = try {
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val lines = BufferedReader(InputStreamReader(process.inputStream)).use { it.readLines() }
        process.destroy()
        lines
    } catch (t: Throwable) {
        listOf("(couldn't read logcat: ${t.message})")
    }
}
