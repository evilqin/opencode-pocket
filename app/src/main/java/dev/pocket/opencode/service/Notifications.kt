package dev.pocket.opencode.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.pocket.opencode.MainActivity
import dev.pocket.opencode.R

object Notifications {

    const val CHANNEL_SERVER = "server"
    const val ID_SERVER = 1001

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val server = NotificationChannel(
            CHANNEL_SERVER,
            "服务运行状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示内置 OpenCode 服务的运行状态"
            setShowBadge(false)
        }
        manager.createNotificationChannel(server)
    }

    fun serverNotification(
        context: Context,
        title: String,
        text: String,
        running: Boolean,
        showStop: Boolean = running,
    ): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, ServerService::class.java).setAction(ServerService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_SERVER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(running)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (showStop) {
            builder.addAction(0, "停止", stopIntent)
        }
        return builder.build()
    }
}
