package com.zibi.app.reminders

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.zibi.app.MainActivity
import com.zibi.app.ZibiApp
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? ZibiApp ?: return
        val container = app.container

        when (intent.action) {
            ACTION_FIRE -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Reminder"
                if (id == -1L) return

                showNotification(context, id, title)
            }
            ACTION_DONE -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                if (id != -1L) {
                    dismissNotification(context, id)
                    container.scope.launch {
                        container.db.reminderDao().setDone(id, true)
                        container.scheduler.cancel(id)
                    }
                }
            }
            ACTION_SNOOZE -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Reminder"
                if (id != -1L) {
                    dismissNotification(context, id)
                    val snoozeTime = System.currentTimeMillis() + SNOOZE_MILLIS
                    container.scheduler.scheduleAt(id, title, snoozeTime)
                }
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            ACTION_RESCHEDULE_ALL -> {
                container.scope.launch {
                    val pending = container.db.reminderDao().getPendingTimeReminders()
                    pending.forEach { reminder ->
                        container.scheduler.schedule(reminder)
                    }
                }
            }
        }
    }

    private fun showNotification(context: Context, id: Long, title: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // Tap notification -> open MainActivity
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            id.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Action: Mark Done
        val doneIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_DONE
            putExtra(EXTRA_ID, id)
        }
        val donePending = PendingIntent.getBroadcast(
            context,
            (id * 10 + 1).toInt(),
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Action: Snooze 10m
        val snoozeIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_SNOOZE
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_TITLE, title)
        }
        val snoozePending = PendingIntent.getBroadcast(
            context,
            (id * 10 + 2).toInt(),
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Reminder")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addAction(0, "Mark Done", donePending)
            .addAction(0, "Snooze 10m", snoozePending)
            .build()

        nm.notify(id.toInt(), notification)
    }

    private fun dismissNotification(context: Context, id: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(id.toInt())
    }

    companion object {
        const val ACTION_FIRE = "com.zibi.app.reminders.ACTION_FIRE"
        const val ACTION_DONE = "com.zibi.app.reminders.ACTION_DONE"
        const val ACTION_SNOOZE = "com.zibi.app.reminders.ACTION_SNOOZE"
        const val ACTION_RESCHEDULE_ALL = "com.zibi.app.reminders.ACTION_RESCHEDULE_ALL"
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_TITLE = "reminder_title"
        const val SNOOZE_MILLIS = 10 * 60 * 1000L // 10 minutes
    }
}
