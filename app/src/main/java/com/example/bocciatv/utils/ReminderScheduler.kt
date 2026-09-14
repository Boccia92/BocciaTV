package com.example.bocciatv.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.bocciatv.data.local.PrefsManager
import com.example.bocciatv.data.model.ReminderItem

object ReminderScheduler {

    fun scheduleReminder(context: Context, item: ReminderItem) {
        val prefs = PrefsManager(context)
        prefs.addReminder(item)

        val triggerTime = maxOf(System.currentTimeMillis() + 5000, item.startTimeMillis - (2 * 60 * 1000))

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("event_id", item.eventId)
            putExtra("program_title", item.programTitle)
            putExtra("channel_id", item.channelId)
            putExtra("channel_name", item.channelName)
            putExtra("stream_url", item.streamUrl)
        }

        val requestCode = item.eventId.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        } catch (e: Exception) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
    }

    fun cancelReminder(context: Context, eventId: String) {
        val prefs = PrefsManager(context)
        prefs.removeReminder(eventId)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ReminderReceiver::class.java)
        val requestCode = eventId.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun restoreAllReminders(context: Context) {
        val prefs = PrefsManager(context)
        val now = System.currentTimeMillis()
        val reminders = prefs.getReminders()
        for (item in reminders) {
            if (item.startTimeMillis > now) {
                scheduleReminder(context, item)
            } else {
                prefs.removeReminder(item.eventId)
            }
        }
    }
}
