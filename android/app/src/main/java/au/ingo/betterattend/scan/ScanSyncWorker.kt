package au.ingo.betterattend.scan

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import au.ingo.betterattend.container
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Sends scans that were saved while offline as soon as there's a network connection.
 * Scheduled whenever something is queued; retries with exponential backoff until the queue is empty.
 * `client_scan_id` makes every retry idempotent, so overlapping flushes are harmless.
 */
class ScanSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val remaining = applicationContext.container.scans.flush()
        if (remaining == 0) Result.success() else Result.retry()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val UNIQUE_NAME = "scan-sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScanSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            // Append so a flush that's already running finishes before the next one picks up new scans.
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
