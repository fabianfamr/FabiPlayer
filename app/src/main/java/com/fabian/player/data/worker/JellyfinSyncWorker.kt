package com.fabian.player.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fabian.player.data.jellyfin.JellyfinRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

@HiltWorker
class JellyfinSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: JellyfinRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("JellyfinSyncWorker: Starting full sync")

        return try {
            val result = repository.syncAllPlaylistsAndSongs()
            val syncFailure = result.exceptionOrNull()
            if (syncFailure != null) {
                Timber.e(syncFailure, "JellyfinSyncWorker: Sync returned failure (attempt=%d)", runAttemptCount)
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
            Timber.e(e, "JellyfinSyncWorker: Sync failed")
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(workDataOf(ERROR_MESSAGE to e.message))
            }
        }
    }

    companion object {
        const val WORK_NAME_ALL = "jellyfin_sync_all"
        const val ERROR_MESSAGE = "error_message"

        private const val MAX_RETRY_ATTEMPTS = 3

        fun startAllSync() = OneTimeWorkRequestBuilder<JellyfinSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
    }
}
