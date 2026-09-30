import SwiftUI

// MARK: - Avatar

/// "Maya Chen" → "MC".
func initials(_ name: String) -> String {
    let parts = name.split(whereSeparator: { $0 == " " || $0 == "-" }).prefix(2)
    let letters = parts.compactMap(\.first).map { String($0).uppercased() }.joined()
    return letters.isEmpty ? "?" : letters
}

/// A headshot, or initials on a colour picked stably from the name.
struct Avatar: View {
    let name: String
    var url: String?
    var size: CGFloat = 44

    private static let palette: [Color] = [HackClub.red, HackClub.orange, HackClub.green, HackClub.cyan, HackClub.blue, HackClub.purple]

    private var tint: Color {
        let seed = name.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0xffff }
        return Self.palette[seed % Self.palette.count]
    }

    var body: some View {
        ZStack {
            Circle().fill(tint.opacity(0.18))
            Text(initials(name))
                .font(.system(size: size * 0.38, weight: .semibold, design: .rounded))
                .foregroundStyle(tint)
            if let url, let u = URL(string: url) {
                AsyncImage(url: u) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Color.clear
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }
}

// MARK: - Pill

/// A small capsule label: "Live", "Checked in", "Withdrawn"…
struct Pill: View {
    let text: String
    var tone: Tone = .neutral
    var systemImage: String?

    var body: some View {
        HStack(spacing: 4) {
            if let systemImage { Image(systemName: systemImage).imageScale(.small) }
            Text(text).lineLimit(1)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(tone.onContainer)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(tone.container, in: .capsule)
    }
}

// MARK: - Banners

/// Inline notice for offline / stale data, with an optional retry.
struct NoticeBanner: View {
    let message: String
    var systemImage = "wifi.slash"
    var tone: Tone = .neutral
    var retry: (() -> Void)?

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage).foregroundStyle(tone == .neutral ? .secondary : tone.color)
            Text(message).font(.subheadline).foregroundStyle(tone == .neutral ? .secondary : tone.onContainer)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let retry {
                Button("Retry") { Haptics.tap(); retry() }
                    .font(.subheadline.weight(.semibold))
            }
        }
        .padding(12)
        .background(tone.container, in: .rect(cornerRadius: 14))
    }
}

// MARK: - Progress ring

/// A circular progress indicator with a rounded cap, animating between values.
struct ProgressRing: View {
    var progress: Double
    var lineWidth: CGFloat = 12
    var tint: Color = .accentColor

    var body: some View {
        ZStack {
            Circle().stroke(tint.opacity(0.18), lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: max(0.001, min(progress, 1)))
                .stroke(tint, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .animation(.spring(duration: 0.8, bounce: 0.2), value: progress)
    }
}

// MARK: - Toolbar

/// The account button in the top-right of every tab root: opens Settings.
struct AccountButton: View {
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router

    var body: some View {
        Button {
            Haptics.tap()
            router.sheet = .settings
        } label: {
            Avatar(name: app.user?.displayName ?? "?", size: 30)
        }
        .accessibilityLabel("Account and settings")
    }
}

extension View {
    /// Tab-root toolbar for organizer screens: the selected event's name as the title (tap to switch
    /// events) and the account button.
    func eventToolbar(fallbackTitle: String) -> some View {
        modifier(EventToolbar(fallbackTitle: fallbackTitle))
    }
}

private struct EventToolbar: ViewModifier {
    let fallbackTitle: String
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router

    func body(content: Content) -> some View {
        let events = app.events.events ?? []
        let selected = app.events.selectedEvent
        content
            .navigationTitle(selected?.name ?? fallbackTitle)
            .toolbarTitleMenu {
                if events.count > 1 {
                    ForEach(EventPicker.ordered(events)) { e in
                        Button {
                            Haptics.selection()
                            app.events.select(e.id)
                        } label: {
                            if e.id == selected?.id {
                                Label(e.name, systemImage: "checkmark")
                            } else {
                                Text(e.name)
                            }
                            Text(EventPicker.subtitle(e))
                        }
                    }
                    Divider()
                }
                Button("All Events…", systemImage: "calendar") { router.sheet = .eventPicker }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { AccountButton() }
            }
    }
}

// MARK: - Polling

extension View {
    /// Runs `action` now and then every `interval` while this view is on screen and the app is
    /// active. Restarts when `id` changes. Cancelled as soon as the view disappears.
    func poll<ID: Equatable>(every interval: Duration, id: ID, runImmediately: Bool = true, _ action: @escaping () async -> Void) -> some View {
        modifier(PollModifier(interval: interval, id: id, runImmediately: runImmediately, action: action))
    }
}

private struct PollModifier<ID: Equatable>: ViewModifier {
    let interval: Duration
    let id: ID
    let runImmediately: Bool
    let action: () async -> Void
    @Environment(\.scenePhase) private var scenePhase

    private struct Key: Equatable {
        var id: ID
        var active: Bool
    }

    func body(content: Content) -> some View {
        content.task(id: Key(id: id, active: scenePhase == .active)) {
            guard scenePhase == .active else { return }
            if !runImmediately { try? await Task.sleep(for: interval) }
            while !Task.isCancelled {
                await action()
                try? await Task.sleep(for: interval)
            }
        }
    }
}
