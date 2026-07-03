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
 * app's uid, so its ggml/mtmd/clip lines and any "Fatal signal" tombstone are
 * visible here.
 */
object NativeDiagnostics {

    // Lines worth keeping: our native tag, the ggml/mtmd/clip stack, libc/crash
    // markers, and OOM signals. Everything else in the buffer is noise.
    private val KEEP = listOf(
        "llama-android", "Llama", "mtmd", "clip", "ggml", "libllama",
        "Fatal signal", "signal ", "SIGSEGV", "SIGABRT", "SIGKILL", "abort",
        "GGML_ASSERT", "assert", "libc", "DEBUG", "tombstone",
        "lowmemorykiller", "lmkd", "OutOfMemory", "Cannot allocate",
        "failed to allocate", "alloc", "bad_alloc", "oom",
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
     * The last [maxLines] relevant native/crash lines from this app's logcat,
     * newest last. Empty string if logcat can't be read (some OEM builds block
     * even own-uid reads). Runs a blocking process — call off the main thread.
     */
    fun captureRecentLog(maxLines: Int = 40): String = try {
        // -d dumps and exits; main+crash buffers hold the ggml logs + any
        // debuggerd tombstone summary. logd filters to our uid automatically.
        val process = ProcessBuilder(
            listOf("logcat", "-d", "-v", "brief", "-b", "main,crash", "-t", "2000")
        ).redirectErrorStream(true).start()
        val lines = BufferedReader(InputStreamReader(process.inputStream)).use { it.readLines() }
        process.destroy()
        lines.filter { line -> KEEP.any { line.contains(it, ignoreCase = true) } }
            .takeLast(maxLines)
            .joinToString("\n")
            .ifBlank { "(no matching native log lines — logcat may be restricted on this build)" }
    } catch (t: Throwable) {
        "(couldn't read logcat: ${t.message})"
    }
}
