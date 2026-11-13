package com.sam.twintracker.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sam.twintracker.R
import com.sam.twintracker.TwinApp
import com.sam.twintracker.data.Repository
import com.sam.twintracker.ui.MainActivity

/** Twice a day: notify about vaccine doses that are due within 2 days or overdue. */
class VaccineReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as TwinApp).repository
        val babies = repo.babyList().associateBy { it.id }
        val today = Repository.startOfDay(System.currentTimeMillis())
        val soon = Repository.addDays(today, 3)

        val due = repo.allDoses().filter { it.givenAt == null }.mapNotNull { dose ->
            val dob = babies[dose.baby]?.birthDate ?: return@mapNotNull null
            val dueAt = Repository.addDays(dob, dose.dueAgeDays)
            if (dueAt < soon) dose to dueAt else null
        }
        if (due.isEmpty()) return Result.success()
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return Result.success()

        val lines = due.groupBy { it.first.baby }.map { (baby, list) ->
            val name = babies[baby]?.name ?: "Baby $baby"
            val overdue = list.count { it.second < today }
            "$name: " + list.joinToString(", ") { it.first.name } + if (overdue > 0) " ($overdue overdue)" else ""
        }
        val open = PendingIntent.getActivity(
            applicationContext, 2,
            Intent(applicationContext, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 2),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(applicationContext, TwinApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Vaccines due")
            .setContentText(lines.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(7, n)
        } catch (_: SecurityException) {
        }
        return Result.success()
    }
}
