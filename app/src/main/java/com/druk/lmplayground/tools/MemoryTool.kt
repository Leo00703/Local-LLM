package com.druk.lmplayground.tools

import android.content.Context
import com.druk.lmplayground.data.AppDatabase
import com.druk.lmplayground.data.MemoryNoteEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lets the model save and recall short notes/facts that persist across chats,
 * stored locally in Room. Bounded (max notes + max length) so it can't grow
 * unchecked. Runs off the main thread (the tool loop), so the synchronous DAO
 * calls are safe.
 */
class MemoryTool(private val context: Context) : Tool {
    override val name = "memory"
    override val description = "Save and recall notes or facts that persist across chats. Use action \"save\" to remember something (with \"content\", optional \"category\"), \"list\" to recall all saved notes, \"update\" to edit one (with its \"id\" and new \"content\"), or \"delete\" to remove one (with its \"id\"). Notes are stored only on this device."
    override val parametersSchema = """{"type":"object","properties":{"action":{"type":"string","description":"\"save\", \"list\", \"update\", or \"delete\""},"content":{"type":"string","description":"The note text, required for \"save\" and \"update\""},"id":{"type":"integer","description":"The note id, required for \"update\" and \"delete\""},"category":{"type":"string","description":"Optional short tag for a saved note, e.g. \"preferences\", \"projects\""}},"required":["action"]}"""

    private val dao get() = AppDatabase.getInstance(context).memoryDao()

    override fun execute(arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            when (args.optString("action").trim().lowercase()) {
                "save", "add", "remember", "store" -> {
                    val content = args.optString("content").trim()
                    if (content.isEmpty()) return errorJson("Nothing to save: 'content' is empty.")
                    if (content.length > MAX_LEN) return errorJson("Note too long (max $MAX_LEN characters).")
                    if (dao.count() >= MAX_NOTES) {
                        return errorJson("Memory is full ($MAX_NOTES notes). Delete some before saving more.")
                    }
                    val category = args.optString("category").trim().take(MAX_CATEGORY_LEN).ifEmpty { null }
                    val now = System.currentTimeMillis()
                    val id = dao.insert(
                        MemoryNoteEntity(
                            content = content,
                            createdAt = now,
                            updatedAt = now,
                            category = category,
                        )
                    )
                    JSONObject().put("saved", true).put("id", id).toString()
                }
                "list", "recall", "get", "all" -> {
                    val arr = JSONArray()
                    for (note in dao.getAll()) {
                        arr.put(
                            JSONObject()
                                .put("id", note.id)
                                .put("content", note.content)
                                .put("createdAt", note.createdAt)
                                .apply { note.category?.let { put("category", it) } }
                        )
                    }
                    JSONObject().put("notes", arr).put("count", arr.length()).toString()
                }
                "update", "edit", "change" -> {
                    val id = args.optLong("id", -1L)
                    if (id < 0L) return errorJson("Provide the numeric 'id' of the note to update (see the list action).")
                    val content = args.optString("content").trim()
                    if (content.isEmpty()) return errorJson("Nothing to update: 'content' is empty.")
                    if (content.length > MAX_LEN) return errorJson("Note too long (max $MAX_LEN characters).")
                    val existing = dao.getById(id) ?: return errorJson("No note with id $id.")
                    val category = args.optString("category").trim().take(MAX_CATEGORY_LEN)
                        .ifEmpty { existing.category }
                    dao.update(
                        existing.copy(
                            content = content,
                            category = category,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    JSONObject().put("updated", true).put("id", id).toString()
                }
                "delete", "remove", "forget" -> {
                    val id = args.optLong("id", -1L)
                    if (id < 0L) return errorJson("Provide the numeric 'id' of the note to delete (see the list action).")
                    val removed = dao.deleteById(id)
                    JSONObject().put("deleted", removed > 0).toString()
                }
                else -> errorJson("Unknown action. Use \"save\", \"list\", \"update\", or \"delete\".")
            }
        } catch (e: Exception) {
            errorJson(e.message ?: "Memory operation failed")
        }
    }

    private fun errorJson(message: String) = """{"error":"${message.replace("\"", "'")}"}"""

    companion object {
        private const val MAX_LEN = 2000
        private const val MAX_NOTES = 200
        private const val MAX_CATEGORY_LEN = 40
    }
}
