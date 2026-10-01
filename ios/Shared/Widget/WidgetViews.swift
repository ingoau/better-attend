import AppIntents
import SwiftUI
import WidgetKit

// Widget bodies as plain SwiftUI views that take the snapshot, the family and the entry date, so the
// app can render them too (unit-test snapshots, previews). The widget extension wraps them in
// `StaticConfiguration`s; `containerBackground` and `widgetURL` are no-ops outside WidgetKit.

/// Colours for widgets. The accent matches the app's `AccentColor` asset (Hack Club red).
enum WidgetPalette {
    static let accent = Color(light: 0xEC3750, dark: 0xFF5C78)
    static let accentDeep = Color(light: 0xC81E3C, dark: 0xD8264A)
    static let background = Color(light: 0xFFFFFF, dark: 0x1C1C1E)
    static let groupFill = Color(light: 0xF2F2F7, dark: 0x2C2C2E)
}

extension View {
    /// The standard widget surface.
    func attendWidgetBackground() -> some View {
        containerBackground(for: .widget) { WidgetPalette.background }
    }
}

// MARK: - Check-in progress

struct CheckInWidgetView: View {
    var snapshot: WidgetSnapshot
    var family: WidgetFamily
    var now: Date

    var body: some View {
        content
            .widgetURL(DeepLink.home)
            .attendWidgetBackground()
    }

    @ViewBuilder private var content: some View {
        if !snapshot.signedIn {
            WidgetMessage.signedOut(family)
        } else if let org = snapshot.organizer {
            if !org.hasCounts {
                WidgetMessage(icon: "person.2.fill", title: org.eventName, message: "Open BetterAttend to sync check-ins", family: family)
            } else if org.expected == 0 {
                WidgetMessage(icon: "person.2.fill", title: org.eventName, message: "No participants yet", family: family)
            } else {
                counts(org)
            }
        } else {
            WidgetMessage(icon: "calendar", title: "No event selected", message: "Open BetterAttend to choose one", family: family)
        }
    }

    @ViewBuilder private func counts(_ org: OrganizerWidgetData) -> some View {
        switch family {
        case .accessoryCircular: CheckInCircular(org: org)
        case .accessoryRectangular: CheckInRectangular(org: org)
        case .accessoryInline: CheckInInline(org: org)
        case .systemMedium: CheckInMedium(org: org, now: now)
        case .systemLarge, .systemExtraLarge: CheckInLarge(org: org, now: now)
        default: CheckInSmall(org: org)
        }
    }
}

private struct CheckInSmall: View {
    var org: OrganizerWidgetData

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            WidgetHeader(icon: "person.2.fill", title: org.eventName)
            Spacer(minLength: 4)
            BigCount(org: org, size: 44, compact: true)
            ProgressBar(value: org.progress, hidden: org.nobodyYet)
                .padding(.top, 6)
                .padding(.bottom, 6)
            Text(WidgetLabels.checkInStatus(org, compact: true))
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }
}

private struct CheckInMedium: View {
    var org: OrganizerWidgetData
    var now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            WidgetHeader(icon: "person.2.fill", title: org.eventName, refresh: true)
            Spacer(minLength: 2)
            HStack(alignment: .lastTextBaseline) {
                BigCount(org: org, size: 44)
                Spacer(minLength: 8)
                if org.lastHour > 0 { LastHour(count: org.lastHour).padding(.bottom, 6) }
            }
            ProgressBar(value: org.progress, hidden: org.nobodyYet)
                .padding(.vertical, 6)
            StatusRow(org: org, now: now)
        }
    }
}

private struct CheckInLarge: View {
    var org: OrganizerWidgetData
    var now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            WidgetHeader(icon: "person.2.fill", title: org.eventName, refresh: true)
            HStack(alignment: .lastTextBaseline) {
                BigCount(org: org, size: 56)
                Spacer(minLength: 8)
                if org.lastHour > 0 { LastHour(count: org.lastHour).padding(.bottom, 8) }
            }
            .padding(.top, 8)
            ProgressBar(value: org.progress, hidden: org.nobodyYet, height: 10)
                .padding(.vertical, 8)
            StatusRow(org: org, now: now)
            Spacer(minLength: 12)
            if !org.contexts.isEmpty {
                ScanPointList(contexts: Array(org.contexts.prefix(5)))
            }
        }
    }
}

/// Per-scan-point totals, like a grouped inset list.
private struct ScanPointList: View {
    var contexts: [ContextCount]
    @Environment(\.widgetRenderingMode) private var renderingMode

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(contexts.enumerated()), id: \.element.id) { i, ctx in
                if i > 0 { Divider().padding(.leading, 30) }
                HStack(spacing: 10) {
                    Image(systemName: icon(ctx))
                        .font(.subheadline)
                        .foregroundStyle(renderingMode == .fullColor ? WidgetPalette.accent : .primary)
                        .widgetAccentable()
                        .frame(width: 20)
                    Text(ctx.name)
                        .font(.subheadline)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    Text(ctx.count, format: .number)
                        .font(.subheadline.weight(.semibold))
                        .monospacedDigit()
                }
                .padding(.vertical, 7)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 2)
        .background(renderingMode == .fullColor ? AnyShapeStyle(WidgetPalette.groupFill) : AnyShapeStyle(.fill.tertiary),
                    in: .rect(cornerRadius: 14, style: .continuous))
    }

    private func icon(_ c: ContextCount) -> String {
        c.isTravel ? "airplane.arrival" : c.checksIn ? "checkmark.circle.fill" : "qrcode.viewfinder"
    }
}

/// "84 / 120", or "120 expected" before anyone has checked in.
private struct BigCount: View {
    var org: OrganizerWidgetData
    var size: CGFloat
    var compact = false

    var body: some View {
        HStack(alignment: .lastTextBaseline, spacing: 3) {
            Text(org.nobodyYet ? org.expected : org.checkedIn, format: .number)
                .font(.stat(size))
                .foregroundStyle(WidgetPalette.accent)
                .widgetAccentable()
                .contentTransition(.numericText())
            if org.nobodyYet {
                if !compact {
                    Text("expected").font(.stat(size * 0.4, weight: .semibold)).foregroundStyle(.secondary)
                }
            } else {
                Text("/ \(org.expected)").font(.stat(size * 0.4, weight: .semibold)).foregroundStyle(.secondary)
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(0.6)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(WidgetLabels.checkInAccessibility(org))
    }
}

private struct LastHour: View {
    var count: Int

    var body: some View {
        Label {
            Text("+\(count) last hour")
        } icon: {
            Image(systemName: "arrow.up.right")
        }
        .font(.caption.weight(.semibold))
        .labelStyle(.titleAndIcon)
        .foregroundStyle(Tone.success.color)
        .lineLimit(1)
    }
}

private struct StatusRow: View {
    var org: OrganizerWidgetData
    var now: Date

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(WidgetLabels.checkInStatus(org, compact: false))
                .font(.caption.weight(.medium))
            Spacer(minLength: 8)
            Text(WidgetLabels.updated(org.updatedAt, now: now))
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .lineLimit(1)
    }
}

private struct CheckInCircular: View {
    var org: OrganizerWidgetData

    var body: some View {
        Group {
            if org.nobodyYet {
                ZStack {
                    AccessoryWidgetBackground()
                    VStack(spacing: -2) {
                        Text(org.expected, format: .number)
                            .font(.system(.title3, design: .rounded, weight: .bold))
                            .minimumScaleFactor(0.5)
                        Text("exp.").font(.system(size: 10, weight: .semibold))
                    }
                    .padding(6)
                }
            } else {
                Gauge(value: org.progress) {
                    Image(systemName: "person.2.fill")
                } currentValueLabel: {
                    Text(org.checkedIn, format: .number)
                        .minimumScaleFactor(0.5)
                }
                .gaugeStyle(.accessoryCircular)
                .widgetAccentable()
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(WidgetLabels.checkInAccessibility(org))
    }
}

private struct CheckInRectangular: View {
    var org: OrganizerWidgetData

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Label(org.eventName, systemImage: "person.2.fill")
                .font(.headline)
                .widgetAccentable()
            if org.nobodyYet {
                Text("\(org.expected) expected")
                Text("No one checked in yet").foregroundStyle(.secondary)
            } else {
                Text("\(org.checkedIn) of \(org.expected) checked in")
                Gauge(value: org.progress) { EmptyView() }
                    .gaugeStyle(.accessoryLinearCapacity)
                    .widgetAccentable()
            }
        }
        .lineLimit(1)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct CheckInInline: View {
    var org: OrganizerWidgetData

    var body: some View {
        Label(org.nobodyYet ? "\(org.expected) expected" : "\(org.checkedIn)/\(org.expected) checked in",
              systemImage: "person.2.fill")
    }
}

// MARK: - Arrivals

struct ArrivalsWidgetView: View {
    var snapshot: WidgetSnapshot
    var family: WidgetFamily
    var now: Date

    var body: some View {
        content
            .widgetURL(snapshot.organizer?.travelEnabled == true ? DeepLink.travel : DeepLink.home)
            .attendWidgetBackground()
    }

    @ViewBuilder private var content: some View {
        if !snapshot.signedIn {
            WidgetMessage.signedOut(family)
        } else if let org = snapshot.organizer {
            if !org.travelEnabled {
                WidgetMessage(icon: "airplane.arrival", title: "No travel for \(org.eventName)",
                              message: "Arrivals appear for events with travel", family: family)
            } else if let travel = org.travel {
                if family == .systemSmall {
                    ArrivalsSmall(travel: travel, timezone: org.timezone, now: now)
                } else {
                    ArrivalsMedium(eventName: org.eventName, travel: travel, timezone: org.timezone, now: now)
                }
            } else {
                WidgetMessage(icon: "airplane.arrival", title: "Arrivals", message: "Open BetterAttend to load travel", family: family)
            }
        } else {
            WidgetMessage(icon: "calendar", title: "No event selected", message: "Open BetterAttend to choose one", family: family)
        }
    }
}

private struct ArrivalsSmall: View {
    var travel: TravelWidgetData
    var timezone: String?
    var now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            WidgetHeader(icon: "airplane.arrival", title: "Arrivals")
            Spacer(minLength: 4)
            Text(travel.awaitingPickup, format: .number)
                .font(.stat(44))
                .foregroundStyle(WidgetPalette.accent)
                .widgetAccentable()
                .contentTransition(.numericText())
                .accessibilityLabel("\(travel.awaitingPickup) awaiting pickup")
            Text("awaiting pickup")
                .font(.caption.weight(.medium))
                .accessibilityHidden(true)
            Spacer(minLength: 6)
            NextArrival(travel: travel, timezone: timezone, now: now, compact: true)
        }
    }
}

private struct ArrivalsMedium: View {
    var eventName: String
    var travel: TravelWidgetData
    var timezone: String?
    var now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            WidgetHeader(icon: "airplane.arrival", title: "Arrivals · \(eventName)", refresh: true)
            Spacer(minLength: 6)
            HStack(spacing: 6) {
                StatTile(value: travel.awaitingPickup, label: "Waiting", highlighted: true)
                StatTile(value: travel.collected, label: "Collected")
                StatTile(value: travel.checkedIn, label: "Checked in")
            }
            Spacer(minLength: 8)
            NextArrival(travel: travel, timezone: timezone, now: now, compact: false)
        }
    }
}

private struct StatTile: View {
    var value: Int
    var label: String
    var highlighted = false
    @Environment(\.widgetRenderingMode) private var renderingMode

    var body: some View {
        let full = renderingMode == .fullColor
        VStack(alignment: .leading, spacing: 0) {
            Text(value, format: .number)
                .font(.stat(24))
                .foregroundStyle(highlighted && full ? WidgetPalette.accent : .primary)
                .widgetAccentable(highlighted)
                .contentTransition(.numericText())
            Text(label)
                .font(.caption2.weight(.medium))
                .foregroundStyle(.secondary)
        }
        .lineLimit(1)
        .minimumScaleFactor(0.8)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(background, in: .rect(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    private var background: AnyShapeStyle {
        guard renderingMode == .fullColor else { return AnyShapeStyle(.fill.tertiary) }
        return highlighted ? AnyShapeStyle(WidgetPalette.accent.opacity(0.14)) : AnyShapeStyle(WidgetPalette.groupFill)
    }
}

/// "Next  Sam [UM]  MEL → SYD · QF401     11:40 AM · in 10 min".
private struct NextArrival: View {
    var travel: TravelWidgetData
    var timezone: String?
    var now: Date
    var compact: Bool

    var body: some View {
        if let date = Time.parse(travel.nextArrivalAt), let time = Time.time(travel.nextArrivalAt, tz: timezone) {
            let who = travel.nextArrivalName ?? "Someone"
            let detail = [travel.nextArrivalRoute, travel.nextArrivalReference].compactMap { $0 }.joined(separator: " · ")
            Group {
                if compact {
                    VStack(alignment: .leading, spacing: 1) {
                        HStack(spacing: 4) {
                            Text(who).fontWeight(.semibold).privacySensitive()
                            if travel.nextArrivalMinor { MinorBadge() }
                        }
                        Text("\(time) · \(WidgetLabels.relative(date, now: now))")
                            .foregroundStyle(.secondary)
                    }
                } else {
                    HStack(spacing: 5) {
                        Text("Next").foregroundStyle(.secondary)
                        Text(who).fontWeight(.semibold).privacySensitive().layoutPriority(2)
                        if travel.nextArrivalMinor { MinorBadge().layoutPriority(2) }
                        if !detail.isEmpty { Text(detail).foregroundStyle(.secondary) }
                        Spacer(minLength: 6)
                        Text("\(Text(time).fontWeight(.semibold)) · \(WidgetLabels.relative(date, now: now))")
                            .layoutPriority(3)
                    }
                }
            }
            .font(.caption)
            .lineLimit(1)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(WidgetLabels.nextArrival(travel, tz: timezone, compact: false))
        } else {
            Text(WidgetLabels.nextArrival(travel, tz: timezone, compact: compact))
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
    }
}

/// Unaccompanied minor flag.
private struct MinorBadge: View {
    var body: some View {
        Text("UM")
            .font(.system(size: 9, weight: .heavy))
            .padding(.horizontal, 4)
            .padding(.vertical, 1)
            .foregroundStyle(Tone.warning.onContainer)
            .background(Tone.warning.container, in: .capsule)
            .accessibilityLabel("unaccompanied minor")
    }
}

// MARK: - Quick scan

struct QuickScanWidgetView: View {
    var snapshot: WidgetSnapshot
    var family: WidgetFamily

    private var eventName: String? { snapshot.signedIn ? snapshot.organizer?.eventName : nil }

    var body: some View {
        Group {
            switch family {
            case .accessoryCircular:
                ZStack {
                    AccessoryWidgetBackground()
                    Image(systemName: "qrcode.viewfinder")
                        .font(.system(size: 26, weight: .semibold))
                        .widgetAccentable()
                }
            case .accessoryInline:
                Label("Scan tickets", systemImage: "qrcode.viewfinder")
            default:
                small
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(eventName.map { "Scan tickets for \($0)" } ?? "Open the scanner")
        .widgetURL(DeepLink.scan)
        .containerBackground(for: .widget) {
            LinearGradient(colors: [WidgetPalette.accent, WidgetPalette.accentDeep], startPoint: .topLeading, endPoint: .bottomTrailing)
        }
    }

    private var small: some View {
        VStack(alignment: .leading, spacing: 2) {
            Image(systemName: "qrcode.viewfinder")
                .font(.system(size: 30, weight: .semibold))
                .frame(width: 52, height: 52)
                .background(.white.opacity(0.2), in: .circle)
                .widgetAccentable()
            Spacer(minLength: 4)
            Text("Scan")
                .font(.title2.weight(.bold))
            Text(eventName ?? "Tickets and badges")
                .font(.caption.weight(.medium))
                .opacity(0.85)
                .lineLimit(2)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}

// MARK: - My ticket

struct TicketWidgetView: View {
    var snapshot: WidgetSnapshot
    var family: WidgetFamily
    var now: Date

    private var link: URL {
        guard let t = snapshot.ticket, t.confirmed else { return DeepLink.tickets }
        return DeepLink.ticket(t.id)
    }

    var body: some View {
        content
            .widgetURL(link)
            .attendWidgetBackground()
    }

    @ViewBuilder private var content: some View {
        if !snapshot.signedIn {
            WidgetMessage.signedOut(family)
        } else if let t = snapshot.ticket {
            switch family {
            case .systemMedium: TicketMedium(ticket: t, now: now)
            case .accessoryRectangular: TicketRectangular(ticket: t, now: now)
            case .accessoryInline: Label("\(WidgetLabels.ticketCountdown(t, now: now)) · \(t.eventName)", systemImage: "ticket.fill")
            default: TicketSmall(ticket: t, now: now)
            }
        } else {
            let copy = WidgetLabels.noTicket(isParticipant: snapshot.isParticipant)
            WidgetMessage(icon: "ticket.fill", title: copy.title, message: copy.body, family: family)
        }
    }
}

private struct TicketSmall: View {
    var ticket: TicketWidgetData
    var now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            WidgetHeader(icon: "ticket.fill", title: "My ticket")
            Spacer(minLength: 4)
            Text(ticket.eventName)
                .font(.headline)
                .lineLimit(2)
                .minimumScaleFactor(0.85)
            if !ticket.confirmed {
                Label(ticket.statusLabel, systemImage: "exclamationmark.circle.fill")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(Tone.danger.color)
                    .lineLimit(1)
                    .padding(.top, 2)
            }
            CountdownChip(ticket: ticket, now: now)
                .padding(.top, 8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct TicketMedium: View {
    var ticket: TicketWidgetData
    var now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                WidgetHeader(icon: "ticket.fill", title: ["My ticket", ticket.city].compactMap { $0 }.joined(separator: " · "))
                if ticket.confirmed, !ticket.checkedIn, let code = ticket.shortCode {
                    Text(WidgetLabels.grouped(code))
                        .font(.caption.monospaced().weight(.semibold))
                        .foregroundStyle(.secondary)
                        .privacySensitive()
                        .fixedSize()
                        .accessibilityLabel("Code \(code)")
                }
            }
            Spacer(minLength: 4)
            Text(ticket.eventName)
                .font(.title3.weight(.bold))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            TicketWhen(ticket: ticket, now: now)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Spacer(minLength: 6)
            HStack(alignment: .center) {
                CountdownChip(ticket: ticket, now: now)
                Spacer(minLength: 8)
                TicketStatus(ticket: ticket)
            }
        }
    }
}

private struct TicketRectangular: View {
    var ticket: TicketWidgetData
    var now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Label(ticket.eventName, systemImage: "ticket.fill")
                .font(.headline)
                .widgetAccentable()
            TicketWhen(ticket: ticket, now: now, preferCountdown: true)
            if !ticket.confirmed {
                Text(ticket.statusLabel).foregroundStyle(.secondary)
            } else if !ticket.checkedIn, let code = ticket.shortCode {
                Text("\(Image(systemName: "qrcode")) \(WidgetLabels.grouped(code))")
                    .monospaced()
                    .foregroundStyle(.secondary)
                    .privacySensitive()
            } else {
                Text(ticket.statusLabel).foregroundStyle(.secondary)
            }
        }
        .lineLimit(1)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The date line: "Sat 3 – Mon 5 Oct", or a live "Doors open in 5 hr, 12 min" on the day.
private struct TicketWhen: View {
    var ticket: TicketWidgetData
    var now: Date
    var preferCountdown = false

    var body: some View {
        let start = Time.parse(ticket.startsAt)
        if let start, start > now, start.timeIntervalSince(now) < 86_400 {
            Text("Doors open in \(Text(start, style: .relative))")
        } else if preferCountdown {
            Text(WidgetLabels.ticketCountdown(ticket, now: now))
        } else {
            Text(Time.range(ticket.startsAt, ticket.endsAt, tz: ticket.timezone, now: now) ?? "Date to be announced")
        }
    }
}

/// "Tomorrow · 9:00 AM", a ticking "Starts in 34:12", or "Happening now".
private struct CountdownChip: View {
    var ticket: TicketWidgetData
    var now: Date
    @Environment(\.widgetRenderingMode) private var renderingMode

    var body: some View {
        let start = Time.parse(ticket.startsAt)
        let label = WidgetLabels.ticketCountdown(ticket, now: now)
        let live = label == "Happening now"
        let tone: Tone = live ? .success : label == "Ended" ? .neutral : .brand
        Group {
            if let start, start > now, start.timeIntervalSince(now) < 3600 {
                Text("Starts in \(Text(start, style: .timer))")
            } else {
                Text(label)
            }
        }
        .font(.caption.weight(.bold))
        .monospacedDigit()
        .lineLimit(1)
        .foregroundStyle(renderingMode == .fullColor ? foreground(tone) : AnyShapeStyle(.primary))
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(renderingMode == .fullColor ? background(tone) : AnyShapeStyle(.fill.tertiary), in: .capsule)
        .widgetAccentable()
    }

    private func foreground(_ tone: Tone) -> AnyShapeStyle {
        tone == .brand ? AnyShapeStyle(WidgetPalette.accent) : AnyShapeStyle(tone.onContainer)
    }

    private func background(_ tone: Tone) -> AnyShapeStyle {
        tone == .brand ? AnyShapeStyle(WidgetPalette.accent.opacity(0.14)) : AnyShapeStyle(tone.container)
    }
}

private struct TicketStatus: View {
    var ticket: TicketWidgetData

    var body: some View {
        let ok = ticket.confirmed
        Label(ticket.statusLabel, systemImage: ticket.checkedIn ? "checkmark.circle.fill" : ok ? "checkmark.seal.fill" : "exclamationmark.circle.fill")
            .font(.caption.weight(.semibold))
            .foregroundStyle(ok ? Tone.success.color : Tone.danger.color)
            .lineLimit(1)
    }
}

// MARK: - Shared pieces

/// Icon + title line at the top of a widget, with an optional refresh button.
private struct WidgetHeader: View {
    var icon: String
    var title: String
    var refresh = false

    var body: some View {
        HStack(alignment: .center, spacing: 5) {
            Image(systemName: icon)
                .font(.caption.weight(.semibold))
                .foregroundStyle(WidgetPalette.accent)
                .widgetAccentable()
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Spacer(minLength: 4)
            if refresh { RefreshButton() }
        }
        .frame(minHeight: refresh ? 26 : 0)
    }
}

/// Interactive refresh (re-reads the latest snapshot).
struct RefreshButton: View {
    var body: some View {
        Button(intent: RefreshWidgetsIntent()) {
            Image(systemName: "arrow.clockwise")
                .font(.caption2.weight(.bold))
                .foregroundStyle(.secondary)
                .frame(width: 26, height: 26)
                .background(.fill.tertiary, in: .circle)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Refresh")
    }
}

/// A rounded progress bar; `hidden` keeps its space so the layout doesn't jump when check-in starts.
private struct ProgressBar: View {
    var value: Double
    var hidden = false
    var height: CGFloat = 8
    @Environment(\.widgetRenderingMode) private var renderingMode

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(renderingMode == .fullColor ? AnyShapeStyle(WidgetPalette.accent.opacity(0.16)) : AnyShapeStyle(.fill.tertiary))
                Capsule()
                    .fill(WidgetPalette.accent)
                    .frame(width: max(height, geo.size.width * min(max(value, 0), 1)))
                    .widgetAccentable()
            }
        }
        .frame(height: height)
        .opacity(hidden ? 0 : 1)
        .accessibilityHidden(true)
    }
}

/// Centred icon + message for "Sign in", "No event" and similar states.
struct WidgetMessage: View {
    var icon: String
    var title: String
    var message: String?
    var family: WidgetFamily
    @Environment(\.widgetRenderingMode) private var renderingMode

    static func signedOut(_ family: WidgetFamily) -> WidgetMessage {
        WidgetMessage(icon: "person.crop.circle", title: "Sign in to BetterAttend", message: "Tap to open the app", family: family)
    }

    var body: some View {
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: icon).font(.title2.weight(.semibold)).widgetAccentable()
            }
            .accessibilityLabel(title)
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 1) {
                Label(title, systemImage: icon).font(.headline).widgetAccentable()
                if let message { Text(message).foregroundStyle(.secondary) }
            }
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
        case .accessoryInline:
            Label(title, systemImage: icon)
        default:
            VStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: family == .systemSmall ? 20 : 22, weight: .semibold))
                    .foregroundStyle(renderingMode == .fullColor ? AnyShapeStyle(WidgetPalette.accent) : AnyShapeStyle(.primary))
                    .frame(width: 44, height: 44)
                    .background(renderingMode == .fullColor ? AnyShapeStyle(WidgetPalette.accent.opacity(0.14)) : AnyShapeStyle(.fill.tertiary),
                                in: .circle)
                    .widgetAccentable()
                    .padding(.bottom, 2)
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(2)
                if let message {
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityElement(children: .combine)
        }
    }
}
