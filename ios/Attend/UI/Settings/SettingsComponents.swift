import SwiftUI

/// The account card at the top of Settings: avatar, name, email and role badges.
struct SettingsAccountHeader: View {
    let user: User?

    var body: some View {
        Section {
            HStack(spacing: 16) {
                Avatar(name: user?.displayName ?? "?", size: 64)
                VStack(alignment: .leading, spacing: 4) {
                    Text(user?.displayName ?? "Signed In")
                        .font(.title3.weight(.semibold))
                        .lineLimit(1)
                    if let user {
                        Text(user.email)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .textSelection(.enabled)
                    }
                    if !badges.isEmpty {
                        HStack(spacing: 6) {
                            ForEach(badges, id: \.text) { Pill(text: $0.text, tone: $0.tone, systemImage: $0.icon) }
                        }
                        .padding(.top, 4)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 6)
            .accessibilityElement(children: .combine)
        }
    }

    private var badges: [(text: String, tone: Tone, icon: String)] {
        guard let user else { return [] }
        var out: [(String, Tone, String)] = []
        if user.globalAdmin { out.append(("Global admin", .danger, "shield.lefthalf.filled")) }
        if user.isOrganizer || user.globalAdmin { out.append(("Organizer", .info, "person.badge.key.fill")) }
        if user.isParticipant { out.append(("Participant", .success, "ticket.fill")) }
        return out
    }
}

/// A white SF Symbol on a coloured rounded square, like the Settings app.
struct SettingsIcon: View {
    let systemImage: String
    let color: Color

    @ScaledMetric(relativeTo: .body) private var size: CGFloat = 29

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.55, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(color.gradient, in: .rect(cornerRadius: size * 0.24, style: .continuous))
            .accessibilityHidden(true)
    }
}

/// Title, optional secondary line and a Settings-style icon.
struct SettingsLabel: View {
    let title: String
    var subtitle: String?
    var subtitleTone: Tone?
    let systemImage: String
    let color: Color

    init(_ title: String, subtitle: String? = nil, subtitleTone: Tone? = nil, systemImage: String, color: Color) {
        self.title = title
        self.subtitle = subtitle
        self.subtitleTone = subtitleTone
        self.systemImage = systemImage
        self.color = color
    }

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if let subtitle {
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(subtitleTone.map { AnyShapeStyle($0.color) } ?? AnyShapeStyle(.secondary))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        } icon: {
            SettingsIcon(systemImage: systemImage, color: color)
        }
    }
}

/// A row that opens a URL (Safari, or the phone app for `tel:`).
struct SettingsLinkRow: View {
    let title: String
    let subtitle: String
    let systemImage: String
    let color: Color
    let url: URL
    var external = true

    var body: some View {
        Link(destination: url) {
            HStack {
                SettingsLabel(title, subtitle: subtitle, systemImage: systemImage, color: color)
                    .foregroundStyle(.primary)
                Spacer()
                if external {
                    Image(systemName: "arrow.up.forward")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                }
            }
        }
        .simultaneousGesture(TapGesture().onEnded { Haptics.tap() })
        .accessibilityHint(external ? "Opens in Safari" : "")
    }
}

/// Every event the organizer can work on, grouped like the event picker. Selecting one pops back.
struct SettingsEventList: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            ForEach(EventPicker.sections(app.events.events ?? []), id: \.title) { section in
                Section(section.title) {
                    ForEach(section.events) { e in
                        Button {
                            Haptics.selection()
                            app.events.select(e.id)
                            dismiss()
                        } label: {
                            HStack(spacing: 12) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(e.name).foregroundStyle(.primary)
                                    Text([EventPicker.subtitle(e), EventLogic.roleLabel(e.role)].compactMap { $0?.nonBlank }.joined(separator: " · "))
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if e.id == app.events.selectedEvent?.id {
                                    Image(systemName: "checkmark")
                                        .font(.body.weight(.semibold))
                                        .foregroundStyle(.tint)
                                        .accessibilityLabel("Selected")
                                }
                            }
                            .contentShape(.rect)
                        }
                    }
                }
            }
        }
        .navigationTitle("Event")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { _ = try? await app.events.refresh() }
    }
}
