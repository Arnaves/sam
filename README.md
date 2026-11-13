# Twin Tracker (Android)

An Android app for logging twin babies' **feeds**, **diapers (output)**, **sleep** and **vaccines** —
by voice, hands-free, in the background, or by tapping.

## Features

- **Background voice assistant with a wake word.** A foreground service keeps listening (with a
  persistent notification and a *Stop* action). Start every request with **"baby tracker"**
  (you can change this in Settings), then log something or ask a question. Say only the wake word,
  wait for "Yes?", then say the rest. If you don't say which baby, it asks "For which baby?".
- **Names and nicknames.** Each baby can have any number of aliases (e.g. *Aarav, Chintu, Munna*).
  "baby 1", "baby 2", any of the names, or "both babies" all work.
- **Spoken answers**, for example:
  - *which baby should I feed now?* compares how long ago each twin was fed against each twin's
    usual feeding gap, and mentions any medicine due in the next 30 minutes
  - *when did Chintu last eat?* / *last poop?* / *last medicine?* / *last temperature?*
  - *when is the next medicine?* / *next vaccine?* / *how much did Pari eat today?*

  The same questions (and entries) can be typed into the **Ask** box on the dashboard.
- **Medicine tracking and timers**: log doses (ml, drops, mg, tablets) and start timers such as
  "every 6 hours for 3 days", "twice a day" or "remind me in 4 hours". When a dose is due you get
  an alarm notification with **Given** and **Snooze** buttons, and the app says it out loud if it's
  listening. Logging a dose moves the timer on.
- **Temperature, weight and height** records, with trend charts and a fever flag
  (≥ 100.4 °F / 38 °C). Temperatures without a unit are read as °F above 45 and °C otherwise.
  Weight can be said in kg, grams or pounds and ounces; height in cm or inches.
- **Manual entry** of every type: a + button on the log, quick-log chips on the dashboard, and
  "Log dose", "Add" and "Timer" buttons on the Health tab.
- **Daily log with search**: move day by day or pick a date, search free text, filter by baby
  and type, or search across all dates.
- **Vaccine schedule** per baby based on the birth date, with reminders.
- **Analytics dashboard**: today's numbers per baby, time since the last feed, latest
  temperature, next medicine, 7/14/30-day comparison charts and daily averages.
- **CSV export** of all logs and vaccine records.

### Voice examples

Each starts with "baby tracker, …":

| Say | Result |
|---|---|
| Chintu fed 120 ml formula | Feed · Formula · 120 ml |
| fed 100 ml for Pari | Feed for baby 2 |
| Chintu and Pari fed 90 ml at 3:30 pm | Two feeds at 3:30 pm |
| Munna pooped / wet diaper / pee and poop | Diaper · Dirty / Wet / Wet + Dirty |
| Pari breastfed 15 minutes left side | Feed · Breast · 15 min · Left |
| Chintu slept 2 hours | Sleep · 2h |
| Chintu paracetamol 2.5 ml every 6 hours for 3 days | Dose logged + 6-hourly timer for 3 days |
| Pari iron drops 5 drops twice a day | Dose logged + 12-hourly timer |
| set a medicine timer for Pari in 4 hours | One-off reminder |
| Chintu temperature 100.2 | Temperature 100.2 °F (fever) |
| Pari weight 4 kg 250 grams · height 55 cm | Weight 4.25 kg · Height 55 cm |
| Chintu vaccine BCG | Marks BCG as given |
| which baby should I feed now? | Spoken answer |
| when did Chintu last eat? | Spoken answer |
| when is the next medicine? | Spoken answer |

## Build

Requirements: Android Studio (Ladybug or newer) or the Android SDK with JDK 17.

```bash
./gradlew testDebugUnitTest   # voice parser unit tests
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Every push also runs the **Android build** GitHub Actions workflow, which uploads the debug APK
as a downloadable artifact (`twin-tracker-debug-apk`).

## Notes on background listening

- It uses Android's built-in `SpeechRecognizer` (Google speech services on most phones) in a
  continuous loop. Turn on *Prefer offline recognition* in Settings if you've downloaded the
  offline language pack.
- Recognizers play a beep every time listening restarts. *Silence listening beep* (on by default)
  mutes media volume while the mic is listening and turns it back on for spoken confirmations.
- To keep it running with the screen off, set the app's battery usage to **Unrestricted**
  (Settings → Battery optimisation settings in the app).
- Android 14+ only lets a microphone service start while the app is open, so after a reboot,
  open the app and tap the mic once. Medicine alarms are restored automatically after a reboot.
- On Android 13+, allow **exact alarms** (the Health tab shows a button for this) so medicine
  reminders go off on time.
- Continuous listening uses more battery than normal; keep the phone on a charger overnight.

## Tech

Kotlin · Jetpack Compose (Material 3) · Room · WorkManager · a foreground service
(`microphone` type) · `SpeechRecognizer` + `TextToSpeech`. minSdk 26, targetSdk 35.

The default vaccine schedule is general guidance only. Confirm it with your paediatrician and
edit it to match the schedule they use.
