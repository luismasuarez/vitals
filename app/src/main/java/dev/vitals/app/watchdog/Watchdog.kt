package dev.vitals.app.watchdog

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.vitals.app.core.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

enum class WatchStatus { Off, Connecting, Watching, Down }

/**
 * Process-local view of the watchdog. The UI reads this flow; the service is
 * the only writer. A process death resets it to Off and WorkManager revives the
 * service (and therefore the state) shortly after.
 */
data class WatchState(
    val status: WatchStatus = WatchStatus.Off,
    val since: Long? = null,
    val lastEventAt: Long? = null,
)

object WatchdogState {
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    fun off() {
        _state.value = WatchState()
    }

    fun connecting() {
        _state.value = WatchState(
            status = WatchStatus.Connecting,
            since = System.currentTimeMillis(),
            lastEventAt = _state.value.lastEventAt,
        )
    }

    fun watching(lastEventAt: Long) {
        _state.value = WatchState(
            status = WatchStatus.Watching,
            since = null,
            lastEventAt = lastEventAt,
        )
    }

    fun down(since: Long) {
        _state.value = WatchState(
            status = WatchStatus.Down,
            since = since,
            lastEventAt = _state.value.lastEventAt,
        )
    }
}

/**
 * Enables/disables the watchdog and keeps a 15-minute WorkManager job around to
 * revive the service if the system kills it.
 */
object Watchdog {
    private const val PREFS = "vitals.watchdog"
    private const val ENABLED = "enabled"
    private const val WORK = "vitals-watchdog"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(ENABLED, false)

    fun start(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app) || SessionStore(app).load() == null) return
        schedule(app)
        runCatching {
            ContextCompat.startForegroundService(app, WatchdogService.intent(app))
        }
    }

    fun stop(context: Context, disable: Boolean = false) {
        val app = context.applicationContext
        if (disable) {
            prefs(app).edit().putBoolean(ENABLED, false).apply()
        }
        runCatching { WorkManager.getInstance(app).cancelUniqueWork(WORK) }
        WatchdogState.off()
        WatchdogNotifications.cancel(app, WatchdogNotifications.ID_ONGOING)
        WatchdogNotifications.cancel(app, WatchdogNotifications.ID_DOWN)
        WatchdogNotifications.cancel(app, WatchdogNotifications.ID_RECOVERED)
        runCatching { app.stopService(Intent(app, WatchdogService::class.java)) }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, enabled).apply()
        if (enabled) start(context) else stop(context)
    }

    private fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(
            15,
            TimeUnit.MINUTES,
        )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
