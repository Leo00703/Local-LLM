package com.druk.lmplayground.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.remote.FoundServer
import com.druk.lmplayground.remote.LocalServerScanner
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.launch

/**
 * Backs Settings → Remote server. Persists the OpenAI-compatible server URL,
 * model name, and an enable flag, and drives a one-shot LAN scan that fills
 * those fields from a discovered server.
 */
class RemoteServerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = StoragePreferences(app)
    private val scanner = LocalServerScanner()

    private val _serverUrl = MutableLiveData(prefs.remoteServerUrl.orEmpty())
    val serverUrl: LiveData<String> = _serverUrl

    private val _modelName = MutableLiveData(prefs.remoteServerModel.orEmpty())
    val modelName: LiveData<String> = _modelName

    private val _enabled = MutableLiveData(prefs.remoteServerEnabled)
    val enabled: LiveData<Boolean> = _enabled

    private val _scanning = MutableLiveData(false)
    val scanning: LiveData<Boolean> = _scanning

    private val _foundServers = MutableLiveData<List<FoundServer>>(emptyList())
    val foundServers: LiveData<List<FoundServer>> = _foundServers

    fun setServerUrl(value: String) {
        _serverUrl.value = value
        prefs.remoteServerUrl = value.trim().ifEmpty { null }
    }

    fun setModelName(value: String) {
        _modelName.value = value
        prefs.remoteServerModel = value.trim().ifEmpty { null }
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        prefs.remoteServerEnabled = value
    }

    fun scan() {
        if (_scanning.value == true) return
        _scanning.value = true
        _foundServers.value = emptyList()
        viewModelScope.launch {
            val results = scanner.scan()
            _foundServers.value = results
            _scanning.value = false
        }
    }

    /** Apply a discovered server: fill the URL and (if advertised) the model. */
    fun useServer(server: FoundServer) {
        setServerUrl(server.url)
        server.firstModel?.let { setModelName(it) }
    }
}
