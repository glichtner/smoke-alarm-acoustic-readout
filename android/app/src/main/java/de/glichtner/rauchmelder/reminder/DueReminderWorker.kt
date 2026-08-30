package de.glichtner.rauchmelder.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.glichtner.rauchmelder.MainActivity
import de.glichtner.rauchmelder.data.AppDatabase
import de.glichtner.rauchmelder.ui.DueStatus
import de.glichtner.rauchmelder.ui.dueInfo
import de.glichtner.rauchmelder.ui.formatGerman
import java.util.concurrent.TimeUnit

/**
 * Daily background check: posts a notification when detectors are due for
 * their annual inspection within the next 30 days or are overdue. The check
 * runs even when the app has not been opened for months, which is the whole
 * point of a yearly reminder.
 */
class DueReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entries = AppDatabase.get(applicationContext).detectorDao().getAllWithLastInspection()
        val due = entries.map { it to dueInfo(it.lastInspection) }
            .filter { (_, info) -> info.status == DueStatus.OVERDUE || info.status == DueStatus.DUE_SOON }
        val manager = NotificationManagerCompat.from(applicationContext)
        if (due.isEmpty()) {
            manager.cancel(NOTIFICATION_ID)
            return Result.success()
        }
        val overdue = due.count { it.second.status == DueStatus.OVERDUE }
        val title = when {
            overdue > 0 && due.size == overdue -> "$overdue Rauchmelder überfällig zur Prüfung"
            overdue > 0 -> "${due.size} Rauchmelder zur Prüfung fällig ($overdue überfällig)"
            else -> "${due.size} Rauchmelder in Kürze zur Prüfung fällig"
        }
        val lines = due.take(6).map { (entry, info) ->
            "${entry.detector.apartment} · ${entry.detector.room}: " +
                if (info.status == DueStatus.OVERDUE) "überfällig seit ${info.nextDue!!.formatGerman()}"
                else "fällig bis ${info.nextDue!!.formatGerman()}"
        }
        postNotification(title, lines, due.size)
        return Result.success()
    }

    private fun postNotification(title: String, lines: List<String>, count: Int) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val style = NotificationCompat.InboxStyle()
        lines.forEach { style.addLine(it) }
        if (count > lines.size) style.setSummaryText("und ${count - lines.size} weitere")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "")
            .setStyle(style)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "due-reminders"
        private const val NOTIFICATION_ID = 1
        private const val WORK_NAME = "due-check"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Prüferinnerungen", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Erinnerung an die jährliche Rauchmelderprüfung"
                    },
                )
            }
        }

        /** Registers the daily check (idempotent; keeps an existing schedule). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DueReminderWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
