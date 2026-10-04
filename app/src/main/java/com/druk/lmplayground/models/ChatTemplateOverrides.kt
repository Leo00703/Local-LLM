package com.druk.lmplayground.models

import android.content.Context
import android.util.Log

/**
 * Replacement chat templates for models whose published template is defective.
 *
 * The chat template lives inside the GGUF (`tokenizer.chat_template`) and is normally
 * authoritative. A few models ship one that cannot express something the model was
 * trained to do, and the symptom is invisible until a user hits it. Overriding is a
 * last resort, reserved for defects verified against the real file shipped here.
 *
 * Keyed by GGUF filename, so an override never applies to a model it was not checked
 * against. If a publisher fixes the template in a re-quant under the same filename, the
 * override would silently outlive the bug, so re-check an entry when its model file
 * changes (the unit test pins that the entry and its asset exist).
 */
object ChatTemplateOverrides {

    private const val TAG = "ChatTemplateOverrides"
    const val ASSET_DIR = "chat_templates"

    /**
     * GGUF filename -> asset file under [ASSET_DIR].
     *
     * SmolLM3 3B (bartowski build): its template describes the `<tool_call>` format in
     * the system prompt but never renders `message.tool_calls` (the string does not
     * occur in the embedded template). llama.cpp derives tool-call grammars
     * differentially, by rendering a tool call and diffing the output, so with nothing
     * rendered it derived none and reported no tool support: every call the model made
     * reached the chat as raw XML. The override adds the missing assistant branch, in
     * the exact format the system prompt asks for, and changes nothing else.
     */
    val OVERRIDES: Map<String, String> = mapOf(
        "HuggingFaceTB_SmolLM3-3B-Q4_K_M.gguf" to "HuggingFaceTB_SmolLM3-3B-Q4_K_M.jinja",
    )

    /** Cached per filename; a template is a few KB and is read once per model load. */
    private val cache = mutableMapOf<String, String?>()

    /**
     * The replacement template for [filename], or null to keep the GGUF's own. A missing
     * or unreadable asset degrades to null: the model still loads, just as it shipped.
     */
    @Synchronized
    fun forModel(context: Context, filename: String): String? {
        val asset = OVERRIDES[filename] ?: return null
        return cache.getOrPut(filename) {
            try {
                context.assets.open("$ASSET_DIR/$asset").bufferedReader().use { it.readText() }
                    // Jinja output is whitespace-sensitive: a CR from a Windows checkout
                    // would end up inside every rendered prompt.
                    .replace("\r\n", "\n")
                    .also { Log.i(TAG, "loaded override for $filename (${it.length} chars)") }
            } catch (e: Exception) {
                Log.e(TAG, "failed to read override asset $asset for $filename", e)
                null
            }
        }
    }
}
