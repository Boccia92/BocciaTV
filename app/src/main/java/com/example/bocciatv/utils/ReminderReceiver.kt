package com.example.bocciatv.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import com.example.bocciatv.R
import com.example.bocciatv.ui.player.PlayerActivity

@UnstableApi
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra("event_id") ?: return
        val title = intent.getStringExtra("program_title") ?: "Programma TV"
        val channelId = intent.getStringExtra("channel_id") ?: ""
        val channelName = intent.getStringExtra("channel_name") ?: "Canale TV"
        val streamUrl = intent.getStringExtra("stream_url") ?: ""

        val channelNotice = "Sta per iniziare: $title su $channelName"

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val channelIdStr = "bocciatv_reminders"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelIdStr,
                "Promemoria Programmi TV",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifiche per l'inizio dei programmi TV preferiti"
            }
            notificationManager?.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("url", streamUrl)
            putExtra("id", channelId)
            putExtra("name", channelName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            eventId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelIdStr)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle("🔔 Promemoria TV")
            .setContentText(channelNotice)
            .setStyle(NotificationCompat.BigTextStyle().bigText(channelNotice))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            notificationManager?.notify(eventId.hashCode(), notification)
        } catch (_: Exception) {}
    }
}
