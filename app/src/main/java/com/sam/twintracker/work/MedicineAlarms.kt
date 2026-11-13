package com.sam.twintracker.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sam.twintracker.R
import com.sam.twintracker.TwinApp
import com.sam.twintracker.data.MedicineSchedule
import com.sam.twintracker.data.ScheduleListener
import com.sam.twintracker.ui.MainActivity
import com.sam.twintracker.voice.VoiceListenerService
import kotlinx.coroutines.launch

/** Schedules one alarm per active medicine timer. */
class MedicineAlarms(private val context: Context) : ScheduleListener {

    private val am = context.getSystemService(AlarmManager::class.java)

    override fun onScheduleChanged(schedule: MedicineSchedule) {
        val due = schedule.nextDueAt
        if (!schedule.active || due == null) onScheduleRemoved(schedule.id) else setAlarm(schedule.id, due)
    }

    override fun onScheduleRemoved(id: Long) {
        am.cancel(pending(id))
        NotificationManagerCompat.from(context).cancel(notificationId(id))
    }

    fun rescheduleAll(list: List<MedicineSchedule>) = list.forEach { onScheduleChanged(it) }

    fun setAlarm(id: Long, at: Long) {
        val pi = pending(id)
        val time = at.coerceAtLeast(System.currentTimeMillis() + 1_000)
        if (canExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
    }

    fun canExact() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    private fun pending(id: Long) = PendingIntent.getBroadcast(
        context, id.toInt(),
        Intent(context, MedicineAlarmReceiver::class.java).setAction(MedicineAlarmReceiver.ACTION_DUE).putExtra(MedicineAlarmReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        fun notificationId(id: Long) = 10_000 + id.toInt()
    }
}

class MedicineAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DUE = "com.sam.twintracker.MEDICINE_DUE"
        const val ACTION_GIVEN = "com.sam.twintracker.MEDICINE_GIVEN"
        const val ACTION_SNOOZE = "com.sam.twintracker.MEDICINE_SNOOZE"
        const val EXTRA_ID = "schedule_id"
        private const val SNOOZE_MIN = 10
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TwinApp
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return
        val pending = goAsync()
        app.appScope.launch {
            try {
                val repo = app.repository
                when (intent.action) {
                    ACTION_DUE -> {
                        val s = repo.schedule(id) ?: return@launch
                        if (!s.active) return@launch
                        val name = repo.babyList().firstOrNull { it.id == s.baby }?.name ?: "Baby ${s.baby}"
                        val text = "Time for $name's ${s.name}" + (s.dose?.let { ", $it" } ?: "")
                        notify(context, s, text)
                        VoiceListenerService.announce("$text.")
                    }
                    ACTION_GIVEN -> {
                        val s = repo.giveScheduledDose(id)
                        NotificationManagerCompat.from(context).cancel(MedicineAlarms.notificationId(id))
                        s?.takeIf { it.active }?.nextDueAt?.let { app.medicineAlarms.setAlarm(id, it) }
                    }
                    ACTION_SNOOZE -> {
                        NotificationManagerCompat.from(context).cancel(MedicineAlarms.notificationId(id))
                        app.medicineAlarms.setAlarm(id, System.currentTimeMillis() + SNOOZE_MIN * 60_000L)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun notify(context: Context, s: MedicineSchedule, text: String) {
        fun action(action: String, req: Int) = PendingIntent.getBroadcast(
            context, req, Intent(context, MedicineAlarmReceiver::class.java).setAction(action).putExtra(EXTRA_ID, s.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_HEALTH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, TwinApp.CHANNEL_MEDICINE)
            .setSmallIcon(R.drawable.ic_stat_medicine)
            .setContentTitle("Medicine due")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Given", action(ACTION_GIVEN, 100_000 + s.id.toInt()))
            .addAction(0, "Snooze $SNOOZE_MIN min", action(ACTION_SNOOZE, 200_000 + s.id.toInt()))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(MedicineAlarms.notificationId(s.id), n)
        } catch (_: SecurityException) {
        }
    }
}

/** Alarms are cleared on reboot; put them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != "android.intent.action.MY_PACKAGE_REPLACED") return
        val app = context.applicationContext as TwinApp
        val pending = goAsync()
        app.appScope.launch {
            try { app.medicineAlarms.rescheduleAll(app.repository.allSchedules()) } finally { pending.finish() }
        }
    }
}
