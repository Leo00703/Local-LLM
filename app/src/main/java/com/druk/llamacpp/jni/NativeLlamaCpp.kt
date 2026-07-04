package com.druk.llamacpp.jni

import com.druk.llamacpp.LlamaProgressCallback

/**
 * Direct JNI binding to llama.cpp. Loaded only inside the `:llama` process.
 *
 * Public app code should use `com.druk.llamacpp.LlamaCpp` (the binder proxy)
 * instead of constructing this class directly.
 */
class NativeLlamaCpp {

    companion object {
        init {
            System.loadLibrary("llamacpp")
        }
    }

    /**
     * @param stateDir a persistent app-private dir for the Vulkan-CLIP crash
     *   sentinel's marker files (survives :llama restarts). Empty disables the
     *   sentinel (used by tests).
     */
    external fun init(nativeLibDir: String, stateDir: String = ""): Int

    external fun systemInfo(): String

    /**
     * Returns `null` when the native load failed — e.g. the file is not a
     * valid GGUF, the architecture isn't supported by this build of
     * llama.cpp, or the file couldn't be opened. The wrapper is only
     * constructed on success.
     */
    external fun loadModel(
        path: String,
        progressCallback: LlamaProgressCallback,
        disableRepack: Boolean
    ): NativeLlamaModel?

    external fun probeModelMetadata(path: String): Array<String>?
}
