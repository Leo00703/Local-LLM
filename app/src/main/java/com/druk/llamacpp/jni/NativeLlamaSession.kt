package com.druk.llamacpp.jni

import com.druk.llamacpp.LlamaGenerationCallback

class NativeLlamaSession {

    private var nativeHandle: Long = 0

    external fun generate(callback: LlamaGenerationCallback): Int

    external fun addMessage(message: String, enableThinking: Boolean)

    /** Stage encoded image bytes (jpg/png/…) for the next [addMessage] turn. */
    external fun setImageData(data: ByteArray)

    /**
     * Attach [model]'s already-loaded projector (mmproj) to this LIVE session —
     * the lazy vision flow loads the projector after sessions exist. Returns
     * true if a vision-capable projector is now attached. Call only between
     * turns, never while this session is generating.
     */
    external fun attachProjector(model: NativeLlamaModel): Boolean

    /** Interrupt an in-progress decode (prompt eval or generation) ASAP. */
    external fun requestAbort()

    external fun printReport()

    external fun getReport(): String

    external fun replayHistory(userMessages: Array<String>, assistantMessages: Array<String>)

    external fun setTools(toolsJson: String)

    external fun getToolCallsJson(): String

    external fun submitToolResults(resultsJson: String, enableThinking: Boolean): Int

    external fun renderPreambleString(enableThinking: Boolean): String

    external fun setPreambleCachePath(path: String, fingerprint: String)

    external fun destroy()
}
