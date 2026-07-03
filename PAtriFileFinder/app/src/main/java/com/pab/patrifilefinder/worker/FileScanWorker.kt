package com.pab.patrifilefinder.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pab.patrifilefinder.data.scanner.FileScanner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

@HiltWorker
class FileScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val fileScanner: FileScanner,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "doWork: start (attempt ${runAttemptCount + 1})")
        return try {
            fileScanner.scan()
            Log.i(TAG, "doWork: success")
            Result.success()
        } catch (e: CancellationException) {
            // The worker was stopped (OS reclaim / cancel). Let it propagate so
            // WorkManager treats it as a stop, not a failure. Progress is already
            // saved per-batch and the embedding pass is resumable, so the next run
            // continues where this one left off.
            Log.i(TAG, "doWork: stopped/cancelled — will resume next run")
            throw e
        } catch (e: Exception) {
            // Retry up to WorkManager's default retry limit (3 attempts with backoff)
            Log.e(TAG, "doWork: failed, scheduling retry", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "FileScanWorker"
        const val PERIODIC_WORK_NAME = "FileScanWorker"
        const val MANUAL_WORK_NAME = "ManualScan"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<FileScanWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // don't reset timer if already scheduled
                request,
            )
        }

        fun enqueueNow(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<FileScanWorker>()
                .addTag("manual_scan")
                .build()

            // KEEP, not REPLACE: if a scan is already running, let it finish rather
            // than cancelling it (which throws WorkerStoppedException and can prevent
            // indexing from ever completing when the button is tapped repeatedly).
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK_NAME,
                androidx.work.ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
