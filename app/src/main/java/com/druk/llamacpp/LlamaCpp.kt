package com.druk.llamacpp

import android.os.ParcelFileDescriptor

/**
 * App-facing facade for the inference engine.
 *
 * Forwards calls over AIDL to the in-process `LlamaService` (and, after
 * the service moves to `:llama`, the inference process). Replaces the
 * previous direct-JNI `LlamaCpp` — that class moved to
 * [com.druk.llamacpp.jni.NativeLlamaCpp] and is only used by the service
 * implementation and the instrumented JNI tests.
 */
class LlamaCpp(private val client: InferenceClient) {

    /** No-op — the service runs `initBackend()` automatically on bind. */
    fun init(): Int = 0

    fun systemInfo(): String = client.withService { it.systemInfo() }

    /**
     * Load a model identified by a filesystem path. Use this for paths
     * the service can open directly (e.g. `/data/local/tmp/...` in tests).
     * For SAF-backed files use the [ParcelFileDescriptor] overload — paths
     * containing `fd:N` are not valid cross-process.
     */
    fun loadModel(
        path: String,
        progressCallback: LlamaProgressCallback,
        disableRepack: Boolean = false,
        gpuLayers: Int = 0,
    ): LlamaModel {
        val id = client.withService { it.loadModel(path, null, wrapProgress(progressCallback), disableRepack, gpuLayers) }
        if (id == 0) throw IllegalStateException("loadModel failed for $path")
        return LlamaModel(client, id)
    }

    /**
     * Load a model from a [ParcelFileDescriptor]. Binder dups the FD into
     * the service process; the service builds its own `fd:N` string from
     * its dup and holds the PFD alive for the model's lifetime.
     */
    fun loadModel(
        pfd: ParcelFileDescriptor,
        progressCallback: LlamaProgressCallback,
        disableRepack: Boolean = false,
        gpuLayers: Int = 0,
    ): LlamaModel {
        val id = client.withService { it.loadModel(null, pfd, wrapProgress(progressCallback), disableRepack, gpuLayers) }
        if (id == 0) throw IllegalStateException("loadModel failed for pfd")
        return LlamaModel(client, id)
    }

    fun probeModelMetadata(path: String): Array<String>? =
        client.withService { it.probeModelMetadata(path, null) }

    fun probeModelMetadata(pfd: ParcelFileDescriptor): Array<String>? =
        client.withService { it.probeModelMetadata(null, pfd) }

    /**
     * Replace the foreground-service notification's title and text. Use
     * after a successful load to surface the loaded model's user-facing
     * name and RAM footprint when the user expands the Silent group in
     * the shade. Either argument may be null to keep the previous value.
     */
    fun setForegroundContent(title: String?, text: String?, actionBody: String? = null) {
        try {
            client.withService { it.setForegroundContent(title, text, actionBody) }
        } catch (_: Throwable) {
            // No-op — the FGS will keep whatever content it had, and the
            // next successful call will overwrite it.
        }
    }

    private fun wrapProgress(cb: LlamaProgressCallback) = object : ILlamaProgressCallback.Stub() {
        override fun onProgress(progress: Float) = cb.onProgress(progress)
    }
}
