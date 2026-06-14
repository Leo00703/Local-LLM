package com.druk.lmplayground.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.druk.lmplayground.storage.StoragePreferences

/**
 * Backs Settings → Sound and Haptic. Two independent toggles, both ON by
 * default: the background-completion chime and the per-token generation
 * haptic. Values are read by [com.druk.lmplayground.conversation.ConversationViewModel]
 * at generation time straight from [StoragePreferences].
 */
class SoundHapticViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = StoragePreferences(app)

    private val _soundEnabled = MutableLiveData(prefs.soundOnCompletion)
    val soundEnabled: LiveData<Boolean> = _soundEnabled

    private val _hapticEnabled = MutableLiveData(prefs.hapticOnGeneration)
    val hapticEnabled: LiveData<Boolean> = _hapticEnabled

    private val _showStatsEnabled = MutableLiveData(prefs.showGenerationStats)
    val showStatsEnabled: LiveData<Boolean> = _showStatsEnabled

    fun setSoundEnabled(value: Boolean) {
        prefs.soundOnCompletion = value
        _soundEnabled.value = value
    }

    fun setHapticEnabled(value: Boolean) {
        prefs.hapticOnGeneration = value
        _hapticEnabled.value = value
    }

    fun setShowStatsEnabled(value: Boolean) {
        prefs.showGenerationStats = value
        _showStatsEnabled.value = value
    }
}
