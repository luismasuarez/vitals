package dev.vitals.app.watchdog

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import dev.vitals.app.core.Session
import dev.vitals.app.core.SessionStore
import dev.vitals.app.core.VitalsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

/**
 * Foreground watchdog. Holds one SSE connection, treats 30 s without an event
 * as "host down" (two 15 s keepalives missed) and notifies on each transition
 * once. The notification is the price of reliable detection on Android.
 */
class WatchdogService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var api: VitalsApi? = null
    private var stream: EventSource? = null
    private var watcher: Job? = null
    private var reconnectJob: Job? = null

    @Volatile private var endpoint = 0
    @Volatile private var attempts = 0
    @Volatile private var stopped = false
    @Volatile private var down = false
    @Volatile private var downSinceElapsed = 0L
    @Volatile private var lastEventElapsed = 0L
    @Volatile private var lastEventWall = 0L
    @Volatile private var startedElapsed = 0L
    @Volatile private var hostName = "host"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WatchdogNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Watchdog.stop(this, disable = true)
            WatchdogNotifications.cancel(this, WatchdogNotifications.ID_ONGOING)
            stopSelf()
            return START_NOT_STICKY
        }

        val session = SessionStore(this).load()
        hostName = session?.name ?: "host"
        startForeground()

        if (session == null || !Watchdog.isEnabled(this)) {
            WatchdogNotifications.cancel(this, WatchdogNotifications.ID_ONGOING)
            stopSelf()
            return START_NOT_STICKY
        }

        if (watcher == null) {
            stopped = false
            startedElapsed = SystemClock.elapsedRealtime()
            Log.i(TAG, "watchdog started for $hostName")
            startWatcher()
            connect(session)
        }
        return START_STICKY
    }

    private fun startForeground() {
        val notification = WatchdogNotifications.ongoing(this, hostName)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            WatchdogNotifications.ID_ONGOING,
            notification,
            type,
        )
    }

    /* -------------------------------- connection ---------------------------- */

    private fun connect(current: Session) {
        if (stopped) return
        stream?.cancel()
        val client = api?.takeIf { it.session == current } ?: VitalsApi(current).also { api = it }
        if (current.endpoints.isEmpty()) {
            scheduleReconnect()
            return
        }
        endpoint = endpoint.coerceIn(0, current.endpoints.lastIndex)
        client.selectEndpoint(endpoint)
        if (!down) WatchdogState.connecting()

        stream = client.openStreamAt(endpoint, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                attempts = 0
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                markAlive()
            }

            override fun onClosed(eventSource: EventSource) {
                scheduleReconnect()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                scheduleReconnect()
            }
        })
    }

    private fun markAlive() {
        if (stopped) return
        attempts = 0
        lastEventElapsed = SystemClock.elapsedRealtime()
        lastEventWall = System.currentTimeMillis()

        if (down) {
            down = false
            val outage = ((lastEventElapsed - downSinceElapsed) / 1000).coerceAtLeast(0)
            Log.i(TAG, "host recovered after ${outage}s")
            WatchdogNotifications.cancel(this, WatchdogNotifications.ID_DOWN)
            WatchdogNotifications.post(
                this,
                WatchdogNotifications.ID_RECOVERED,
                WatchdogNotifications.recovered(this, hostName, outage),
            )
        }
        WatchdogState.watching(lastEventWall)
    }

    private fun scheduleReconnect() {
        if (stopped) return
        stream?.cancel()
        stream = null
        reconnectJob?.cancel()
        attempts++
        val count = SessionStore(this).load()?.endpoints?.size ?: 0
        if (count > 1) endpoint = (endpoint + 1) % count
        val wait = minOf(1_500L * attempts, 8_000L)
        reconnectJob = scope.launch {
            delay(wait)
            val current = SessionStore(this@WatchdogService).load()
            if (current == null) {
                stopSelf()
            } else {
                connect(current)
            }
        }
    }

    /* --------------------------------- watcher ------------------------------ */

    private fun startWatcher() {
        watcher = scope.launch {
            while (isActive) {
                delay(CHECK_EVERY_MS)
                val reference = if (lastEventElapsed == 0L) startedElapsed else lastEventElapsed
                val age = SystemClock.elapsedRealtime() - reference
                if (!down && age >= DOWN_AFTER_MS) {
                    down = true
                    downSinceElapsed = SystemClock.elapsedRealtime() - age
                    Log.w(TAG, "host down after ${age / 1000}s without events")
                    WatchdogState.down(System.currentTimeMillis() - age)
                    WatchdogNotifications.post(
                        this@WatchdogService,
                        WatchdogNotifications.ID_DOWN,
                        WatchdogNotifications.down(this@WatchdogService, hostName, age / 1000),
                    )
                }
            }
        }
    }

    /* -------------------------------- lifecycle ----------------------------- */

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        stopped = true
        reconnectJob?.cancel()
        stream?.cancel()
        stream = null
        watcher?.cancel()
        watcher = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        scope.cancel()
        WatchdogState.off()
    }

    companion object {
        const val ACTION_STOP = "dev.vitals.app.watchdog.STOP"

        private const val TAG = "VitalsWatchdog"
        private const val DOWN_AFTER_MS = 30_000L
        private const val CHECK_EVERY_MS = 5_000L

        fun intent(context: Context): Intent =
            Intent(context, WatchdogService::class.java)
    }
}
