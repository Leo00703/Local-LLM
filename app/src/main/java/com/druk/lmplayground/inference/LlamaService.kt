package com.druk.lmplayground.inference

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import com.druk.llamacpp.ILlamaGenerationCallback
import com.druk.llamacpp.ILlamaProgressCallback
import com.druk.llamacpp.ILlamaService
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.llamacpp.SamplerParams
import com.druk.llamacpp.jni.NativeLlamaCpp
import com.druk.llamacpp.jni.NativeLlamaModel
import com.druk.llamacpp.jni.NativeLlamaSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Bound service that owns the llama.cpp native state.
 *
 * In step 3 this still runs in the main app process (same UID, same
 * memory). In step 5 the manifest declaration adds `android:process=":llama"`
 * so this service hosts the JNI in a separate OS process — at which point
 * a SIGSEGV inside llama.cpp only kills `:llama`, not the UI.
 *
 * The service hands out positive int handles (`modelId`, `sessionId`).
 * Native pointers never leave this process. Map entries are guarded by
 * `ConcurrentHashMap`'s implicit locking.
 */
class LlamaService : Service() {

    private val nativeLlamaCpp by lazy {
        NativeLlamaCpp().also { it.init(applicationInfo.nativeLibraryDir) }
    }

    private val nextModelId = AtomicInteger(1)
    private val nextSessionId = AtomicInteger(1)

    private class ModelEntry(
        val modelId: Int,
        val nativeModel: NativeLlamaModel,
        // Held alive for the model's lifetime when loaded from a PFD
        val pfd: ParcelFileDescriptor?,
    ) {
        /**
         * Set to true when the client called `unloadModel` but at least
         * one of the model's sessions still has a live worker. The model
         * stays in [models] until the last such worker exits and clears
         * the way via [tryFinalizeModelTeardown].
         */
        @Volatile var pendingDestroy: Boolean = false
    }

    private class SessionEntry(
        val sessionId: Int,
        val modelId: Int,
        val nativeSession: NativeLlamaSession,
    ) {
        /**
         * Active generation worker, or null if no generation is running.
         *
         * AtomicReference so we can `compareAndSet` to atomically claim
         * the slot in [startGeneration] — the binder dispatcher can
         * deliver two startGeneration calls for the same session
         * concurrently on different binder threads, and starting two
         * workers on the same `nativeSession` (two threads inside
         * `nativeSession.generate()`) is undefined behavior that
         * crashes `:llama`.
         */
        val worker = AtomicReference<GenerationWorker?>(null)

        /**
         * Set to true when the client called `destroySession` (or
         * `unloadModel` on the parent model) but the worker hadn't
         * exited yet. The entry stays in [sessions] under this flag so
         * future operations can observe the live worker; once the worker
         * actually exits, [onWorkerExit] sees the flag and finishes the
         * cleanup that was deferred.
         */
        @Volatile var pendingDestroy: Boolean = false

        /**
         * Accumulators for chunked replayHistory. Each call carries at
         * most one string (≤700 KB) so it fits under the binder cap; we
         * zip the two lists into the native call on finalize.
         */
        val pendingReplayUsers: MutableList<String> = mutableListOf()
        val pendingReplayAssistants: MutableList<String> = mutableListOf()
    }

    private val models = ConcurrentHashMap<Int, ModelEntry>()
    private val sessions = ConcurrentHashMap<Int, SessionEntry>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "LlamaService.onCreate (pid=${android.os.Process.myPid()})")
        InferenceNotification.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Mark every model and session for destroy, then tear down what
        // we can synchronously. Anything still pending (worker alive)
        // will leak until the worker exits, at which point onWorkerExit
        // → tryFinalizeModelTeardown will finish the cleanup. The
        // process is going away anyway, so the leak is bounded.
        models.values.forEach { it.pendingDestroy = true }
        sessions.values.toList().forEach { tearDownSession(it) }
        super.onDestroy()
    }

    /**
     * Mark [entry] for destroy and try to finish it now. Returns `true`
     * if the entry has been freed and removed from [sessions], `false`
     * if the worker is still alive — in which case the entry stays in
     * [sessions] under the `pendingDestroy` flag and will be finalized
     * by [onWorkerExit] when the worker eventually returns from
     * `nativeSession.generate()`.
     *
     * Critical for UAF avoidance: callers MUST NOT remove [entry] from
     * [sessions] themselves on a `false` return, otherwise a follow-up
     * `unloadModel` for the same model would no longer see the live
     * worker and would happily free the model out from under it.
     */
    private fun tearDownSession(entry: SessionEntry): Boolean {
        // Mark first — a racing onWorkerExit must observe pendingDestroy.
        entry.pendingDestroy = true
        val worker = entry.worker.get()
        if (worker == null) {
            return finalizeSessionTeardown(entry)
        }
        worker.cancel()
        if (worker.join()) {
            // Thread.run's finally has already fired onWorkerExit, which
            // saw pendingDestroy=true and finalized. Idempotent re-check.
            return !sessions.containsKey(entry.sessionId)
        }
        Log.w(
            TAG,
            "session ${entry.sessionId}: worker still alive after join budget; " +
                "deferring native destroy to onWorkerExit (entry stays in sessions)",
        )
        return false
    }

    /**
     * Called by the worker thread right before it exits. CAS-clears the
     * worker slot so we don't clobber a successor that may have been
     * installed by a concurrent `startGeneration` between the two
     * lifecycle events. If the entry has already been marked for
     * destroy, completes the teardown we couldn't do synchronously.
     */
    private fun onWorkerExit(sessionId: Int, finishedWorker: GenerationWorker) {
        val entry = sessions[sessionId] ?: return
        entry.worker.compareAndSet(finishedWorker, null)
        if (entry.pendingDestroy) {
            finalizeSessionTeardown(entry)
        }
    }

    /**
     * Free the native session and remove [entry] from [sessions], then
     * re-attempt any pending model teardown that this session was
     * blocking. Returns `true` if the entry was actually removed (false
     * if a concurrent finalize beat us to it — that's fine, the
     * native destroy is still safe).
     */
    private fun finalizeSessionTeardown(entry: SessionEntry): Boolean {
        // remove() returns the prior value; we own the destroy iff WE
        // removed it. Avoids double-free when destroySession races with
        // onWorkerExit.
        val removed = sessions.remove(entry.sessionId, entry)
        if (removed) {
            entry.nativeSession.destroy()
            tryFinalizeModelTeardown(entry.modelId)
        }
        return removed
    }

    /**
     * If [modelId] was marked for destroy and its last surviving session
     * worker has now exited, free the native model and PFD. No-op
     * otherwise.
     */
    private fun tryFinalizeModelTeardown(modelId: Int) {
        val entry = models[modelId] ?: return
        if (!entry.pendingDestroy) return
        val stillHasSessions = sessions.values.any { it.modelId == modelId }
        if (stillHasSessions) return
        if (models.remove(modelId, entry)) {
            entry.nativeModel.unloadModel()
            entry.pfd?.close()
            if (models.isEmpty()) demoteFromForeground()
        }
    }

    private val binder = object : ILlamaService.Stub() {

        override fun initBackend(): Int {
            // Lazy-init triggers System.loadLibrary + llama_backend_init
            nativeLlamaCpp
            return 0
        }

        override fun systemInfo(): String = nativeLlamaCpp.systemInfo()

        override fun probeModelMetadata(path: String?, pfd: ParcelFileDescriptor?): Array<String>? {
            val resolved = resolvePath(path, pfd) ?: return null
            return try {
                nativeLlamaCpp.probeModelMetadata(resolved)
            } finally {
                // probe doesn't retain the FD — close immediately
                pfd?.close()
            }
        }

        override fun loadModel(
            path: String?,
            pfd: ParcelFileDescriptor?,
            progress: ILlamaProgressCallback,
            disableRepack: Boolean,
        ): Int {
            val resolved = resolvePath(path, pfd) ?: return 0
            val nativeProgress = object : LlamaProgressCallback {
                override fun onProgress(p: Float) {
                    try { progress.onProgress(p) } catch (_: RemoteException) {}
                }
            }
            return try {
                val nativeModel = nativeLlamaCpp.loadModel(resolved, nativeProgress, disableRepack)
                if (nativeModel == null) {
                    Log.e(
                        TAG,
                        "native loadModel returned null (corrupt GGUF or " +
                            "unsupported architecture at $resolved)",
                    )
                    pfd?.close()
                    return 0
                }
                val id = nextModelId.getAndIncrement()
                models[id] = ModelEntry(id, nativeModel, pfd)
                // First model loaded → promote to foreground so the OS doesn't
                // evict the :llama process under memory pressure mid-inference.
                if (models.size == 1) promoteToForeground()
                id
            } catch (t: Throwable) {
                Log.e(TAG, "loadModel failed", t)
                pfd?.close()
                0
            }
        }

        override fun getModelSize(modelId: Int): Long =
            models[modelId]?.nativeModel?.getModelSize() ?: 0L

        override fun getModelReport(modelId: Int): String =
            models[modelId]?.nativeModel?.getModelReport() ?: ""

        override fun getContextTrainSize(modelId: Int): Int =
            models[modelId]?.nativeModel?.getContextTrainSize() ?: 0

        override fun supportsThinking(modelId: Int): Boolean =
            models[modelId]?.nativeModel?.supportsThinking() == true

        override fun supportsToolCalling(modelId: Int): Boolean =
            models[modelId]?.nativeModel?.supportsToolCalling() == true

        override fun supportsVision(modelId: Int): Boolean =
            models[modelId]?.nativeModel?.supportsVision() == true

        override fun loadMmproj(modelId: Int, path: String): Boolean =
            models[modelId]?.nativeModel?.loadMmproj(path) == true

        override fun unloadModel(modelId: Int) {
            val entry = models[modelId] ?: return
            // Mark for destroy first (DON'T remove from `models` yet).
            // Then tear down all attached sessions. tearDownSession leaves
            // any leaked entries in `sessions` under their pendingDestroy
            // flag; tryFinalizeModelTeardown then refuses to free the
            // model until the last such session's worker has exited.
            // This prevents the UAF where a worker would still be inside
            // nativeSession.generate() (and thus holding a llama_context
            // derived from this model) when we freed the model.
            entry.pendingDestroy = true
            sessions.values.filter { it.modelId == modelId }.toList().forEach { s ->
                tearDownSession(s)
            }
            tryFinalizeModelTeardown(modelId)
        }

        override fun createSession(modelId: Int, params: SamplerParams): Int {
            val model = models[modelId] ?: return 0
            val nativeSession = model.nativeModel.createSession(
                params.contextSize,
                params.temperature,
                params.topP,
                params.repetitionPenalty,
                params.topK,
                params.minP,
                params.seed,
                params.thinkingBudget,
                params.systemPrompt,
            ) ?: return 0
            val id = nextSessionId.getAndIncrement()
            sessions[id] = SessionEntry(id, modelId, nativeSession)
            return id
        }

        override fun addMessage(sessionId: Int, message: String, enableThinking: Boolean) {
            sessions[sessionId]?.nativeSession?.addMessage(message, enableThinking)
        }

        override fun setImageData(sessionId: Int, data: ByteArray) {
            sessions[sessionId]?.nativeSession?.setImageData(data)
        }

        override fun beginReplayHistory(sessionId: Int) {
            sessions[sessionId]?.let {
                it.pendingReplayUsers.clear()
                it.pendingReplayAssistants.clear()
            }
        }

        override fun appendReplayUser(sessionId: Int, userMessage: String) {
            sessions[sessionId]?.pendingReplayUsers?.add(userMessage)
        }

        override fun appendReplayAssistant(sessionId: Int, assistantMessage: String) {
            sessions[sessionId]?.pendingReplayAssistants?.add(assistantMessage)
        }

        override fun finalizeReplayHistory(sessionId: Int) {
            val entry = sessions[sessionId] ?: return
            val users = entry.pendingReplayUsers
            val assistants = entry.pendingReplayAssistants
            if (users.size != assistants.size) {
                Log.w(
                    TAG,
                    "finalizeReplayHistory: mismatched buffers " +
                        "users=${users.size} assistants=${assistants.size} — discarding",
                )
                users.clear(); assistants.clear()
                return
            }
            if (users.isEmpty()) return
            val u = users.toTypedArray()
            val a = assistants.toTypedArray()
            users.clear(); assistants.clear()
            entry.nativeSession.replayHistory(u, a)
        }

        override fun startGeneration(sessionId: Int, cb: ILlamaGenerationCallback) {
            val entry = sessions[sessionId] ?: run {
                try { cb.onGenerationFinished(-1) } catch (_: RemoteException) {}
                return
            }
            // Two-phase atomic claim, all serialized through the
            // entry.worker AtomicReference:
            //  1. Drain any prior worker (cancel + join). If join times
            //     out, refuse — prior is still inside generate() and a
            //     parallel worker on the same llama_context would crash.
            //  2. CAS-claim the slot from null to our new worker. If a
            //     concurrent startGeneration on a different binder
            //     thread already installed a different worker between
            //     phases 1 and 2, our CAS fails and we refuse — the
            //     other call will run.
            val prior = entry.worker.get()
            if (prior != null) {
                prior.cancel()
                if (!prior.join()) {
                    Log.w(
                        TAG,
                        "session $sessionId: prior worker still alive after join " +
                            "budget; refusing concurrent startGeneration",
                    )
                    try { cb.onGenerationFinished(-3) } catch (_: RemoteException) {}
                    return
                }
                // join() returning true means Thread.run's finally has
                // already executed onWorkerExit, which CAS-cleared the
                // slot to null (unless the slot has since been claimed).
            }
            // Construct the worker (Thread already alive but blocked on
            // its publish latch — see GenerationWorker docs). We must
            // call publish() on the winner OR cancel() on the loser so
            // the latched thread eventually exits.
            val newWorker = GenerationWorker(
                sessionId = sessionId,
                nativeSession = entry.nativeSession,
                cb = cb,
                onFinished = { id, w -> onWorkerExit(id, w) },
            )
            if (!entry.worker.compareAndSet(null, newWorker)) {
                Log.w(
                    TAG,
                    "session $sessionId: lost CAS for worker slot " +
                        "(concurrent startGeneration); refusing",
                )
                // The worker thread is alive on the latch — release it
                // via cancel() so it sees cancelled=true and exits.
                newWorker.cancel()
                try { cb.onGenerationFinished(-3) } catch (_: RemoteException) {}
                return
            }
            // Publish: lets the worker thread proceed past the latch
            // and start the actual generation loop. Until this call,
            // tearDownSession can safely cancel + join the worker
            // because the thread is alive (blocked on the latch) and
            // join() will not return prematurely.
            newWorker.publish()
        }

        override fun cancelGeneration(sessionId: Int) {
            // .cancel() just flips a @Volatile flag the worker checks
            // between tokens — safe to call even if the worker slot has
            // been swapped under us.
            sessions[sessionId]?.worker?.get()?.cancel()
        }

        override fun getSessionReport(sessionId: Int): String =
            sessions[sessionId]?.nativeSession?.getReport() ?: ""

        override fun printSessionReport(sessionId: Int) {
            sessions[sessionId]?.nativeSession?.printReport()
        }

        override fun destroySession(sessionId: Int) {
            // Don't pre-remove — tearDownSession only removes from
            // [sessions] after the native destroy actually succeeds.
            // Otherwise a still-running worker would be invisible to a
            // follow-up unloadModel and the model could be freed under it.
            val entry = sessions[sessionId] ?: return
            tearDownSession(entry)
        }

        override fun setTools(sessionId: Int, toolsJson: String) {
            sessions[sessionId]?.nativeSession?.setTools(toolsJson)
        }

        override fun getToolCallsJson(sessionId: Int): String =
            sessions[sessionId]?.nativeSession?.getToolCallsJson() ?: "[]"

        override fun submitToolResults(
            sessionId: Int,
            resultsJson: String,
            enableThinking: Boolean,
        ): Int {
            val entry = sessions[sessionId] ?: return 1
            return entry.nativeSession.submitToolResults(resultsJson, enableThinking)
        }

        override fun setPreambleCachePath(
            sessionId: Int,
            path: String,
            fingerprint: String,
        ) {
            sessions[sessionId]?.nativeSession?.setPreambleCachePath(path, fingerprint)
        }

        // Self-managed via loadModel/unloadModel — kept here for the AIDL
        // contract; callers don't need to invoke them.
        override fun requestForeground() = promoteToForeground()
        override fun releaseForeground() = demoteFromForeground()

        override fun setForegroundContent(title: String?, text: String?, actionBody: String?) {
            if (title != null) foregroundTitle = title
            if (text != null) foregroundText = text
            // Not sticky: each call sets the action body verbatim (null
            // clears the Copy/Share buttons for the loaded/generating states).
            foregroundActionBody = actionBody
            if (!isForeground) return
            try {
                val notification = InferenceNotification.build(
                    this@LlamaService,
                    foregroundTitle,
                    foregroundText,
                    foregroundActionBody,
                )
                getSystemService(NotificationManager::class.java)
                    ?.notify(InferenceNotification.NOTIFICATION_ID, notification)
            } catch (t: Throwable) {
                Log.w(TAG, "setForegroundContent notify failed", t)
            }
        }

        override fun crashForTest() {
            // Debug-only fault injection. In release builds this is a no-op
            // so production users can never trip the kill switch. Used by
            // instrumented tests and the debug-only Settings button to
            // validate crash isolation.
            if (!com.druk.lmplayground.BuildConfig.DEBUG) {
                Log.w(TAG, "crashForTest ignored in release build")
                return
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    @Volatile private var isForeground = false
    // Last values seen via setForegroundContent. Null until the UI process
    // calls in with the loaded model's name and formatted size; the build()
    // helper falls back to the localized defaults when null.
    @Volatile private var foregroundTitle: String? = null
    @Volatile private var foregroundText: String? = null
    // Response text for the Copy/Share notification actions; null = no buttons.
    @Volatile private var foregroundActionBody: String? = null

    @Synchronized
    private fun promoteToForeground() {
        if (isForeground) return
        try {
            val notification = InferenceNotification.build(
                this, foregroundTitle, foregroundText,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    InferenceNotification.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(InferenceNotification.NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed (continuing as bound-only)", t)
        }
    }

    @Synchronized
    private fun demoteFromForeground() {
        if (!isForeground) return
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
            // Clear so the next promote starts from the localized default
            // again until the UI updates with a fresh model name + size.
            foregroundTitle = null
            foregroundText = null
        } catch (t: Throwable) {
            Log.w(TAG, "stopForeground failed", t)
        }
    }

    /**
     * Map an AIDL (path?, pfd?) pair into a string the native side accepts.
     * "fd:N" is a sentinel recognized by our llama.cpp fork's patched
     * ggml_fopen / llama-mmap (see andriydruk/llama.cpp-android). Plain
     * libc reopen via /proc/self/fd/N would EACCES under Scoped Storage
     * — the kernel re-runs path-based permission checks with our UID
     * instead of inheriting the SAF fd's privilege. dup() of the
     * Binder-inherited fd is the only thing that works.
     */
    private fun resolvePath(path: String?, pfd: ParcelFileDescriptor?): String? {
        if (path != null) return path
        if (pfd != null) return "fd:${pfd.fd}"
        return null
    }

    companion object {
        private const val TAG = "LlamaService"
    }
}
