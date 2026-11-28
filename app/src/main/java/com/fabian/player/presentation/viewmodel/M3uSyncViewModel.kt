package com.fabian.player.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fabian.player.R
import com.fabian.player.data.playlist.M3uSyncRepository
import com.fabian.player.data.playlist.M3uSyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class M3uSyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: M3uSyncRepository,
) : ViewModel() {
    val state: StateFlow<M3uSyncState> = repository.state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages

    fun selectFolder(uri: Uri) {
        viewModelScope.launch {
            runCatching { repository.configure(uri) }
                .onFailure { error ->
                    _messages.emit(error.message ?: context.getString(R.string.m3u_sync_folder_error))
                }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            runCatching { repository.syncNow() }
                .onFailure { error ->
                    _messages.emit(error.message ?: context.getString(R.string.m3u_sync_failed))
                }
        }
    }

    fun disable() {
        viewModelScope.launch {
            runCatching { repository.disable() }
                .onFailure { error ->
                    _messages.emit(error.message ?: context.getString(R.string.m3u_sync_disable_error))
                }
        }
    }
}
