package com.druk.lmplayground.settings

import android.app.Application
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.App
import com.druk.lmplayground.data.MemoryNoteEntity
import com.druk.lmplayground.data.MemoryRepository
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.launch

/**
 * Backs the Memory management screen: the reactive list of saved notes plus the
 * opt-in master toggle. CRUD mirrors [SystemPromptsViewModel]; the model can
 * still read and write the same notes through the "memory" tool.
 */
class MemoryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository: MemoryRepository? = (app as? App)?.memoryRepository
    private val prefs = StoragePreferences(app)

    val notes: LiveData<List<MemoryNoteEntity>> =
        repository?.getAllLive() ?: MutableLiveData(emptyList())

    private val _memoryEnabled = MutableLiveData(prefs.memoryEnabled)
    val memoryEnabled: LiveData<Boolean> = _memoryEnabled

    @MainThread
    fun setMemoryEnabled(enabled: Boolean) {
        prefs.memoryEnabled = enabled
        _memoryEnabled.value = enabled
    }

    @MainThread
    fun addNote(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository?.add(trimmed) }
    }

    @MainThread
    fun updateNote(id: Long, content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            deleteNote(id)
            return
        }
        viewModelScope.launch {
            val existing = repository?.getById(id) ?: return@launch
            repository.update(
                existing.copy(content = trimmed, updatedAt = System.currentTimeMillis())
            )
        }
    }

    @MainThread
    fun deleteNote(id: Long) {
        viewModelScope.launch { repository?.delete(id) }
    }

    @MainThread
    fun clearAll() {
        viewModelScope.launch { repository?.deleteAll() }
    }
}
