package dev.vitals.app.watchdog

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.vitals.app.core.SessionStore

/**
 * Safety net only: if the system kills the foreground service, this runs every
 * 15 minutes and starts it again. Detection while the service is alive does not
 * depend on WorkManager.
 */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        if (!Watchdog.isEnabled(context) || SessionStore(context).load() == null) {
            return Result.success()
        }
        return runCatching {
            ContextCompat.startForegroundService(context, WatchdogService.intent(context))
            Result.success()
        }.getOrElse {
            // Android 12+ can refuse a foreground-service start from the
            // background. Retry with WorkManager's backoff; opening the app is
            // the reliable path when the device blocks it.
            Result.retry()
        }
    }
}
