package app.berilo.reader.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.berilo.reader.BeriloApplication
import java.util.concurrent.TimeUnit

/**
 * Background sync (S3.2).
 *
 * A Boox tablet is offline most of the time and aggressively dozes, so this is scheduled as
 * *periodic* work with a network constraint rather than a timer the app controls: WorkManager
 * defers it until the device actually has connectivity and survives reboots, which is the whole
 * reason to use it instead of a coroutine on app start. The app also syncs on foreground, so a
 * user who opens the reader after connecting does not wait for the next window.
 */
class SyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val container =
            (applicationContext as? BeriloApplication)?.container ?: return Result.success()
        val report = container.syncManager.syncNow()
        return when (report.outcome) {
            // Offline and rate-limited are not failures — retry lets WorkManager apply its own
            // backoff instead of burning the next window on a request that cannot succeed yet.
            SyncOutcome.Offline -> Result.retry()
            is SyncOutcome.RateLimited -> Result.retry()
            is SyncOutcome.Failed -> Result.retry()
            // Nothing to retry *for*: without a session there is no work this job can do until
            // the user signs in, which reschedules on its own.
            SyncOutcome.SignedOut -> Result.success()
            SyncOutcome.Success -> Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "berilo-sync"
        private const val INTERVAL_MINUTES = 60L

        /**
         * Registers the periodic job, keeping any existing schedule so a re-launch does not
         * reset the interval and re-sync on every cold start.
         */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
        }

        /** Cancels background sync — used on sign-out, when there is nothing to sync to. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
