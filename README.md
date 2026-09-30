# Attend for Android — a better Hack Club Attend app

A native Android rewrite of Hack Club's [Attend](https://attend.hackclub.com) mobile app
([Play Store](https://play.google.com/store/apps/details?id=com.hackclub.attend)), built with
Kotlin, Jetpack Compose and **Material 3 Expressive**. It talks to the same backend
([hackclub/attend](https://github.com/hackclub/attend)) and signs in with the same Hack Club
OAuth client as the official app, so any existing Attend account works.

It installs **alongside** the official app (package `au.ingo.betterattend`).

## What's in it

**For organizers**
- **Home dashboard** — live "84 / 120 checked in" with an expressive progress ring, not-here-yet and
  last-hour counts, per scan-point progress, arrivals to collect, people needing attention, recent
  check-ins and one-tap actions (Scan · Find · Announce · Kiosk).
- **Scan** — continuous QR scanning (CameraX + on-device ML Kit, works offline), **NFC badge reading**
  (the old app had no Android NFC), scan-point selector, big colour-coded results with safety alerts,
  undo, sounds + haptics, recent-scans log, and "Find person" for manual check-ins.
- **Offline first** — scans made without signal are queued, shown as "N waiting to sync", and
  retried automatically (WorkManager) with idempotent `client_scan_id`s. Rosters, tickets and travel
  are cached (encrypted with an Android Keystore key) so everything still reads offline.
- **People** — instant search (name, email, pronouns, short code), filter chips with live counts
  (Here / Not here / Needs attention / Not complete / Withdrawn), advanced filters and sorting.
- **Participant detail** — safety alerts first, check in / undo per scan point, call · SMS ·
  WhatsApp · email, travel legs with live flight status, medical/accessibility/safeguarding
  (role-gated), guardians, consents, notes with type + sensitivity, and **NFC badge writing**
  (ensure → write → verify → confirm).
- **Travel** — arrivals/departures by day in the event timezone, pickup states, UM flags.
- **Announcements** — send Slack blasts with confirmation and live delivery progress.
- **Kiosk mode** — self check-in on a spare phone/tablet: front camera, pinned screen, PIN to exit.

**For participants**
- **My tickets** — works offline at the door; pass with a high-contrast QR that **boosts screen
  brightness** automatically, full-screen QR, Google Wallet, countdown, directions, arriving
  travel, organiser messages and safety contacts.

**Home-screen widgets** (Jetpack Glance, Material You, light + dark, resizable)
- **Check-in progress** — count, progress bar, not here yet; larger sizes add per-scan-point rows.
- **Arrivals** — awaiting pickup / collected / checked in + next arrival.
- **Quick scan** — one tap straight into the scanner.
- **My ticket** — next event countdown, opens your pass.

Widgets refresh in the background every 15 minutes (and instantly when the app has new data),
staying well inside Attend's shared 300 requests / 5 min per-IP rate limit.

Also: dark mode everywhere (System / Light / Dark), optional wallpaper colours, adaptive layout with
a navigation rail on tablets, app shortcuts, and no analytics or session recording.

## Install

Grab the APK from the latest CI run (**Actions → Build APK → attend-apk**) or build it yourself,
then open it on your phone and allow installing from that source. Android 8.0+.

## Build

Requirements: JDK 17+ and the Android SDK (platform 37, build-tools 37).

```sh
./gradlew :app:assembleRelease        # → app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest      # unit, screenshot (Roborazzi) and end-to-end smoke tests
```

Screenshot tests render every screen in light and dark mode on the JVM into
`app/build/outputs/roborazzi/`. `AppSmokeTest` boots the real app against a mock Attend API and
walks every tab, including a real check-in.

### Signing

Release builds are signed with your key if one is configured, otherwise with the debug key (so the
APK always installs). Configure either via environment variables

```
ATTEND_KEYSTORE_FILE, ATTEND_KEYSTORE_PASSWORD, ATTEND_KEY_ALIAS, ATTEND_KEY_PASSWORD
```

or a gitignored `keystore.properties` in the repo root (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`). In CI, set the `ATTEND_KEYSTORE_BASE64` secret (plus the three others) to publish
updates signed with a stable key.

## How sign-in works

Authorization Code + PKCE against `auth.hackclub.com` using the official app's OAuth client and
its registered redirect `attend://oauth/callback`, opened in an **Auth Tab** (falls back to a Custom
Tab). The code is exchanged by the Attend backend (`POST /api/v1/session`), which returns a 14-day
mobile token that the app rotates automatically. Your account must already exist in Attend.

## Known limitations

- **Push notifications** aren't supported: the Attend backend only accepts Expo push tokens.
- **Live updates** use polling; Attend's ActionCable channel only accepts browser cookies.
- If the official app is also installed, Android may ask which app should finish signing in
  on older browsers without Auth Tab support — pick this one.

## Layout

```
app/src/main/java/au/ingo/betterattend/
  data/api      Attend API client + models        data/repo   events, roster sync, scans + offline queue, tickets, travel
  data/auth     Hack Club OAuth (PKCE)             data/store  encrypted cache, settings
  ui/           Compose screens (dashboard, scan, people, travel, blasts, tickets, settings, login)
  widget/       Glance widgets + background sync   scan/       scan sync worker
```
