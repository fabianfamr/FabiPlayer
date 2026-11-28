package com.fabian.player.presentation.jellyfin.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fabian.player.R
import com.fabian.player.data.database.JellyfinPlaylistEntity
import com.fabian.player.data.model.Song
import com.fabian.player.data.jellyfin.JellyfinRepository
import com.fabian.player.data.jellyfin.model.JellyfinLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.map

@HiltViewModel
class JellyfinDashboardViewModel @Inject constructor(
    private val repository: JellyfinRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val playlists: StateFlow<ImmutableList<JellyfinPlaylistEntity>> = repository.getPlaylists()
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.Lazily, persistentListOf())

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _selectedPlaylistSongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val selectedPlaylistSongs: StateFlow<ImmutableList<Song>> = _selectedPlaylistSongs.asStateFlow()

    private val _libraries = MutableStateFlow<ImmutableList<JellyfinLibrary>>(persistentListOf())
    val libraries: StateFlow<ImmutableList<JellyfinLibrary>> = _libraries.asStateFlow()

    private val _librariesLoadFailed = MutableStateFlow(false)
    val librariesLoadFailed: StateFlow<Boolean> = _librariesLoadFailed.asStateFlow()

    val selectedLibraryIds: StateFlow<Set<String>> = repository.selectedLibraryIdsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    val syncFavorites: StateFlow<Boolean> = repository.syncFavoritesFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    private val _librarySelectionNeedsSync = MutableStateFlow(false)
    val librarySelectionNeedsSync: StateFlow<Boolean> = _librarySelectionNeedsSync.asStateFlow()

    val username: String? get() = repository.username
    val serverUrl: String? get() = repository.serverUrl
    val isLoggedIn: StateFlow<Boolean> = repository.isLoggedInFlow

    init {
        loadLibraries()
        if (repository.isFullSyncDue) {
            syncAllPlaylistsAndSongs()
        }
    }

    fun loadLibraries() {
        viewModelScope.launch {
            repository.getLibraries()
                .onSuccess { libraries ->
                    _libraries.value = libraries.toImmutableList()
                    _librariesLoadFailed.value = false
                }
                .onFailure {
                    _libraries.value = persistentListOf()
                    _librariesLoadFailed.value = true
                }
        }
    }

    fun setSelectedLibraryIds(libraryIds: Set<String>) {
        viewModelScope.launch {
            repository.setSelectedLibraryIds(libraryIds)
            _librarySelectionNeedsSync.value = true
        }
    }

    fun setSyncFavorites(enabled: Boolean) {
        viewModelScope.launch {
            repository.setSyncFavoritesEnabled(enabled)
            if (enabled) {
                val result = repository.syncFavorites()
                _syncMessage.value = if (result.isSuccess) {
                    context.getString(R.string.dash_jellyfin_sync_favorites_synced)
                } else {
                    context.getString(R.string.dash_jellyfin_sync_favorites_failed)
                }
            }
        }
    }

    fun syncAllPlaylistsAndSongs() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = context.getString(R.string.dash_jellyfin_syncing_all)
            val result = repository.syncAllPlaylistsAndSongs()
            result.fold(
                onSuccess = { summary ->
                    _librarySelectionNeedsSync.value = false
                    _syncMessage.value = if (summary.failedPlaylistCount == 0) {
                        context.getString(
                            R.string.dash_jellyfin_synced_all_format,
                            summary.playlistCount,
                            summary.syncedSongCount
                        )
                    } else {
                        context.getString(
                            R.string.dash_jellyfin_synced_all_with_failures_format,
                            summary.playlistCount,
                            summary.syncedSongCount,
                            summary.failedPlaylistCount
                        )
                    }
                },
                onFailure = {
                    _syncMessage.value = context.getString(
                        R.string.dash_jellyfin_sync_failed_format,
                        it.message.orEmpty()
                    )
                }
            )
            _isSyncing.value = false
        }
    }

    fun syncPlaylists() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = context.getString(R.string.dash_jellyfin_syncing_playlists)
            val result = repository.syncPlaylists()
            result.fold(
                onSuccess = {
                    _syncMessage.value = context.getString(
                        R.string.dash_jellyfin_synced_playlists_count,
                        it.size
                    )
                },
                onFailure = {
                    _syncMessage.value = context.getString(
                        R.string.dash_jellyfin_sync_failed_format,
                        it.message.orEmpty()
                    )
                }
            )
            _isSyncing.value = false
        }
    }

    fun syncPlaylistSongs(playlistId: String) {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = context.getString(R.string.dash_jellyfin_syncing_songs)
            val result = repository.syncPlaylistSongs(playlistId)
            result.fold(
                onSuccess = { count ->
                    try {
                        repository.syncUnifiedLibrarySongsFromJellyfin()
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to sync unified library after playlist sync")
                    }
                    _syncMessage.value = context.getString(
                        R.string.dash_jellyfin_synced_songs_count,
                        count
                    )
                },
                onFailure = {
                    _syncMessage.value = context.getString(
                        R.string.dash_jellyfin_sync_failed_format,
                        it.message.orEmpty()
                    )
                }
            )
            _isSyncing.value = false
        }
    }

    fun loadPlaylistSongs(playlistId: String) {
        viewModelScope.launch {
            repository.getPlaylistSongs(playlistId).collect { songs ->
                _selectedPlaylistSongs.value = songs.toImmutableList()
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
        }
    }

    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
        }
    }
}
