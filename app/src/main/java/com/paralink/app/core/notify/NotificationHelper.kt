package com.paralink.app.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {

    const val CH_MESSAGES = "paralink_messages"
    const val CH_VOICE = "paralink_voice"
    const val CH_FILES = "paralink_files"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming chat messages"
            },
            NotificationChannel(CH_VOICE, "Voice", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming voice messages"
            },
            NotificationChannel(CH_FILES, "Files", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Received files"
            }
        ))
    }

    fun notify(context: Context, channel: String, title: String, text: String, id: Int, icon: Int) {
        ensureChannels(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val intent = launch ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, android.app.Activity::class.java)
        val pending = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            nm.notify(id, NotificationCompat.Builder(context, channel)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build())
        }
    }
}