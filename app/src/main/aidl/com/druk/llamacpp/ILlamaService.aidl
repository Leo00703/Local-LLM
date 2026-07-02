package com.druk.llamacpp;

import com.druk.llamacpp.ILlamaGenerationCallback;
import com.druk.llamacpp.ILlamaProgressCallback;
import com.druk.llamacpp.SamplerParams;
import android.os.ParcelFileDescriptor;

/**
 * Cross-process interface to the llama.cpp inference engine.
 *
 * Handles to native objects are exposed as opaque positive int IDs. The
 * service maintains the int → native pointer maps; native pointers never
 * cross the process boundary.
 *
 * Models can be loaded either from a regular filesystem path (for tests
 * with /data/local/tmp models) or from a ParcelFileDescriptor (the SAF
 * case in production — the service dups the FD into its own process and
 * builds its own "fd:N" string for ggml_fopen_override).
 */
interface ILlamaService {
    // ── Global ───────────────────────────────────────────────────────────
    /** Initialize the llama backend. Idempotent. Returns 0 on success. */
    int initBackend();

    String systemInfo();

    // ── Probing (no weight load) ─────────────────────────────────────────
    /** Exactly one of (path, pfd) must be non-null. */
    @nullable String[] probeModelMetadata(in @nullable String path,
                                          in @nullable ParcelFileDescriptor pfd);

    // ── Model lifecycle ──────────────────────────────────────────────────
    /**
     * Load weights and return a positive modelId, or 0 on failure.
     * Exactly one of (path, pfd) must be non-null. The service holds the
     * PFD alive for the model's lifetime.
     *
     * When `disableRepack` is true the native loader turns off the CPU
     * weight-repacking buffer types (`use_extra_bufts`). Repacking copies
     * quantized weights into freshly-allocated RAM, which defeats mmap and
     * makes large models exceed available RAM. The UI sets this for models
     * that trip the RAM-fit gate so they load memory-mapped instead.
     */
    int loadModel(in @nullable String path,
                  in @nullable ParcelFileDescriptor pfd,
                  ILlamaProgressCallback progress,
                  boolean disableRepack);

    long getModelSize(int modelId);
    String getModelReport(int modelId);
    int getContextTrainSize(int modelId);
    boolean supportsThinking(int modelId);
    boolean supportsToolCalling(int modelId);
    /** True once a vision-capable mmproj is attached via [loadMmproj]. */
    boolean supportsVision(int modelId);
    /**
     * Attach a multimodal projector (mmproj) to an already-loaded model so it
     * can accept images. [path] is a real filesystem path (the mmproj is a
     * companion file copied next to / paired with the model). Returns true if
     * the projector loaded and supports vision.
     */
    boolean loadMmproj(int modelId, String path);
    void unloadModel(int modelId);

    // ── Session lifecycle ────────────────────────────────────────────────
    /** Returns a positive sessionId, or 0 on failure. */
    int createSession(int modelId, in SamplerParams params);
    void addMessage(int sessionId, String message, boolean enableThinking);

    /**
     * Replay a conversation history into a fresh session. The full history
     * may be too large for one binder transaction (~1 MB cap, shared with
     * other framework traffic). Each AIDL call here carries **one** string
     * at most so a per-string cap of 700 KB safely fits under the binder
     * limit even when other binder traffic is in flight.
     *
     * Protocol: [beginReplayHistory] (clears any pending buffer), then
     * for each turn call [appendReplayUser] followed by
     * [appendReplayAssistant] — strict alternation, in chronological
     * order. Conclude with exactly one [finalizeReplayHistory] which
     * flushes the buffered messages into the native session.
     */
    void beginReplayHistory(int sessionId);
    void appendReplayUser(int sessionId, String userMessage);
    void appendReplayAssistant(int sessionId, String assistantMessage);
    void finalizeReplayHistory(int sessionId);

    /**
     * Drives the generation loop on a service-owned worker thread, streaming
     * deltas through `cb` until llama returns non-zero, cancelGeneration is
     * called, or destroySession is called. Returns immediately. Termination
     * is signalled via cb.onGenerationFinished.
     */
    void startGeneration(int sessionId, ILlamaGenerationCallback cb);

    /** Idempotent. Sets a cancel flag the worker checks between tokens. */
    void cancelGeneration(int sessionId);

    String getSessionReport(int sessionId);
    void printSessionReport(int sessionId);
    void destroySession(int sessionId);

    // ── Tool / function calling ──────────────────────────────────────────
    /**
     * Configure the available tools for function calling on this session.
     * Pass an OpenAI-compatible JSON array of tool definitions, or "[]"
     * to disable tool calls for the next generation.
     */
    void setTools(int sessionId, String toolsJson);

    /**
     * After startGeneration ends with statusCode 2, fetch the pending
     * tool calls as a JSON array of { id, name, arguments } objects.
     */
    String getToolCallsJson(int sessionId);

    /**
     * Submit tool execution results so the next startGeneration resumes
     * from where the model left off. resultsJson is a JSON array of
     * { id, name, content } objects. Returns 0 on success.
     */
    int submitToolResults(int sessionId, String resultsJson, boolean enableThinking);

    /**
     * Configure the persistent preamble (system prompt + tools) KV cache
     * for this session. The native side reads/writes <path>.bin and
     * <path>.json. fingerprint is an opaque content-hash that must match
     * exactly between save and load — it covers (model, system prompt,
     * tools) so the cache silently regenerates whenever any of those
     * change. Lazy: actual disk I/O happens on the first addMessage()
     * call. Pass empty path to disable.
     */
    void setPreambleCachePath(int sessionId, String path, String fingerprint);

    // ── Foreground promotion (called by the proxy on model load/unload) ──
    void requestForeground();
    void releaseForeground();

    /**
     * Update the FGS notification's title and text. Called by the UI
     * process after a successful load so the (otherwise hidden) MIN-
     * priority notification surfaces the user-facing model name and the
     * formatted RAM footprint when expanded from the Silent group.
     * Either field may be null to keep the previous value (or fall back
     * to the default at first promote).
     */
    /**
     * @param actionBody when non-null/non-empty, the FGS notification gains
     *        "Copy" and "Share" action buttons that act on this text (the
     *        last assistant response). Pass null to remove the buttons —
     *        unlike title/text, this field is NOT sticky.
     */
    void setForegroundContent(in @nullable String title, in @nullable String text, in @nullable String actionBody);

    /**
     * Debug-only fault injection: kills the service process via
     * Process.killProcess(myPid). Used by instrumented tests to verify
     * crash isolation (the death recipient on the app side should fire,
     * the app should keep running, and the next call should rebind).
     */
    void crashForTest();
}
