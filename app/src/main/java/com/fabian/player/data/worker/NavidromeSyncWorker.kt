package com.fabian.player.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fabian.player.data.navidrome.NavidromeRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

@HiltWorker
class NavidromeSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: NavidromeRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val syncType = inputData.getString(KEY_SYNC_TYPE) ?: SYNC_TYPE_ALL
        val playlistId = inputData.getString(KEY_PLAYLIST_ID)

        Timber.d("NavidromeSyncWorker: Starting sync (type=$syncType, playlistId=$playlistId)")

        return try {
            val syncFailure: Throwable? = when (syncType) {
                SYNC_TYPE_ALL -> {
                    repository.syncAllPlaylistsAndSongs { progress, message ->
                        setProgressAsync(
                            workDataOf(
                                PROGRESS_VALUE to progress,
                                PROGRESS_MESSAGE to message
                            )
                        )
                    }.exceptionOrNull()
                }
                SYNC_TYPE_PLAYLISTS -> {
                    repository.syncPlaylists().exceptionOrNull()
                }
                SYNC_TYPE_PLAYLIST_SONGS -> {
                    if (playlistId != null) {
                        val songsResult = repository.syncPlaylistSongs(playlistId)
                        if (songsResult.isSuccess) {
                            repository.syncUnifiedLibrarySongsFromNavidrome()
                        }
                        songsResult.exceptionOrNull()
                    } else {
                        return Result.failure(workDataOf(ERROR_MESSAGE to "Missing playlistId"))
                    }
                }
                else -> null
            }

            if (syncFailure != null) {
                Timber.e(syncFailure, "NavidromeSyncWorker: Sync returned failure (attempt=%d)", runAttemptCount)
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure(workDataOf(ERROR_MESSAGE to syncFailure.message))
                }
            } else {
                Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "NavidromeSyncWorker: Sync failed")
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(workDataOf(ERROR_MESSAGE to e.message))
            }
        }
    }

    companion object {
        const val WORK_NAME_ALL = "navidrome_sync_all"

        const val KEY_SYNC_TYPE = "sync_type"
        const val KEY_PLAYLIST_ID = "playlist_id"
        
        const val SYNC_TYPE_ALL = "all"
        const val SYNC_TYPE_PLAYLISTS = "playlists"
        const val SYNC_TYPE_PLAYLIST_SONGS = "playlist_songs"

        const val PROGRESS_VALUE = "progress_value"
        const val PROGRESS_MESSAGE = "progress_message"
        const val ERROR_MESSAGE = "error_message"

        private const val MAX_RETRY_ATTEMPTS = 3

        private fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun startAllSync() = OneTimeWorkRequestBuilder<NavidromeSyncWorker>()
            .setConstraints(networkConstraints())
            .setInputData(workDataOf(KEY_SYNC_TYPE to SYNC_TYPE_ALL))
            .build()
            
        fun startPlaylistSync(playlistId: String) = OneTimeWorkRequestBuilder<NavidromeSyncWorker>()
            .setConstraints(networkConstraints())
            .setInputData(
                workDataOf(
                    KEY_SYNC_TYPE to SYNC_TYPE_PLAYLIST_SONGS,
                    KEY_PLAYLIST_ID to playlistId
                )
            )
            .build()
    }
}
