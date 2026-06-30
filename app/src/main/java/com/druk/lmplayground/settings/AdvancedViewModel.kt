package com.druk.lmplayground.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.druk.lmplayground.storage.StoragePreferences

/**
 * Backs Settings → Advanced. Two toggles: show per-message generation stats, and
 * globally disable weight repacking (load every model memory-mapped). Both are
 * read at generation / model-load time by
 * [com.druk.lmplayground.conversation.ConversationViewModel] straight from
 * [StoragePreferences].
 */
class AdvancedViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = StoragePreferences(app)

    private val _showGenerationStats = MutableLiveData(prefs.showGenerationStats)
    val showGenerationStats: LiveData<Boolean> = _showGenerationStats

    private val _disableRepack = MutableLiveData(prefs.disableRepack)
    val disableRepack: LiveData<Boolean> = _disableRepack

    fun setShowGenerationStats(value: Boolean) {
        prefs.showGenerationStats = value
        _showGenerationStats.value = value
    }

    fun setDisableRepack(value: Boolean) {
        prefs.disableRepack = value
        _disableRepack.value = value
    }
}
