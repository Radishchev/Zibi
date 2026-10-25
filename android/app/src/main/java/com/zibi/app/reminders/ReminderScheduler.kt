package com.zibi.app.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zibi.app.data.Reminder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ReminderScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    init {
        createNotificationChannel()
    }

    fun schedule(reminder: Reminder) {
        if (reminder.done || reminder.triggerType != "at_time") return
        val dueDate = reminder.dueDate ?: return
        val date = runCatching { LocalDate.parse(dueDate) }.getOrNull() ?: return
        val time = reminder.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(9, 0)

        val triggerMs = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        scheduleAt(reminder.id, reminder.title, triggerMs)
    }

    fun scheduleAt(reminderId: Long, title: String, triggerMs: Long) {
        if (triggerMs <= System.currentTimeMillis()) return

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            putExtra(ReminderReceiver.EXTRA_ID, reminderId)
            putExtra(ReminderReceiver.EXTRA_TITLE, title)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        alarmManager?.let { am ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pending)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerMs, pending)
            }
        }
    }

    fun cancel(reminderId: Long) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
        }
        val pending = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) {
            alarmManager?.cancel(pending)
            pending.cancel()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zibi Reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Due-time notifications for Zibi reminders"
                enableVibration(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "zibi_reminders"
    }
}
