package com.druk.lmplayground.benchmark

import android.os.SystemClock
import com.druk.llamacpp.GenerationModel
import com.druk.llamacpp.LlamaGenerationCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs one benchmark pass against an ALREADY-LOADED local model: create a fresh
 * session (llama.cpp perf counters are per-session and cumulative, so a clean
 * session per run is required), feed a fixed synthetic prompt, generate up to
 * [BenchmarkConfig.decodeTokens], then read the native performance report.
 *
 * Deterministic sampler (temperature 0 = greedy, fixed seed) so the run is
 * reproducible; note the SPEED numbers barely depend on sampling anyway, so the
 * real comparability comes from the fixed prompt + fixed token budget + fresh
 * session (see the benchmark plan). tok/s come from the authoritative native
 * report; TTFT is a wall-clock stamp (the local report doesn't expose it).
 */
class BenchmarkRunner(private val model: GenerationModel) {

    data class RunMetrics(
        val prefillTokPerSec: Float,
        val decodeTokPerSec: Float,
        val ttftMs: Int,
        val loadTimeMs: Int,
        val contextUsed: Int,
        val generatedTokens: Int,
        /** Experimental MTP status from the native report: "active", "unsupported", or "" (not requested). */
        val mtpStatus: String = "",
    )

    class BenchmarkException(message: String) : Exception(message)

    /**
     * One run. Must be called off the main thread is handled internally (the
     * AIDL session calls are blocking, so the whole body runs on [Dispatchers.Default]).
     * Bounding to [BenchmarkConfig.decodeTokens] is done by counting streaming
     * callbacks and cancelling generation (there is no native max-tokens param);
     * the report still reports the exact tokens actually decoded.
     */
    suspend fun runOnce(config: BenchmarkConfig): RunMetrics = withContext(Dispatchers.Default) {
        // Context must hold prefill + decode with a little headroom; this also
        // gives a natural stop (context full) as a backstop to the token cap.
        val contextSize = config.prefillTokens + config.decodeTokens + CONTEXT_MARGIN
        val session = model.createSession(
            contextSize,
            0f,   // temperature = greedy
            1f,   // topP (ignored at temp 0)
            1f,   // repetitionPenalty
            40,   // topK (ignored at temp 0)
            0f,   // minP (ignored at temp 0)
            0,    // seed (fixed)
            0,    // thinkingBudget (thinking disabled on the turn anyway)
            "",   // no system prompt
            config.kvCacheType,
            config.speculative,   // experimental self-MTP (builds the draft context)
            config.specNDraft,
        ) ?: throw BenchmarkException("Could not create a benchmark session (is a local model loaded?)")

        try {
            session.addMessage(buildSyntheticPrompt(config.prefillTokens), false)

            val count = AtomicInteger(0)
            val firstTokenMs = AtomicLong(0L)
            val lastTokenMs = AtomicLong(0L)
            val startMs = SystemClock.elapsedRealtime()
            val done = CompletableDeferred<Unit>()

            // LlamaGenerationCallback is a plain (non-fun) Kotlin interface, so it
            // needs an object expression, not a SAM lambda (matches every other
            // call site).
            val callback = object : LlamaGenerationCallback {
                override fun onFullResponse(response: String) {
                    val now = SystemClock.elapsedRealtime()
                    if (firstTokenMs.get() == 0L) firstTokenMs.set(now)
                    lastTokenMs.set(now)
                    if (count.incrementAndGet() >= config.decodeTokens && done.isActive) {
                        done.complete(Unit)
                    }
                }
            }

            coroutineScope {
                val genJob = launch {
                    try {
                        session.generateAll(callback)
                    } catch (_: CancellationException) {
                        // expected when we hit the token cap and cancel
                    } finally {
                        if (done.isActive) done.complete(Unit)
                    }
                }
                done.await()          // target tokens reached, or generation ended
                genJob.cancel()       // stop if still running (drains via cancelGeneration)
                genJob.join()
            }

            val report = session.getReport()
            // The native report only prints "Prompt eval"/"Generation" t/s when the
            // context's perf timing is enabled, which it is NOT (only the sampler's
            // is), so those lines are absent and would parse to 0. Compute
            // throughput from wall-clock instead, using the report's ALWAYS-present
            // token counts: prefill speed over the TTFT window (Edge Gallery's
            // AICore-path approach), decode speed over the first->last token window.
            val first = firstTokenMs.get()
            val last = lastTokenMs.get()
            val ttft = if (first > 0L) (first - startMs).toInt().coerceAtLeast(0) else 0
            val promptTokens = parseTokenCount(report, "Prompt tokens:")
            val genTokens = parseTokenCount(report, "Generated tokens:").takeIf { it > 0 } ?: count.get()
            val prefillSec = ttft / 1000f
            val decodeSec = (last - first).coerceAtLeast(0L) / 1000f
            RunMetrics(
                prefillTokPerSec = if (prefillSec > 0f && promptTokens > 0) promptTokens / prefillSec else 0f,
                decodeTokPerSec = if (decodeSec > 0f && genTokens > 0) genTokens / decodeSec else 0f,
                ttftMs = ttft,
                loadTimeMs = parseInt(report, "Load time:", "ms"),
                contextUsed = parseContextUsed(report),
                generatedTokens = genTokens,
                mtpStatus = parseMtpStatus(report),
            )
        } finally {
            session.destroy()
        }
    }

    /**
     * A filler passage sized to roughly [prefillTokens] (~4 chars/token) so the
     * prefill workload is stable, plus a continuation instruction so the model
     * actually decodes. Capped well under the AIDL binder budget.
     */
    private fun buildSyntheticPrompt(prefillTokens: Int): String {
        val unit = "The quick brown fox jumps over the lazy dog. "
        val targetChars = prefillTokens.coerceIn(1, 60_000) * 4
        val sb = StringBuilder(targetChars + 96)
        while (sb.length < targetChars) sb.append(unit)
        if (sb.length > MAX_PROMPT_CHARS) sb.setLength(MAX_PROMPT_CHARS)
        // A counting task decodes reliably to the token cap on any model at
        // temperature 0 (unlike "continue this text", which some instruct models
        // cut short) — keeps the decode-throughput measurement stable.
        sb.append("\n\nNow count upward from 1, writing one number per line, and keep going.")
        return sb.toString()
    }

    /** Parse "  Prompt tokens: 256" / "  Generated tokens: 256" -> the int. */
    private fun parseTokenCount(report: String, tag: String): Int {
        val line = report.lineSequence().firstOrNull { it.contains(tag) } ?: return 0
        return line.substringAfter(tag).trim().toIntOrNull() ?: 0
    }

    /** Parse "  Load time: 1234 ms" -> 1234 (the int before [unit]). */
    private fun parseInt(report: String, tag: String, unit: String): Int {
        val line = report.lineSequence().firstOrNull { it.contains(tag) } ?: return 0
        return line.substringAfter(tag).substringBefore(unit).trim().toIntOrNull() ?: 0
    }

    /** Parse "  Context: 42 / 4096 tokens" -> 42. */
    private fun parseContextUsed(report: String): Int {
        val line = report.lineSequence().firstOrNull { it.contains("Context:") } ?: return 0
        return line.substringAfter("Context:").substringBefore("/").trim().toIntOrNull() ?: 0
    }

    /** Parse "  MTP: active" -> "active" (or "unsupported"); "" when the line is absent. */
    private fun parseMtpStatus(report: String): String {
        val line = report.lineSequence().firstOrNull { it.contains("MTP:") } ?: return ""
        return line.substringAfter("MTP:").trim()
    }

    private companion object {
        const val CONTEXT_MARGIN = 128
        const val MAX_PROMPT_CHARS = 300_000 // < 700 KB UTF-16 binder cap
    }
}
