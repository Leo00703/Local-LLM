package com.druk.lmplayground.storage

import android.util.Log

/**
 * Startup janitor: remove model files that a newer catalog build has replaced.
 *
 * When the catalog swaps a model for a better build of the same weights (Qwen 3.5 went
 * from Q3_K_M to Q4_K_M), the old file stays in the user's folder, taking up gigabytes
 * for a model that is no longer offered. It is deleted only once its replacement is
 * already on disk: a user who has not downloaded the new build yet keeps a working
 * model, and nothing is ever removed in favour of a download that has not finished
 * (downloads stream into `<name>.part` and are renamed on completion, so a `.gguf` of
 * the new name only exists once it is whole).
 *
 * The shared vision projector is never touched: the old and the new build use the same
 * add-on file.
 */
object SupersededFileCleanup {

    private const val TAG = "SupersededFileCleanup"

    /** Retired file name -> the file that replaces it. */
    internal val SUPERSEDED: Map<String, String> = mapOf(
        "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf" to "Qwen_Qwen3.5-0.8B-Q4_K_M.gguf",
        "Qwen_Qwen3.5-2B-Q3_K_M.gguf" to "Qwen_Qwen3.5-2B-Q4_K_M.gguf",
        "Qwen_Qwen3.5-4B-Q3_K_M.gguf" to "Qwen_Qwen3.5-4B-Q4_K_M.gguf",
    )

    /** Every retired file name, for the catalog consistency checks. */
    fun supersededFilenames(): Set<String> = SUPERSEDED.keys

    /**
     * The retired files that can go now: those whose replacement is also in [present].
     * Pure, so the rule can be unit-tested without a storage folder.
     */
    internal fun selectDeletable(present: Set<String>): List<String> =
        SUPERSEDED.filter { (old, replacement) -> old in present && replacement in present }.keys.toList()

    /**
     * Delete every retired file whose replacement is already downloaded. Does file I/O, so
     * call it off the main thread. A folder the app can no longer read just means there is
     * nothing to do.
     *
     * @return total bytes reclaimed.
     */
    fun run(repository: StorageRepository): Long {
        val files = try {
            repository.getModelFiles()
        } catch (e: Exception) {
            Log.w(TAG, "Could not list the model folder: ${e.message}")
            return 0L
        }
        val sizes = files.associate { it.name to it.sizeBytes }
        var reclaimed = 0L
        for (name in selectDeletable(sizes.keys)) {
            val deleted = try {
                repository.deleteModel(name)
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete $name: ${e.message}")
                false
            }
            if (deleted) {
                reclaimed += sizes[name] ?: 0L
                Log.i(TAG, "Removed $name, replaced by ${SUPERSEDED.getValue(name)}")
            }
        }
        if (reclaimed > 0) {
            Log.i(TAG, "Reclaimed ${reclaimed / 1_000_000} MB from superseded model files")
        }
        return reclaimed
    }
}
