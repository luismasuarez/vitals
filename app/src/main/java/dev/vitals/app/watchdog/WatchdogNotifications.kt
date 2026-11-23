package dev.vitals.app.watchdog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.vitals.app.MainActivity
import dev.vitals.app.R

object WatchdogNotifications {
    const val CHANNEL_STATUS = "watchdog_status"
    const val CHANNEL_ALERTS = "watchdog_alerts"

    const val ID_ONGOING = 1001
    const val ID_DOWN = 1002
    const val ID_RECOVERED = 1003

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val status = NotificationChannel(
            CHANNEL_STATUS,
            "Vigilancia",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Servicio en primer plano que vigila el host"
            setShowBadge(false)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            "Host caído",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Avisos de host sin respuesta y de recuperación"
        }
        manager.createNotificationChannels(listOf(status, alerts))
    }

    fun ongoing(context: Context, host: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_vitals)
            .setContentTitle("vitals vigilando · $host")
            .setContentText("Te aviso si el host deja de responder")
            .setContentIntent(openApp(context))
            .addAction(0, "Dejar de vigilar", stopWatching(context))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    fun down(context: Context, host: String, seconds: Long): Notification =
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_vitals)
            .setContentTitle("$host no responde")
            .setContentText("Sin datos desde hace ${seconds}s")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Sin datos desde hace ${seconds}s. Revisa la red, Tailscale o el servicio vitals del host.",
                ),
            )
            .setContentIntent(openApp(context))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

    fun recovered(context: Context, host: String, outageSeconds: Long): Notification =
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_vitals)
            .setContentTitle("$host vuelve a responder")
            .setContentText("Caído durante ${formatDuration(outageSeconds)}")
            .setContentIntent(openApp(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setSilent(true)
            .build()

    @Suppress("MissingPermission")
    fun post(context: Context, id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopWatching(context: Context): PendingIntent {
        val intent = WatchdogService.intent(context).setAction(WatchdogService.ACTION_STOP)
        return PendingIntent.getService(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60} min"
        else -> "${seconds / 3_600} h"
    }
}
