# BetterAttend for iOS

A native SwiftUI port of the Android app in [`../android`](../android). It talks to the same
backend (`https://attend.hackclub.com/api/v1`) and signs in with the same Hack Club OAuth client
(`attend://oauth/callback`), so any existing Attend account works.

Requirements: Xcode 26+ (iOS 26 SDK), deployment target iOS 18. No third-party dependencies.

```sh
cd ios
xcodebuild -project Attend.xcodeproj -scheme Attend \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -derivedDataPath build/DerivedData test
```

## Demo mode

Launch with `-AttendDemo YES` (also a disabled launch argument in the Attend scheme) to run the
real app against `DemoBackend`, an in-process, stateful fake of the Attend API: a live event with
143 registrations, travel, announcements and tickets. Scans check people in, undo works, notes
and announcements are stored. Profile edits, invites (an `@banned.example` address is refused),
removals and event staff changes are stored too; the staff list includes a global admin and a
series-inherited admin that can't be removed. It starts fresh on every launch.
`-AttendDemoOffline YES` simulates no network (scans queue up).

`-AttendOpenURL <deep link>` opens a screen at launch, so any screen can be screenshotted:

```sh
scripts/shot.sh "iPhone 17 Pro" /tmp/people.png attend://people dark
```

Deep links: `attend://home|scan|people|travel|tickets`, `attend://ticket/<id>`,
`attend://participant/<eventId>/<participantEventId>`, `attend://blasts`, `attend://kiosk`,
`attend://settings`, `attend://events`. In demo data the event id is
`0f5d1a64-2a0e-4d0c-8a3b-1e4c9a7b3c21` (`DemoData.mainEventId`). Participant event ids are
`String(format: "%08x-8d7e-4f60-9a1b-2c3d4e5f6a7b", 0x3b1f_9000 + i * 104_729)` for generated person `i`
(e.g. `3b1f9000-…` is Sam Lee, checked in; `3b245b4b-8d7e-4f60-9a1b-2c3d4e5f6a7b` is `i = 3`, Leo Nguyen,
anaphylaxis alert). The organizer's own registration is `3b1f9a2c-8d7e-4f60-9a1b-2c3d4e5f6a7b`.

## Layout

| Folder | Target(s) | What |
|---|---|---|
| `Shared/` | app + widgets | Wire models, pure logic (ported 1:1 from Kotlin, unit tested), widget snapshot, theme colours |
| `Attend/Data/` | app | `AttendAPI`, auth, Keychain / encrypted cache / settings, repositories, `AppModel` container |
| `Attend/Demo/` | app | Demo backend + data |
| `Attend/UI/` | app | SwiftUI screens, one folder per feature |
| `AttendWidgets/` | widget extension | WidgetKit widgets + controls; read the snapshot the app writes to the App Group |
| `AttendTests/` | unit tests | Swift Testing; `Fixtures.swift` is the port of Android's `SampleData` |
| `AttendUITests/` | UI tests | XCUITest against demo mode |
| `Config/` | — | Info.plists and entitlements |

The project uses **synchronized folders**: any file added under those folders is compiled into the
right target automatically. Never hand-edit `project.pbxproj` for new files.

## Conventions

- **Concurrency**: Swift 6 language mode. Models in `Shared/` are plain `Sendable` structs. App-side
  state holders are `@MainActor @Observable final class`; views read them directly.
- **Dependencies** come from the environment: `@Environment(AppModel.self) var app` (repositories:
  `app.events`, `app.participants`, `app.scans`, `app.tickets`, `app.travel`, `app.settings`,
  `app.auth`, `app.api`) and `@Environment(Router.self) var router` (navigation).
- **Navigation**: `TabView` + one `NavigationStack` per tab. Push with `router.open(.participant(…))`
  etc. Settings and the event picker are sheets (`router.sheet`), kiosk is a full-screen cover
  (`router.kiosk`). Organizer tab roots use `.eventToolbar(fallbackTitle:)`: the event name is the
  title (tap for the event switcher) with the account button top-right.
- **Feel native**: system components first (`List`, `Form`, `.searchable`, `.refreshable`,
  `.swipeActions`, `.contextMenu`, `ContentUnavailableView`, `.confirmationDialog`, sheets with
  detents, `ShareLink`, SF Symbols, Dynamic Type, `.contentTransition(.numericText())`). Liquid Glass
  comes free with stock bars and controls on iOS 26.
- **Colour**: the accent is Hack Club red. Semantic status colours are `Tone.success/warning/info/danger`
  (`.color`, `.container`, `.onContainer`). `Pill`, `Avatar`, `ProgressRing`, `NoticeBanner` live in
  `UI/Components`.
- **Haptics** are gated on the user's setting: use `Haptics.tap()/selection()/confirm()/reject()`, or
  `.sensoryFeedback(…, trigger:) { _, _ in Haptics.enabled }`. Scan outcomes use `ScanFeedbackPlayer`.
- **Errors**: `error.friendlyMessage`, `error.isTransient`, `error.isCancellation` (don't show an
  error for cancellation).
- **Polling** while visible: `.poll(every: .seconds(30), id: eventId) { … }`.
