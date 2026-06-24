package com.druk.lmplayground.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

class StoragePreferences(context: Context) {

    private val prefs = context.getSharedPreferences("storage_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_URI = "model_storage_uri"
    }

    var modelStorageUri: Uri?
        get() = prefs.getString(KEY_URI, null)?.toUri()
        set(value) = prefs.edit { putString(KEY_URI, value?.toString()) }

    /** True once the user has tapped the What's New "Set up tools" button. */
    var toolsSetupSeen: Boolean
        get() = prefs.getBoolean("tools_setup_seen", false)
        set(value) = prefs.edit { putBoolean("tools_setup_seen", value) }

    /** Play a chime when generation finishes while the app is backgrounded. */
    var soundOnCompletion: Boolean
        get() = prefs.getBoolean("sound_on_completion", true)
        set(value) = prefs.edit { putBoolean("sound_on_completion", value) }

    /** Per-token typewriter haptic while a response streams in. */
    var hapticOnGeneration: Boolean
        get() = prefs.getBoolean("haptic_on_generation", true)
        set(value) = prefs.edit { putBoolean("haptic_on_generation", value) }

    /** Show per-message generation stats (tokens, speed, time-to-first-token). */
    var showGenerationStats: Boolean
        get() = prefs.getBoolean("show_generation_stats", true)
        set(value) = prefs.edit { putBoolean("show_generation_stats", value) }

    // --- Remote (OpenAI-compatible) server ---

    /** Base URL of the remote server, e.g. "http://192.168.1.42:1234". Null = unset. */
    var remoteServerUrl: String?
        get() = prefs.getString("remote_server_url", null)
        set(value) = prefs.edit { putString("remote_server_url", value) }

    /** Model id to request from the remote server. Null = unset. */
    var remoteServerModel: String?
        get() = prefs.getString("remote_server_model", null)
        set(value) = prefs.edit { putString("remote_server_model", value) }

    /** When true, the chat can use the remote server instead of a local model. */
    var remoteServerEnabled: Boolean
        get() = prefs.getBoolean("remote_server_enabled", false)
        set(value) = prefs.edit { putBoolean("remote_server_enabled", value) }

    fun getCustomModelMetadata(filename: String): Pair<String, Boolean>? {
        val value = prefs.getString("custom_model_$filename", null) ?: return null
        val parts = value.split("|", limit = 2)
        if (parts.size != 2) return null
        return Pair(parts[0], parts[1].toBoolean())
    }

    fun setCustomModelMetadata(filename: String, name: String, hasChatTemplate: Boolean) {
        prefs.edit { putString("custom_model_$filename", "$name|$hasChatTemplate") }
    }

    fun removeCustomModelMetadata(filename: String) {
        prefs.edit { remove("custom_model_$filename") }
    }

    fun getModelGenerationParams(filename: String): Map<String, Float>? {
        val json = prefs.getString("gen_params_$filename", null) ?: return null
        return try {
            json.split(",").associate {
                val (k, v) = it.split("=", limit = 2)
                k to v.toFloat()
            }
        } catch (_: Exception) { null }
    }

    fun setModelGenerationParams(filename: String, params: Map<String, Float>) {
        val encoded = params.entries.joinToString(",") { "${it.key}=${it.value}" }
        prefs.edit { putString("gen_params_$filename", encoded) }
    }

    // --- Tool enablement (two-level: global default + per-model override) ---
    //
    // The global default (set in Settings → Tools) applies to every model; a
    // per-model override (set in a model's generation-params sheet) wins for
    // that model only. Tools are OFF by default.

    fun isToolEnabledDefault(toolName: String): Boolean {
        return prefs.getBoolean("tool_enabled_$toolName", false)
    }

    fun setToolEnabledDefault(toolName: String, enabled: Boolean) {
        prefs.edit { putBoolean("tool_enabled_$toolName", enabled) }
    }

    fun hasToolOverride(filename: String, toolName: String): Boolean {
        return prefs.contains("tool_override_${filename}_$toolName")
    }

    fun setToolOverride(filename: String, toolName: String, enabled: Boolean) {
        prefs.edit { putBoolean("tool_override_${filename}_$toolName", enabled) }
    }

    /** Resolved enablement for a tool on a specific model: override if set, else global default. */
    fun effectiveToolEnabled(filename: String, toolName: String): Boolean {
        return if (hasToolOverride(filename, toolName)) {
            prefs.getBoolean("tool_override_${filename}_$toolName", false)
        } else {
            isToolEnabledDefault(toolName)
        }
    }

    // --- Detected model capabilities cache ---
    //
    // Capabilities (tool calling, thinking) read from a model's chat template at
    // load time, cached per filename so the model list can show accurate badges
    // for models the user has already loaded (including custom GGUFs).

    fun getDetectedCaps(filename: String): Pair<Boolean, Boolean>? {
        if (!prefs.contains("caps_tools_$filename")) return null
        return Pair(
            prefs.getBoolean("caps_tools_$filename", false),
            prefs.getBoolean("caps_thinking_$filename", false),
        )
    }

    fun setDetectedCaps(filename: String, supportsTools: Boolean, supportsThinking: Boolean) {
        prefs.edit {
            putBoolean("caps_tools_$filename", supportsTools)
            putBoolean("caps_thinking_$filename", supportsThinking)
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }
}
