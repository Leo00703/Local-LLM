package com.druk.lmplayground.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.tools.Tool
import com.druk.lmplayground.tools.ToolDefaults
import com.druk.lmplayground.tools.ToolRegistry

/**
 * Backs the Settings → Tools screen. Reads and writes the *global default*
 * enablement for each tool (Settings → Tools). A per-model override, set from a
 * model's generation-params sheet, takes precedence at load time — see
 * [StoragePreferences.effectiveToolEnabled].
 */
class ToolsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = StoragePreferences(app)

    init {
        // Opening the Tools screen counts as having "set up tools" — the
        // What's New prompt button is suppressed from now on (re-checked by
        // ConversationViewModel.refreshToolsSetupVisibility on resume).
        prefs.toolsSetupSeen = true
    }

    /** The catalog of available tools (same set the conversation engine uses). */
    val tools: List<Tool> = ToolRegistry.createDefault(app).getAllTools()

    private val _enabled = MutableLiveData(
        tools.associate { it.name to prefs.isToolEnabledDefault(it.name) }
    )
    val enabled: LiveData<Map<String, Boolean>> = _enabled

    fun setEnabled(toolName: String, value: Boolean) {
        prefs.setToolEnabledDefault(toolName, value)
        _enabled.value = _enabled.value.orEmpty().toMutableMap().apply { put(toolName, value) }
    }

    /** The "Enable all" switch: every tool's default on or off (Location only with its permission). */
    fun setAllEnabled(value: Boolean) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(), android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val next = _enabled.value.orEmpty().toMutableMap()
        for (name in ToolDefaults.bulkTargets(tools.map { it.name }, granted, value)) {
            prefs.setToolEnabledDefault(name, value)
            next[name] = value
        }
        _enabled.value = next
    }
}
