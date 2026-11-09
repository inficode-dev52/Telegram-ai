package com.aditya.ai.svc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aditya.ai.AdityaApp
import com.aditya.ai.MainActivity
import com.aditya.ai.R
import com.aditya.ai.data.Reminder

/** Inexact alarms only (no exact-alarm permission needed). A reminder exists only after the user confirmed it. */
object Reminders {
    const val CHANNEL = "reminders"
    private const val ACTION = "com.aditya.ai.REMINDER"

    private fun pending(ctx: Context, id: Long) = PendingIntent.getBroadcast(ctx, (id and 0x7fffffff).toInt(),
        Intent(ctx, ReminderReceiver::class.java).setAction(ACTION).putExtra("rid", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Exact delivery when Android grants the special access; otherwise keep a resilient inexact fallback. */
    fun schedule(ctx: Context, r: Reminder): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        val pi = pending(ctx, r.id)
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.whenMs, pi)
                true
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.whenMs, pi)
                false
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.whenMs, pi)
            false
        }
    }
    fun cancel(ctx: Context, id: Long) { ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx, id)) }
    fun notificationsAllowed(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as? AdityaApp ?: return
        val id = intent.getLongExtra("rid", 0L)
        val r = app.v2.reminders.value.firstOrNull { it.id == id } ?: return
        val nm = NotificationManagerCompat.from(ctx)
        if (nm.areNotificationsEnabled()) {
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
            // Respect the existing "show message previews" privacy setting: no task text on the lock screen when previews are off.
            val text = if (app.store.bool("preview", true)) r.title else "You have a task reminder"
            val n = NotificationCompat.Builder(ctx, Reminders.CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("ADITYA AI reminder")
                .setContentText(text).setAutoCancel(true).setContentIntent(open).build()
            try { nm.notify((id and 0x7fffffff).toInt(), n) } catch (_: SecurityException) {}
        }
        app.v2.removeReminder(id)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = ctx.applicationContext as? AdityaApp ?: return
        val now = System.currentTimeMillis()
        // Alarms are cleared on reboot: restore every confirmed reminder; overdue ones are delivered shortly (late rather than lost).
        app.v2.reminders.value.forEach { Reminders.schedule(ctx, if (it.whenMs > now) it else it.copy(whenMs = now + 10_000L)) }
    }
}
