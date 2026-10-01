# Attend for Android and iOS — a better Hack Club Attend app

Native rewrites of Hack Club's [Attend](https://attend.hackclub.com) mobile app
([Play Store](https://play.google.com/store/apps/details?id=com.hackclub.attend)):

- **[`android/`](android)** — Kotlin, Jetpack Compose and **Material 3 Expressive**.
- **[`ios/`](ios)** — Swift and SwiftUI (iOS 18+, Liquid Glass on iOS 26), with WidgetKit widgets,
  a Control Center control and Siri / Spotlight shortcuts. Same features, built the iOS way; see
  [`ios/README.md`](ios/README.md).

Both talk to the same backend ([hackclub/attend](https://github.com/hackclub/attend)) and sign in
with the same Hack Club OAuth client as the official app, so any existing Attend account works.
They install **alongside** the official app (`au.ingo.betterattend`).

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

## iOS

Open `ios/Attend.xcodeproj` in Xcode 26+ and run the **Attend** scheme, or from the command line:

```sh
cd ios
xcodebuild -project Attend.xcodeproj -scheme Attend \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' test   # unit + UI tests
```

Each [release](https://github.com/ingoau/better-attend/releases/latest) includes an unsigned
`.ipa` you can sideload with [AltStore](https://altstore.io) or [SideStore](https://sidestore.io).
With a free Apple ID it needs refreshing every 7 days, and widgets and NFC may not work.

Launch with `-AttendDemo YES` to try every screen against a built-in fake event (no account
needed). Running on a device needs your Apple developer team set in Signing & Capabilities (App
Groups for widgets, NFC Tag Reading for badges). iOS equivalents of the Android extras: Apple Wallet
passes, Home Screen and Lock Screen widgets, a Scan Tickets control, Home Screen quick actions,
Siri / Spotlight shortcuts and Guided Access for kiosk mode. NFC reading on iOS uses the system
scan sheet (tap "Scan NFC Badge"), as iOS doesn't allow always-on background tag reading.

## Android: Install

Download an APK from the [latest release](https://github.com/ingoau/better-attend/releases/latest) —
`Attend-<version>-arm64-v8a.apk` fits almost every phone, `Attend-<version>-universal.apk` runs
everywhere — then open it on your phone and allow installing from that source. Android 8.0+.
Builds of unreleased changes are under **Actions → Android → attend-apk**.

## Android: Build

Requirements: JDK 17+ and the Android SDK (platform 37, build-tools 37).

```sh
cd android
./gradlew :app:assembleRelease        # → app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest      # unit, screenshot (Roborazzi) and end-to-end smoke tests
```

Screenshot tests render every screen in light and dark mode on the JVM into
`android/app/build/outputs/roborazzi/`. `AppSmokeTest` boots the real app against a mock Attend API and
walks every tab, including a real check-in.

### Signing

Release builds are signed with your key if one is configured, otherwise with the debug key (so the
APK always installs). Configure either via environment variables

```
ATTEND_KEYSTORE_FILE, ATTEND_KEYSTORE_PASSWORD, ATTEND_KEY_ALIAS, ATTEND_KEY_PASSWORD
```

or a gitignored `keystore.properties` in `android/` (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`). In CI, set the `ATTEND_KEYSTORE_BASE64` secret (plus the three others) to publish
updates signed with a stable key. The release workflow requires them.

## Releasing

Push a version tag and GitHub builds both apps and publishes them as a release:

```sh
git tag v1.3.0 && git push origin v1.3.0
```

The tag sets the version name, and the version code is derived from it (`v1.3.0` → `10300`), so
there's nothing to bump by hand.

## How sign-in works

Authorization Code + PKCE against `auth.hackclub.com` using the official app's OAuth client and
its registered redirect `attend://oauth/callback`, opened in an **Auth Tab** (falls back to a Custom
Tab; on iOS an `ASWebAuthenticationSession`). The code is exchanged by the Attend backend (`POST /api/v1/session`), which returns a 14-day
mobile token that the app rotates automatically. Your account must already exist in Attend.

## Known limitations

- **Push notifications** aren't supported: the Attend backend only accepts Expo push tokens.
- **Live updates** use polling; Attend's ActionCable channel only accepts browser cookies.
- If the official app is also installed, Android may ask which app should finish signing in
  on older browsers without Auth Tab support — pick this one.

## Layout

```
android/app/src/main/java/au/ingo/betterattend/
  data/api      Attend API client + models        data/repo   events, roster sync, scans + offline queue, tickets, travel
  data/auth     Hack Club OAuth (PKCE)             data/store  encrypted cache, settings
  ui/           Compose screens (dashboard, scan, people, travel, blasts, tickets, settings, login)
  widget/       Glance widgets + background sync   scan/       scan sync worker
ios/
  Shared/       models + pure logic (shared with widgets)   Attend/Data   API, auth, stores, repositories
  Attend/UI/    SwiftUI screens                              AttendWidgets/ WidgetKit widgets + control
```
