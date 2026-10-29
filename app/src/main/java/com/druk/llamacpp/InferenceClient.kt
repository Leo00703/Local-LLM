package com.druk.llamacpp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Owns the binding to the `LlamaService`, which runs in the separate `:llama`
 * process. Provides a [state] flow consumers can observe to react to
 * connection / crash transitions.
 *
 * Design notes:
 * - `bind()` is fire-and-forget; consumers `await()` for the connected
 *   state if they need a synchronous handle.
 * - A `linkToDeath` recipient moves the state to [InferenceState.Crashed]
 *   when the `:llama` process dies; [InferenceState.Crashed] is sticky until
 *   the crash is acknowledged.
 * - The class name of the service is supplied by the caller to avoid
 *   pulling the app module into the public llamacpp package.
 */
class InferenceClient(
    private val appContext: Context,
    private val serviceComponent: ComponentName,
) {
    private val _state = MutableStateFlow<InferenceState>(InferenceState.Connecting)
    val state: StateFlow<InferenceState> = _state

    private var connection: ServiceConnection? = null
    @Volatile private var bound = false
    @Volatile private var deathRecipient: DeathRecipient? = null

    /**
     * Most-recent binder from the system. After a crash Android auto-restarts
     * the bound service and `onServiceConnected` fires again with a fresh
     * binder — we stash it here so [acknowledgeCrash] can switch to it
     * without an extra unbind/rebind round trip.
     */
    @Volatile private var pendingService: ILlamaService? = null

    /** Bind to the service. Idempotent; safe to call multiple times. */
    @Synchronized
    fun bind() {
        if (bound) return
        bound = true
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val svc = ILlamaService.Stub.asInterface(binder)
                // Pre-warm: trigger backend init so the first user-facing call doesn't
                // pay the System.loadLibrary cost. initBackend is a synchronous binder
                // call that loads the native libraries and probes the CPU/GPU backends in
                // :llama (hundreds of ms), and this callback runs on the main thread, so
                // it goes on its own thread. It is idempotent and any later call that
                // needs the backend waits on the same lazy init, so nothing depends on it.
                Thread({
                    try { svc.initBackend() } catch (t: Throwable) {
                        Log.w(TAG, "initBackend failed during bind", t)
                    }
                }, "llama-prewarm").apply { isDaemon = true }.start()
                installDeathRecipient(binder)
                // Sticky-Crashed: if we already crashed, keep the state at
                // Crashed and just stash the new binder. The consumer must
                // call acknowledgeCrash() to opt in to using it.
                if (_state.value is InferenceState.Crashed) {
                    pendingService = svc
                } else {
                    _state.value = InferenceState.Connected(svc)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // Don't transition out of Crashed here either — same reason.
                if (_state.value !is InferenceState.Crashed) {
                    _state.value = InferenceState.Connecting
                }
            }
        }
        connection = conn
        appContext.bindService(
            Intent().setComponent(serviceComponent),
            conn,
            Context.BIND_AUTO_CREATE,
        )
    }

    /**
     * After the consumer has surfaced the crash to the user (and the user
     * is ready to retry), call this to leave the Crashed state and use
     * the freshly-bound service. If Android hasn't re-bound yet, returns
     * null and the consumer should observe [state] to see the next Connected.
     */
    @Synchronized
    fun acknowledgeCrash(): ILlamaService? {
        val pending = pendingService
        pendingService = null
        if (pending != null) {
            _state.value = InferenceState.Connected(pending)
        } else {
            _state.value = InferenceState.Connecting
        }
        return pending
    }

    /** Suspend until the service is [Connected]. */
    suspend fun awaitConnected(): ILlamaService {
        val s = _state.first { it is InferenceState.Connected }
        return (s as InferenceState.Connected).service
    }

    /**
     * Synchronous accessor — returns the service immediately if connected,
     * **blocks briefly** if the initial bind is still in progress (state
     * == Connecting), throws if the service has crashed.
     *
     * Callers running on a coroutine should prefer [awaitConnected]
     * (suspend, no blocking). This entry point serves the non-suspend proxy
     * methods (`addMessage`, `probeModelMetadata`, `loadModel`, via
     * [withService]) that are invoked from background dispatchers
     * (Default / IO) where a short block is acceptable.
     *
     * @param waitForBindMs how long to block if state is Connecting
     * before giving up. Default is generous because the bind is one
     * lightweight binder transaction; if it's not done in 10 s the
     * service process is wedged.
     */
    fun requireConnected(waitForBindMs: Long = 10_000): ILlamaService {
        val s = _state.value
        if (s is InferenceState.Connected) return s.service
        if (s is InferenceState.Connecting) {
            // Race the bind. runBlocking is fine here because production
            // callers are already on background dispatchers (Dispatchers
            // .Default / IO) — never the main thread.
            val service = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(waitForBindMs) { awaitConnected() }
            }
            if (service != null) return service
            throw InferenceUnavailableException(
                "Inference service didn't connect within ${waitForBindMs}ms"
            )
        }
        throw InferenceUnavailableException("Inference service is ${s::class.simpleName}")
    }

    /**
     * Run [block] against a connected service, converting binder-death
     * exceptions into [InferenceUnavailableException] and flipping the
     * state to Crashed so observers can react. Use this from proxy
     * classes (LlamaModel, LlamaCpp, LlamaGenerationSession) so callers
     * see one exception type regardless of how the service went away.
     */
    fun <T> withService(block: (ILlamaService) -> T): T {
        val svc = requireConnected()
        try {
            return block(svc)
        } catch (e: DeadObjectException) {
            handleBinderDeath(e)
            throw InferenceUnavailableException("Inference service died: ${e.message}")
        } catch (e: RemoteException) {
            // Other RemoteExceptions are rarer but still indicate the
            // transaction couldn't complete — treat the same as dead.
            handleBinderDeath(e)
            throw InferenceUnavailableException("Inference service transaction failed: ${e.message}")
        }
    }

    private fun handleBinderDeath(cause: Throwable) {
        Log.w(TAG, "AIDL call failed, marking service Crashed", cause)
        synchronized(this) {
            pendingService = null
            if (_state.value !is InferenceState.Crashed) {
                _state.value = InferenceState.Crashed("died")
            }
        }
    }

    fun unbind() {
        val conn = connection ?: return
        try { appContext.unbindService(conn) } catch (_: IllegalArgumentException) {}
        connection = null
        bound = false
        _state.value = InferenceState.Connecting
    }

    private fun installDeathRecipient(binder: IBinder) {
        val recipient = DeathRecipient {
            Log.w(TAG, "binder died")
            // Sticky Crashed: stays until acknowledgeCrash() is called.
            // Android will auto-restart the bound service and onServiceConnected
            // will fire with a fresh binder, which we stash in pendingService.
            pendingService = null
            _state.value = InferenceState.Crashed("native")
        }
        try {
            binder.linkToDeath(recipient, 0)
            deathRecipient = recipient
        } catch (t: Throwable) {
            Log.w(TAG, "linkToDeath failed", t)
        }
    }

    companion object {
        private const val TAG = "InferenceClient"
    }
}
