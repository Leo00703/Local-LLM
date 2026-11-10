package com.druk.lmplayground.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.remote.FoundServer
import com.druk.lmplayground.remote.LocalServerScanner
import com.druk.lmplayground.remote.RemoteOpenAiClient
import com.druk.lmplayground.remote.SavedServer
import com.druk.lmplayground.remote.SavedServers
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A server about to be saved that looks like one already saved, reached at another address: the
 * user is asked whether it is the same machine ([existing]) before it becomes a second entry.
 */
data class MergeProposal(
    val existing: SavedServer,
    val name: String,
    val url: String,
    val apiKey: String?,
    val type: String,
    val fingerprint: String,
)

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

    private val _mergeProposal = MutableLiveData<MergeProposal?>(null)
    val mergeProposal: LiveData<MergeProposal?> = _mergeProposal

    /** How many saved servers are certainly the same machine as another (strong fingerprint). */
    private val _duplicateCount = MutableLiveData(SavedServers.duplicateGroups(prefs.savedRemoteServers()).sumOf { it.size - 1 })
    val duplicateCount: LiveData<Int> = _duplicateCount

    init {
        backfillFingerprints()
    }

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
            // Who is behind this address: the same machine may already be saved at another one.
            val fingerprint = RemoteOpenAiClient(url, key).identityFingerprint()
            if (editing == null && previous == null) {
                val same = SavedServers.findSameServer(before, fingerprint, url)
                if (same != null) {
                    _mergeProposal.value = MergeProposal(same, name, url, key, type, fingerprint)
                    _saving.value = false
                    return@launch
                }
            }
            val list = SavedServers.save(before, editing, UUID.randomUUID().toString(), name, url, key, type, fingerprint)
            persist(list)
            // The first server saved switches the feature on: otherwise it would sit in the
            // list and never appear in the model picker.
            if (before.isEmpty() && list.isNotEmpty()) setEnabled(true)
            newServer()
            _saving.value = false
        }
    }

    /** The answer to a [MergeProposal]: add the address to the existing server, or keep a separate one. */
    fun answerMerge(merge: Boolean) {
        val proposal = _mergeProposal.value ?: return
        _mergeProposal.value = null
        val before = _servers.value.orEmpty()
        val list = if (merge) {
            SavedServers.addAddress(before, proposal.existing.id, proposal.url, proposal.fingerprint)
        } else {
            SavedServers.save(before, null, UUID.randomUUID().toString(), proposal.name, proposal.url, proposal.apiKey, proposal.type, proposal.fingerprint)
        }
        persist(list)
        newServer()
    }

    /** Merge the servers that are certainly the same machine (the banner's button). */
    fun mergeDuplicates() {
        persist(SavedServers.mergeDuplicates(_servers.value.orEmpty()))
    }

    /**
     * Servers saved before fingerprints existed have none: read it from the ones that answer, so
     * doubles already in the list can be found. Quiet and best-effort.
     */
    private fun backfillFingerprints() {
        val missing = _servers.value.orEmpty().filter { it.fingerprint.isEmpty() }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            val found = missing.associate { it.id to RemoteOpenAiClient(it.url, it.apiKey).identityFingerprint() }
            // The list may have changed while the servers were asked: apply to what is there now.
            val updated = _servers.value.orEmpty().map { s ->
                val fp = found[s.id]
                if (s.fingerprint.isEmpty() && !fp.isNullOrEmpty()) s.copy(fingerprint = fp) else s
            }
            persist(updated)
        }
    }

    private fun persist(list: List<SavedServer>) {
        prefs.setSavedRemoteServers(list)
        _servers.value = list
        _duplicateCount.value = SavedServers.duplicateGroups(list).sumOf { it.size - 1 }
    }
}
