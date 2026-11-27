package com.fabian.player.presentation.viewmodel

import com.fabian.player.data.model.Song
import com.fabian.player.data.preferences.PlaylistPreferencesRepository
import com.fabian.player.data.repository.MusicRepository
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject

@ViewModelScoped
class SongRemovalStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
    private val metadataEditStateHolder: MetadataEditStateHolder,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
    private val libraryStateHolder: LibraryStateHolder
) {
    suspend fun deleteSongFile(song: Song): Boolean {
        return metadataEditStateHolder.deleteSong(song)
    }

    suspend fun removeSongFromLibrary(song: Song) {
        libraryStateHolder.removeSong(song.id)
        musicRepository.deleteById(song.id.toLong())
        playlistPreferencesRepository.removeSongFromAllPlaylists(song.id)
    }
}
