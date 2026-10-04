package com.druk.lmplayground.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.remote.FoundServer
import com.druk.lmplayground.remote.LocalServerScanner
import com.druk.lmplayground.remote.SavedServer
import com.druk.lmplayground.remote.SavedServers
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Backs Settings → Remote server. Holds the list of saved servers (each a display name, an
 * OpenAI-compatible URL, an optional API key and the detected software), the form used to
 * add or edit one, the master on/off switch, and a one-shot LAN scan that fills the form
 * from a discovered server. Nothing is stored until Save; the model is chosen later in the
 * chat's model picker, which lists the saved servers that are online.
 */
class RemoteServerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = StoragePreferences(app)
    private val scanner = LocalServerScanner()

    private val _servers = MutableLiveData(prefs.savedRemoteServers())
    val servers: LiveData<List<SavedServer>> = _servers

    /** Id of the saved server loaded into the form, or null when the form adds a new one. */
    private val _editingId = MutableLiveData<String?>(null)
    val editingId: LiveData<String?> = _editingId

    private val _serverName = MutableLiveData("")
    val serverName: LiveData<String> = _serverName

    private val _serverUrl = MutableLiveData("")
    val serverUrl: LiveData<String> = _serverUrl

    private val _apiKey = MutableLiveData("")
    val apiKey: LiveData<String> = _apiKey

    private val _enabled = MutableLiveData(prefs.remoteServerEnabled)
    val enabled: LiveData<Boolean> = _enabled

    private val _scanning = MutableLiveData(false)
    val scanning: LiveData<Boolean> = _scanning

    private val _saving = MutableLiveData(false)
    val saving: LiveData<Boolean> = _saving

    private val _foundServers = MutableLiveData<List<FoundServer>>(emptyList())
    val foundServers: LiveData<List<FoundServer>> = _foundServers

    // Server software reported by a scan result that filled the form; applied on Save so the
    // scan's answer is not asked again. Cleared as soon as the address is edited by hand.
    private var pendingType: String = ""

    fun setServerName(value: String) {
        _serverName.value = value
    }

    fun setServerUrl(value: String) {
        _serverUrl.value = value
        pendingType = ""
    }

    fun setApiKey(value: String) {
        _apiKey.value = value
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

    /** Fill the form from a discovered server (the URL and its type); Save keeps it. */
    fun useServer(server: FoundServer) {
        _serverUrl.value = server.url
        pendingType = server.serverType
    }

    /** Load a saved server into the form to change it. */
    fun edit(server: SavedServer) {
        _editingId.value = server.id
        _serverName.value = server.name
        _serverUrl.value = server.url
        _apiKey.value = server.apiKey.orEmpty()
        pendingType = ""
    }

    /** Empty the form, ready to add another server. */
    fun newServer() {
        _editingId.value = null
        _serverName.value = ""
        _serverUrl.value = ""
        _apiKey.value = ""
        pendingType = ""
    }

    fun delete(server: SavedServer) {
        persist(SavedServers.remove(_servers.value.orEmpty(), server.id))
        if (_editingId.value == server.id) newServer()
    }

    /**
     * Save the form: add the server to the list, or update the one being edited. The server
     * software is identified here (unless a scan already did) so a hand-typed address still
     * gets the right logo; an unreachable server is saved all the same, just without a type.
     */
    fun save() {
        val url = SavedServers.normalizeUrl(_serverUrl.value.orEmpty())
        if (url.isEmpty() || _saving.value == true) return
        val name = _serverName.value.orEmpty()
        val key = _apiKey.value.orEmpty().trim().ifEmpty { null }
        val editing = _editingId.value
        val before = _servers.value.orEmpty()
        val previous = before.firstOrNull { it.id == editing } ?: SavedServers.findByUrl(before, url)
        _saving.value = true
        viewModelScope.launch {
            val type = when {
                pendingType.isNotEmpty() -> pendingType
                previous != null && SavedServers.sameEndpoint(previous.url, url) && previous.type.isNotEmpty() -> previous.type
                else -> scanner.identify(url, key).orEmpty()
            }
            val list = SavedServers.save(before, editing, UUID.randomUUID().toString(), name, url, key, type)
            persist(list)
            // The first server saved switches the feature on: otherwise it would sit in the
            // list and never appear in the model picker.
            if (before.isEmpty() && list.isNotEmpty()) setEnabled(true)
            newServer()
            _saving.value = false
        }
    }

    private fun persist(list: List<SavedServer>) {
        prefs.setSavedRemoteServers(list)
        _servers.value = list
    }
}
