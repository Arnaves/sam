package com.sam.twintracker.voice

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sam.twintracker.R
import com.sam.twintracker.TwinApp
import com.sam.twintracker.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service that keeps the microphone open and listens for
 * "entry for baby 1" / "entry for baby 2" (or "entry for both babies").
 *
 * Android's [SpeechRecognizer] stops after each utterance, so this service restarts it in a loop.
 * The recognizer must be driven from the main thread, which is why everything here runs on the
 * main looper.
 */
class VoiceListenerService : LifecycleService() {

    data class State(
        val running: Boolean = false,
        val listening: Boolean = false,
        val lastHeard: String = "",
        val lastSaved: String = "",
        /** Wake word heard (or "which baby?" asked) — listening for the follow-up. */
        val awaiting: Boolean = false,
        val error: String? = null,
    )

    companion object {
        private const val TAG = "VoiceListener"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "com.sam.twintracker.STOP_LISTENING"
        private const val ARM_TIMEOUT_MS = 10_000L

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VoiceListenerService::class.java))
        }

        @Volatile
        private var instance: VoiceListenerService? = null

        /** Speak [text] if the listener is running (used by medicine alarms). */
        fun announce(text: String) {
            Handler(Looper.getMainLooper()).post { instance?.announceInternal(text) }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, VoiceListenerService::class.java).setAction(ACTION_STOP))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var stopped = false
    private var armedUntil = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var mutedStream = false
    private var consecutiveErrors = 0

    private val prefs by lazy { getSharedPreferences(TwinApp.PREFS, MODE_PRIVATE) }
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val assistant by lazy { Assistant((application as TwinApp).repository, prefs) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        handler.post { onSpeechDone() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handler.post { onSpeechDone() }
                    }
                })
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = State(error = "Microphone permission not granted")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification("Say \"${assistant.wakeWord}\" then your entry or question"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            // Android 14+ refuses microphone services started from the background (e.g. after a restart).
            Log.w(TAG, "Cannot start foreground", e)
            _state.value = State(error = "Open the app to start voice listening")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            _state.value = State(error = "Speech recognition is not available on this device")
            shutdown()
            return START_NOT_STICKY
        }
        if (!_state.value.running) {
            stopped = false
            acquireWakeLock()
            _state.value = State(running = true)
            createRecognizer()
            startListening()
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------- recognition loop

    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, prefs.getBoolean(TwinApp.PREF_OFFLINE, false))
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        // Allow a natural pause between trigger and command.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
    }

    private fun startListening() {
        if (stopped || speaking) return
        if (prefs.getBoolean(TwinApp.PREF_MUTE_BEEP, true)) muteBeep(true)
        try {
            recognizer?.startListening(recognizerIntent())
            _state.value = _state.value.copy(listening = true, error = null)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            scheduleRestart(1000, recreate = true)
        }
    }

    private fun scheduleRestart(delayMs: Long, recreate: Boolean = false) {
        if (stopped) return
        handler.removeCallbacks(restartRunnable)
        if (recreate) handler.postDelayed({ if (!stopped) createRecognizer() }, delayMs / 2)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private val restartRunnable = Runnable { startListening() }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            _state.value = _state.value.copy(listening = false)
            val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            handleUtterance(alternatives)
            if (!speaking) scheduleRestart(150)
        }

        override fun onError(error: Int) {
            _state.value = _state.value.copy(listening = false)
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    consecutiveErrors = 0
                    scheduleRestart(150)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    _state.value = _state.value.copy(error = "Microphone permission missing")
                    shutdown()
                }
                else -> {
                    // busy / client / network / server errors: back off and rebuild the recognizer.
                    consecutiveErrors++
                    val delay = (500L * consecutiveErrors).coerceAtMost(10_000L)
                    if (consecutiveErrors > 3) {
                        _state.value = _state.value.copy(error = "Recognizer error $error, retrying…")
                    }
                    scheduleRestart(delay, recreate = true)
                }
            }
        }
    }

    // ---------------------------------------------------------------- command handling

    private enum class Awaiting { NOTHING, REQUEST, BABY }

    private var awaiting = Awaiting.NOTHING
    private var pendingCommand: String? = null

    private fun handleUtterance(alternatives: List<String>) {
        if (alternatives.isEmpty()) return
        val now = System.currentTimeMillis()
        val armed = awaiting != Awaiting.NOTHING && now <= armedUntil
        if (!armed) awaiting = Awaiting.NOTHING
        val mode = awaiting
        val pending = pendingCommand
        lifecycleScope.launch {
            val reply = when (mode) {
                Awaiting.NOTHING -> assistant.onUtterance(alternatives, now) ?: run {
                    _state.value = _state.value.copy(lastHeard = alternatives.first())
                    return@launch
                }
                Awaiting.REQUEST -> assistant.onRequest(alternatives.first(), now)
                Awaiting.BABY -> assistant.onBabyAnswer(alternatives.first(), pending.orEmpty(), now)
            }
            _state.value = _state.value.copy(lastHeard = alternatives.first())
            when {
                reply.awaitingRequest -> { awaiting = Awaiting.REQUEST; pendingCommand = null }
                reply.awaitingBabyFor != null -> { awaiting = Awaiting.BABY; pendingCommand = reply.awaitingBabyFor }
                else -> { awaiting = Awaiting.NOTHING; pendingCommand = null }
            }
            armedUntil = System.currentTimeMillis() + ARM_TIMEOUT_MS
            _state.value = _state.value.copy(lastSaved = reply.text, awaiting = awaiting != Awaiting.NOTHING)
            updateNotification(if (awaiting != Awaiting.NOTHING) reply.text else "Last: ${reply.text}")
            say(reply.text)
        }
    }

    /** Spoken alert from outside (e.g. a medicine timer firing). */
    private fun announceInternal(text: String) {
        updateNotification(text)
        say(text)
    }

    // ---------------------------------------------------------------- TTS + beep handling

    private fun say(text: String) {
        if (!prefs.getBoolean(TwinApp.PREF_TTS, true) || !ttsReady) return
        speaking = true
        handler.removeCallbacks(restartRunnable)
        recognizer?.cancel()
        muteBeep(false)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "twin-${System.nanoTime()}")
        // Safety net in case the engine never reports completion.
        handler.postDelayed({ if (speaking) onSpeechDone() }, 8_000)
    }

    private fun onSpeechDone() {
        if (!speaking) return
        speaking = false
        if (awaiting != Awaiting.NOTHING) armedUntil = System.currentTimeMillis() + ARM_TIMEOUT_MS
        scheduleRestart(250)
    }

    /**
     * Most recognizers play a short tone each time listening restarts, which is every few seconds
     * in a continuous loop. Muting the music stream while listening suppresses it.
     */
    private fun muteBeep(mute: Boolean) {
        if (mute == mutedStream) return
        try {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0,
            )
            mutedStream = mute
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot change volume (Do Not Disturb?)", e)
        }
    }

    // ---------------------------------------------------------------- notification & lifecycle

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceListenerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, TwinApp.CHANNEL_LISTENER)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Twin Tracker is listening")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop listening", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        if (stopped) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TwinTracker:voice").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun shutdown() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        muteBeep(false)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        _state.value = _state.value.copy(running = false, listening = false, awaiting = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (!stopped) shutdown()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
