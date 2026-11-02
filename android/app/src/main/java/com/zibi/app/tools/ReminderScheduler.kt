package com.zibi.app.tools

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.zibi.app.MainActivity
import com.zibi.app.data.Reminder
import com.zibi.app.data.ReminderDao
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

internal fun reminderTriggerAtMillis(reminder: Reminder, zone: ZoneId = ZoneId.systemDefault()): Long? {
    return try {
        val date = reminder.dueDate?.let(LocalDate::parse) ?: return null
        val time = reminder.time?.let(LocalTime::parse) ?: return null
        LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
    } catch (_: RuntimeException) {
        null
    }
}

class ReminderScheduler(
    private val context: Context,
    private val dao: ReminderDao,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(reminder: Reminder): Boolean {
        val triggerAt = reminderTriggerAtMillis(reminder) ?: return false
        if (triggerAt <= System.currentTimeMillis()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) return false
        return try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                alarmPendingIntent(reminder.id, reminder.title),
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancel(reminderId: Long) {
        val intent = alarmIntent(reminderId)
        val pending = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    suspend fun reschedulePending() {
        dao.pendingTimed().forEach(::schedule)
    }

    private fun alarmPendingIntent(id: Long, title: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id.toInt(),
            alarmIntent(id).putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alarmIntent(id: Long) = Intent(context, ReminderAlarmReceiver::class.java)
        .setAction(ACTION_REMIND)
        .setData(Uri.parse("zibi://reminder/$id"))

    companion object {
        private const val CHANNEL_ID = "reminders"
        private const val ACTION_REMIND = "com.zibi.app.action.REMIND"
        private const val EXTRA_TITLE = "reminder_title"
        private const val EXTRA_ID = "reminder_id"

        internal fun showNotification(context: Context, reminderId: Long, title: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return

            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val openApp = PendingIntent.getActivity(
                context,
                reminderId.toInt(),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
            }
            manager.notify(
                reminderId.toInt(),
                builder
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Zibi reminder")
                    .setContentText(title)
                    .setContentIntent(openApp)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .build(),
            )
        }
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("reminder_id", -1L)
        val title = intent.getStringExtra("reminder_title") ?: return
        if (id >= 0) ReminderScheduler.showNotification(context, id, title)
    }
}

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val app = context.applicationContext as com.zibi.app.ZibiApp
        app.container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                app.container.reminderScheduler.reschedulePending()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
