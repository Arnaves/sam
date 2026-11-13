# Twin Tracker (Android)

An Android app for logging twin babies' **feeds**, **diapers (output)**, **sleep** and **vaccines** —
by voice, hands-free, in the background, or by tapping.

## Features

- **Background voice logging.** A foreground service keeps the microphone listening (with a
  persistent notification and a *Stop* action). Trigger phrases:
  - `entry for baby 1 …`
  - `entry for baby 2 …`
  - `entry for both babies …`, or the names you give the babies (`entry for Aria …`)

  Say it all at once (`entry for baby 1 fed 120 ml formula`) or say only the trigger, wait for
  "go ahead", then say the details. The app reads back what it saved.
- **Manual entry**: a + button on the log, quick-log chips on the dashboard (Feed / Wet / Dirty / Sleep),
  and tap any entry to edit or delete it.
- **Daily log with search**: move day by day or pick a date, search free text (e.g. "formula",
  "poop", "BCG", "fever"), filter by baby and type, or search across all dates.
- **Vaccine schedule** for each baby, worked out from the birth date (a default birth–18 month
  WHO/IAP-style schedule that you can edit), showing given, due-soon and overdue doses. Reminder
  notifications come twice a day. Logging a vaccine by voice marks the next pending dose.
- **Analytics dashboard**: today's numbers per baby, time since the last feed (highlighted when
  it's later than a threshold you set), 7/14/30-day charts comparing both babies (milk ml,
  feeds, diapers, sleep), daily averages and the average gap between feeds.
- **CSV export** of all logs and vaccine records.

### Voice command examples

| Say | Logged as |
|---|---|
| entry for baby 1 fed 120 ml formula | Feed · Formula · 120 ml |
| entry for baby 2 breastfed 15 minutes left side | Feed · Breast · 15 min · Left |
| entry for baby 1 bottle 4 oz | Feed · 118 ml |
| entry for baby 2 wet diaper / pooped / pee and poop | Diaper · Wet / Dirty / Wet + Dirty |
| entry for baby 1 slept 2 hours | Sleep · 2h |
| entry for baby 2 vaccine BCG and hepatitis B | Vaccine (marks both doses as given) |
| entry for both babies fed 90 ml at 3:30 pm | Two feeds, timestamped 3:30 pm |
| entry for baby 1 fed 60 ml 20 minutes ago | Feed, timestamped 20 minutes ago |
| entry for baby 1 fed 100 ml formula and pooped | A feed and a diaper |
| entry for baby 2 note slight fever | Note |

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
  open the app and tap the mic once.
- Continuous listening uses more battery than normal; keep the phone on a charger overnight.

## Tech

Kotlin · Jetpack Compose (Material 3) · Room · WorkManager · a foreground service
(`microphone` type) · `SpeechRecognizer` + `TextToSpeech`. minSdk 26, targetSdk 35.

The default vaccine schedule is general guidance only. Confirm it with your paediatrician and
edit it to match the schedule they use.
