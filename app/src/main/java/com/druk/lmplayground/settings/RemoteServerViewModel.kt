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
 * Backs Settings → Remote server. Persists a display name + the
 * OpenAI-compatible server URL + an enable flag, and drives a one-shot LAN
 * scan that fills the URL from a discovered server. The actual model is chosen
 * later in the chat's model picker, not here.
 */
class RemoteServerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = StoragePreferences(app)
    private val scanner = LocalServerScanner()

    private val _serverName = MutableLiveData(prefs.remoteServerName.orEmpty())
    val serverName: LiveData<String> = _serverName

    private val _serverUrl = MutableLiveData(prefs.remoteServerUrl.orEmpty())
    val serverUrl: LiveData<String> = _serverUrl

    private val _enabled = MutableLiveData(prefs.remoteServerEnabled)
    val enabled: LiveData<Boolean> = _enabled

    private val _scanning = MutableLiveData(false)
    val scanning: LiveData<Boolean> = _scanning

    private val _foundServers = MutableLiveData<List<FoundServer>>(emptyList())
    val foundServers: LiveData<List<FoundServer>> = _foundServers

    fun setServerName(value: String) {
        _serverName.value = value
        prefs.remoteServerName = value.trim().ifEmpty { null }
    }

    fun setServerUrl(value: String) {
        _serverUrl.value = value
        prefs.remoteServerUrl = value.trim().ifEmpty { null }
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

    /** Apply a discovered server: fill the URL + remember its type (model is
     *  picked later in chat). */
    fun useServer(server: FoundServer) {
        setServerUrl(server.url)
        prefs.remoteServerType = server.serverType
    }
}
