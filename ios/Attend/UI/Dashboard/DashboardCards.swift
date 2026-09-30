import SwiftUI

// MARK: - Hero

/// "84 / 120 checked in" with the progress ring (or registrations before the event starts).
struct HomeHeroCard: View {
    let event: Event
    let stats: EventStats
    let phase: Time.Phase
    let now: Date

    @Environment(\.dynamicTypeSize) private var typeSize
    @ScaledMetric(relativeTo: .largeTitle) private var numberSize: CGFloat = 56
    @ScaledMetric(relativeTo: .largeTitle) private var ringSize: CGFloat = 112

    private struct Fact: Hashable {
        var icon: String
        var value: Int?
        var prefix = ""
        var text: String
    }

    private var upcoming: Bool { phase == .upcoming }
    private var value: Int { upcoming ? stats.confirmed : DashboardLogic.checkedInConfirmed(stats) }
    private var total: Int { upcoming ? stats.registered : stats.expected }
    private var caption: String { upcoming ? "registrations complete" : "checked in" }
    private var fraction: Double { total <= 0 ? 0 : min(max(Double(value) / Double(total), 0), 1) }

    private var eyebrow: (text: String, icon: String) {
        switch phase {
        case .upcoming: (DashboardLogic.countdown(event.startsAt, tz: event.timezone, now: now) ?? "Upcoming", "calendar.badge.clock")
        case .past: (DashboardLogic.ended(event.endsAt, tz: event.timezone, now: now) ?? "Final numbers", "flag.checkered")
        default: ("Check-in", "person.crop.circle.badge.checkmark")
        }
    }

    private var facts: [Fact] {
        if upcoming {
            var f: [Fact] = []
            if let doors = Time.dayTime(event.startsAt, tz: event.timezone, now: now) {
                f.append(Fact(icon: "door.left.hand.open", text: "Doors \(doors)"))
            }
            let onboarding = DashboardLogic.notComplete(stats)
            if onboarding > 0 { f.append(Fact(icon: "hourglass", value: onboarding, text: "still onboarding")) }
            return f
        }
        var f = [stats.notArrived == 0
            ? Fact(icon: "party.popper", text: "Everyone's here")
            : Fact(icon: "person.fill.questionmark", value: stats.notArrived, text: "not here yet")]
        if phase != .past {
            f.append(Fact(icon: "chart.line.uptrend.xyaxis", value: stats.checkedInLastHour, prefix: "+", text: "in the last hour"))
        }
        return f
    }

    var body: some View {
        let percent = Int(fraction * 100)
        let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 16)) : AnyLayout(HStackLayout(spacing: 16))
        VStack(alignment: .leading, spacing: 16) {
            layout {
                VStack(alignment: .leading, spacing: 2) {
                    Label(eyebrow.text, systemImage: eyebrow.icon)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.tint)
                        .padding(.bottom, 2)
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        DashNumber(value: value)
                            .font(.stat(numberSize, weight: .heavy))
                        Text("/ \(total.formatted())")
                            .font(.title2.weight(.semibold))
                            .monospacedDigit()
                            .foregroundStyle(.secondary)
                            .contentTransition(.numericText(value: Double(total)))
                            .animation(.snappy, value: total)
                    }
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    Text(caption)
                        .font(.headline)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                ZStack {
                    ProgressRing(progress: fraction, lineWidth: 13)
                    DashNumber(value: percent, suffix: "%")
                        .font(.stat(ringSize * 0.22, weight: .heavy))
                }
                .frame(width: ringSize, height: ringSize)
                .padding(4)
            }

            if !facts.isEmpty {
                Divider()
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 20) { factViews }
                    VStack(alignment: .leading, spacing: 10) { factViews }
                }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: 26, style: .continuous)
                .fill(Color(.secondarySystemGroupedBackground))
                .overlay {
                    RoundedRectangle(cornerRadius: 26, style: .continuous)
                        .fill(LinearGradient(colors: [Color.accentColor.opacity(0.16), Color.accentColor.opacity(0.02)],
                                             startPoint: .topLeading, endPoint: .bottomTrailing))
                }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(eyebrow.text). \(value) of \(total) \(caption), \(percent) percent.")
        .accessibilityValue(facts.map { f in [f.value.map { "\(f.prefix)\($0)" }, f.text].compactMap { $0 }.joined(separator: " ") }.joined(separator: ". "))
    }

    @ViewBuilder private var factViews: some View {
        ForEach(facts, id: \.self) { f in
            HStack(spacing: 6) {
                Image(systemName: f.icon)
                    .foregroundStyle(.tint)
                    .frame(minWidth: 20)
                if let v = f.value {
                    DashNumber(value: v, prefix: f.prefix)
                        .fontWeight(.bold)
                }
                Text(f.text).foregroundStyle(.secondary)
            }
            .font(.subheadline)
            .lineLimit(1)
        }
    }
}

/// Placeholder hero while the first roster sync runs (or after it failed).
struct HomeHeroLoadingCard: View {
    let failed: Bool
    var title = "Loading participants…"
    var detail = "The first sync downloads the whole roster, so it can take a moment."
    let retry: () -> Void

    var body: some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 6) {
                Text(failed ? "Couldn't Load Numbers" : title)
                    .font(.title3.weight(.semibold))
                Text(failed ? "They'll appear once we can reach Attend." : detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if failed {
                    Button("Try Again") {
                        Haptics.tap()
                        retry()
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 6)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if !failed {
                ProgressView().controlSize(.large)
            } else {
                Image(systemName: "icloud.slash")
                    .font(.system(size: 36))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(18)
        .frame(minHeight: 150)
        .dashCardBackground()
    }
}

// MARK: - Quick actions

struct HomeQuickActions: View {
    let showFind: Bool
    let event: Event
    @Environment(Router.self) private var router
    @Environment(\.dynamicTypeSize) private var typeSize

    private struct Action: Identifiable {
        var id: String { title }
        var title: String
        var icon: String
        var primary = false
        var perform: () -> Void
    }

    private var actions: [Action] {
        var a = [Action(title: "Scan", icon: "qrcode.viewfinder", primary: true) { router.switchTab(.scan) }]
        if showFind { a.append(Action(title: "Find", icon: "magnifyingglass") { router.switchTab(.people) }) }
        a.append(Action(title: "Announce", icon: "megaphone") { router.open(.blasts(eventId: event.id)) })
        a.append(Action(title: "Kiosk", icon: "ipad.and.iphone") { router.kiosk = KioskConfig(eventId: event.id, scanContextId: nil) })
        return a
    }

    var body: some View {
        let columns = typeSize >= .xxxLarge ? 2 : actions.count
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: columns), spacing: 10) {
            ForEach(actions) { a in
                let button = Button {
                    Haptics.tap()
                    a.perform()
                } label: {
                    VStack(spacing: 6) {
                        Image(systemName: a.icon)
                            .font(.title2)
                            .frame(height: 28)
                        Text(a.title)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .frame(maxWidth: .infinity, minHeight: 60)
                }
                .buttonBorderShape(.roundedRectangle(radius: 18))
                if a.primary {
                    button.buttonStyle(.borderedProminent)
                } else {
                    button.buttonStyle(.bordered)
                }
            }
        }
    }
}

// MARK: - Stat tiles

struct HomeStatTiles: View {
    let stats: EventStats
    let open: () -> Void

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
            tile("Registered", stats.registered, "person.3", "Not withdrawn")
            tile("Confirmed", stats.confirmed, "checkmark.seal", "Registration complete")
            tile("Not Complete", DashboardLogic.notComplete(stats), "hourglass", "Still onboarding")
            tile("Withdrawn", stats.withdrawn, "person.slash", "Incl. rejected")
        }
    }

    private func tile(_ label: String, _ value: Int, _ icon: String, _ hint: String) -> some View {
        Button {
            Haptics.tap()
            open()
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Label(label, systemImage: icon)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
                    .labelStyle(TintedIconLabelStyle())
                    .lineLimit(1)
                DashNumber(value: value)
                    .font(.stat(30))
                    .foregroundStyle(.primary)
                Text(hint)
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .dashCardBackground()
        }
        .buttonStyle(DashCardButtonStyle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label): \(value). \(hint)")
        .accessibilityHint("Opens People")
        .accessibilityAddTraits(.isButton)
    }
}

private struct TintedIconLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 6) {
            configuration.icon.foregroundStyle(.tint)
            configuration.title
        }
    }
}

// MARK: - Needs attention

struct HomeAttentionCard: View {
    let stats: EventStats
    let count: Int
    let open: () -> Void

    private var detail: String {
        [stats.anaphylaxis > 0 ? "\(stats.anaphylaxis) anaphylaxis risk" : nil,
         stats.highSupport > 0 ? "\(stats.highSupport) high support" : nil].compactMap { $0 }.joined(separator: " · ")
    }

    var body: some View {
        Button {
            Haptics.tap()
            open()
        } label: {
            HStack(spacing: 14) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.title3)
                    .foregroundStyle(Tone.danger.onColor)
                    .frame(width: 44, height: 44)
                    .background(Tone.danger.color, in: .circle)
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(count) Need Attention").font(.headline)
                    Text(detail).font(.subheadline).opacity(0.85)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.forward")
                    .font(.subheadline.weight(.semibold))
                    .opacity(0.6)
            }
            .foregroundStyle(Tone.danger.onContainer)
            .padding(16)
            .dashCardBackground(Tone.danger.container)
        }
        .buttonStyle(DashCardButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens People")
    }
}

// MARK: - Scan points

struct HomeContextsCard: View {
    let rows: [ContextProgress]

    var body: some View {
        DashCard(title: "Scan Points") {
            VStack(spacing: 14) {
                ForEach(rows) { row in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 10) {
                            Image(systemName: DashboardIcons.context(row.context))
                                .foregroundStyle(row.active ? Tone.success.color : .secondary)
                                .frame(width: 24)
                            Text(row.context.name)
                                .font(.subheadline.weight(row.active ? .semibold : .regular))
                                .lineLimit(1)
                            if row.active { Pill(text: "Now", tone: .success) }
                            Spacer(minLength: 8)
                            HStack(alignment: .firstTextBaseline, spacing: 2) {
                                DashNumber(value: row.count).font(.subheadline.weight(.semibold))
                                Text("/ \(row.total)").font(.footnote).foregroundStyle(.secondary).monospacedDigit()
                            }
                        }
                        Gauge(value: row.fraction) { EmptyView() }
                            .gaugeStyle(.linearCapacity)
                            .tint(row.active ? Tone.success.color : Color.accentColor)
                            .animation(.smooth(duration: 0.8), value: row.fraction)
                    }
                    .padding(row.active ? 10 : 0)
                    .background {
                        if row.active {
                            RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tone.success.container.opacity(0.45))
                        }
                    }
                    .padding(row.active ? -10 : 0)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(row.context.name)\(row.active ? ", happening now" : "")")
                    .accessibilityValue("\(row.count) of \(row.total)")
                }
            }
        }
    }
}

// MARK: - Arrivals

struct HomeArrivalsCard: View {
    let summary: ArrivalsSummary
    let tz: String?
    /// nil when the Travel tab isn't available.
    let onOpen: (() -> Void)?

    var body: some View {
        DashCard(title: "Arrivals", actionTitle: onOpen == nil ? nil : "Travel", action: onOpen) {
            HStack(spacing: 8) {
                mini("To collect", summary.awaitingPickup, .warning)
                mini("Picked up", summary.collected, .success)
                mini("Checked in", summary.checkedIn, .info)
            }
            if !summary.next.isEmpty {
                Text("Next to Collect")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
                    .padding(.top, 4)
                VStack(spacing: 0) {
                    ForEach(Array(summary.next.enumerated()), id: \.element.id) { i, e in
                        if i > 0 { Divider().padding(.leading, 104) }
                        row(e)
                    }
                }
            }
        }
        .contentShape(.rect)
        .onTapGesture {
            guard let onOpen else { return }
            Haptics.tap()
            onOpen()
        }
    }

    private func mini(_ label: String, _ value: Int, _ tone: Tone) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            DashNumber(value: value).font(.stat(24))
            Text(label).font(.caption.weight(.medium)).lineLimit(1).minimumScaleFactor(0.8)
        }
        .foregroundStyle(tone.onContainer)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tone.container, in: .rect(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    private func row(_ e: TravelEntry) -> some View {
        HStack(spacing: 10) {
            Text(Time.time(e.primaryTimeAt, tz: tz) ?? "—")
                .font(.subheadline.weight(.semibold))
                .monospacedDigit()
                .frame(width: 70, alignment: .leading)
            Image(systemName: DashboardIcons.travelMode(e.mode, direction: e.direction))
                .foregroundStyle(.secondary)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 6) {
                    Text(e.name).font(.body).lineLimit(1)
                    if e.isUnaccompaniedMinor { Pill(text: "UM", tone: .warning) }
                }
                let sub = [e.route, e.reference].compactMap { $0?.nonBlank }.joined(separator: " · ")
                if !sub.isEmpty {
                    Text(sub).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Recent check-ins

struct HomeRecentCheckInsCard: View {
    let recent: [Participant]
    let now: Date
    let open: (Participant) -> Void
    let onSeeAll: () -> Void

    var body: some View {
        DashCard(title: "Recently Checked In", actionTitle: "People", action: onSeeAll) {
            if recent.isEmpty {
                Text("No one's checked in yet. Scans at a check-in point show up here.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(recent.enumerated()), id: \.element.id) { i, p in
                        VStack(spacing: 0) {
                            if i > 0 { Divider().padding(.leading, 52) }
                            row(p)
                        }
                        .transition(.asymmetric(insertion: .push(from: .top), removal: .opacity))
                    }
                }
                .animation(.smooth, value: recent.map(\.id))
            }
        }
    }

    private func row(_ p: Participant) -> some View {
        Button {
            Haptics.tap()
            open(p)
        } label: {
            HStack(spacing: 12) {
                Avatar(name: p.name, url: p.headshotUrl, size: 40)
                VStack(alignment: .leading, spacing: 1) {
                    Text(p.fullName?.nonBlank ?? p.name).font(.body).foregroundStyle(.primary).lineLimit(1)
                    Text("Checked in \(Time.ago(p.checkedInAt, now: now) ?? "")")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                if p.hasSafetyAlert {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(Tone.danger.color)
                        .accessibilityLabel("Has a safety alert")
                }
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.vertical, 8)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Limited roles

struct HomeLimitedAccessCard: View {
    let event: Event

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "lock.fill")
                .font(.title3)
                .foregroundStyle(Tone.info.color)
            VStack(alignment: .leading, spacing: 4) {
                Text(EventLogic.roleLabel(event.role).map { "You're \($0.lowercased()) on this event" } ?? "Limited access")
                    .font(.headline)
                Text("You can scan tickets and send announcements, but your role can't see participant details. Home shows live scan activity\(event.travelEnabled ? " and travel" : "") instead.")
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .foregroundStyle(Tone.info.onContainer)
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .dashCardBackground(Tone.info.container)
        .accessibilityElement(children: .combine)
    }
}

/// Today's scan count for roles without roster access.
struct HomeScansHeroCard: View {
    let feed: ScanFeedSummary
    @ScaledMetric(relativeTo: .largeTitle) private var numberSize: CGFloat = 56
    @ScaledMetric(relativeTo: .largeTitle) private var badgeSize: CGFloat = 96

    var body: some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 2) {
                Label("Today", systemImage: "calendar")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.tint)
                DashNumber(value: feed.today, suffix: feed.capped ? "+" : "")
                    .font(.stat(numberSize, weight: .heavy))
                Text(feed.today == 1 ? "scan" : "scans")
                    .font(.headline)
                    .foregroundStyle(.secondary)
                HStack(spacing: 6) {
                    Image(systemName: "person.2.fill").foregroundStyle(.tint)
                    DashNumber(value: feed.uniquePeopleToday).fontWeight(.bold)
                    Text(feed.uniquePeopleToday == 1 ? "person scanned" : "people scanned").foregroundStyle(.secondary)
                }
                .font(.subheadline)
                .padding(.top, 8)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "qrcode.viewfinder")
                .font(.system(size: badgeSize * 0.42, weight: .medium))
                .foregroundStyle(.white)
                .frame(width: badgeSize, height: badgeSize)
                .background(Color.accentColor.gradient, in: .rect(cornerRadius: badgeSize * 0.3, style: .continuous))
                .accessibilityHidden(true)
        }
        .padding(18)
        .dashCardBackground()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(feed.today)\(feed.capped ? " or more" : "") scans today, \(feed.uniquePeopleToday) people")
    }
}

struct HomeRecentScansCard: View {
    let scans: [Scan]
    let travel: TravelCalendar?
    let now: Date

    var body: some View {
        // Travel entries are the only names a limited role can see.
        let names = Dictionary((travel?.entries ?? []).compactMap { e in e.participantEventId.map { ($0, e.name) } }, uniquingKeysWith: { a, _ in a })
        DashCard(title: "Latest Scans") {
            VStack(spacing: 0) {
                ForEach(Array(scans.enumerated()), id: \.element.id) { i, scan in
                    if i > 0 { Divider().padding(.leading, 36) }
                    row(scan, names: names)
                }
            }
        }
    }

    private func row(_ scan: Scan, names: [String: String]) -> some View {
        let ctx = scan.scanContext
        let short = (scan.participantId ?? scan.participantEventId).map { String($0.split(separator: "-").first ?? "").uppercased() } ?? ""
        let who = scan.participantEventId.flatMap { names[$0] } ?? "Attendee \(short)".trimmingCharacters(in: .whitespaces)
        let icon = ctx.map { DashboardIcons.context(name: $0.name, checksIn: $0.checksIn, travel: $0.isTravelPickup) } ?? "qrcode.viewfinder"
        return HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(.secondary)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 1) {
                Text(who).font(.body).lineLimit(1)
                let sub = [ctx?.name, scan.scannedBy.map { "by \($0)" }].compactMap { $0 }.joined(separator: " · ")
                if !sub.isEmpty {
                    Text(sub).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            Text(Time.ago(scan.scannedAt, now: now) ?? "")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 9)
        .accessibilityElement(children: .combine)
    }
}
