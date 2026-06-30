package com.druk.lmplayground.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.druk.lmplayground.storage.StoragePreferences

/**
 * Backs Settings → Advanced. Currently one toggle: globally disable weight
 * repacking (load every model memory-mapped). Read at model-load time by
 * [com.druk.lmplayground.conversation.ConversationViewModel] straight from
 * [StoragePreferences].
 */
class AdvancedViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = StoragePreferences(app)

    private val _disableRepack = MutableLiveData(prefs.disableRepack)
    val disableRepack: LiveData<Boolean> = _disableRepack

    fun setDisableRepack(value: Boolean) {
        prefs.disableRepack = value
        _disableRepack.value = value
    }
}
