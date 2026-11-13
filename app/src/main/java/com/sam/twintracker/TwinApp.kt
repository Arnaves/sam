package com.sam.twintracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.sam.twintracker.data.AppDatabase
import com.sam.twintracker.data.Repository
import com.sam.twintracker.work.MedicineAlarms
import com.sam.twintracker.work.VaccineReminderWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class TwinApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repository: Repository
        private set
    lateinit var medicineAlarms: MedicineAlarms
        private set

    override fun onCreate() {
        super.onCreate()
        medicineAlarms = MedicineAlarms(this)
        repository = Repository(AppDatabase.build(this)).also { it.scheduleListener = medicineAlarms }
        appScope.launch {
            repository.ensureDefaults()
            medicineAlarms.rescheduleAll(repository.allSchedules())
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTENER, "Voice listener", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the app listens for \"entry for baby 1/2\""
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Vaccine reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MEDICINE, "Medicine timers", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when a medicine dose is due"
            }
        )

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "vaccine-reminder",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<VaccineReminderWorker>(12, TimeUnit.HOURS).build(),
        )
    }

    companion object {
        const val CHANNEL_LISTENER = "listener"
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_MEDICINE = "medicine"
        const val PREFS = "settings"
        const val PREF_MUTE_BEEP = "mute_beep"
        const val PREF_TTS = "tts_confirm"
        const val PREF_OFFLINE = "prefer_offline"
        const val PREF_FEED_ALERT_HOURS = "feed_alert_hours"
        const val PREF_WAKE_WORD = "wake_word"
        /** "°F" or "°C" — display unit for temperature charts. */
        const val PREF_TEMP_UNIT = "temp_unit"
    }
}
