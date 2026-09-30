import SwiftUI

/// The participant's tickets: upcoming and live passes first, past events below. Offline-first from the
/// cache (QR codes keep working with no signal), with pull to refresh.
struct TicketsView: View {
    /// True when Tickets is the only tab, so it carries the account button.
    var showsAccount = false

    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @State private var refreshing = false
    @State private var error: String?
    @State private var lastRefresh: Date?
    @State private var web: TicketWebPage?

    private var tickets: [Ticket]? { app.tickets.tickets }

    var body: some View {
        TimelineView(.everyMinute) { context in
            content(now: context.date)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("My Tickets")
        .modifier(TicketSubtitleModifier(subtitle: subtitle))
        .toolbar {
            if showsAccount {
                ToolbarItem(placement: .topBarTrailing) { AccountButton() }
            }
        }
        .task { await refresh(force: false) }
        .ticketSafariSheet($web)
    }

    private var subtitle: String? {
        guard let tickets else { return nil }
        return TicketPassLogic.subtitle(current: TicketLogic.sorted(tickets).current.count, total: tickets.count)
    }

    @ViewBuilder
    private func content(now: Date) -> some View {
        if let tickets, !tickets.isEmpty {
            list(tickets, now: now)
        } else if tickets != nil || error != nil {
            // Empty and failure states still pull to refresh.
            ScrollView {
                Group {
                    if let error, !refreshing {
                        ContentUnavailableView {
                            Label("Couldn't Load Your Tickets", systemImage: "wifi.exclamationmark")
                        } description: {
                            Text(error)
                        } actions: {
                            Button("Try Again") { Task { await refresh(force: true) } }
                                .buttonStyle(.bordered)
                        }
                    } else if refreshing && tickets == nil {
                        ProgressView("Fetching your tickets…")
                    } else {
                        ContentUnavailableView {
                            Label("No Tickets Yet", systemImage: "ticket")
                        } description: {
                            Text("When you register for a Hack Club event, your ticket shows up here. It's saved on your phone, so it works even without signal at the door.")
                        }
                    }
                }
                .containerRelativeFrame(.vertical) { h, _ in h * 0.8 }
                .frame(maxWidth: .infinity)
            }
            .refreshable { await refresh(force: true) }
        } else {
            ProgressView("Fetching your tickets…")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func list(_ tickets: [Ticket], now: Date) -> some View {
        let (current, past) = TicketLogic.sorted(tickets, now: now)
        return ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let error {
                    NoticeBanner(message: "\(error) Showing your saved tickets — QR codes still work offline.") {
                        Task { await refresh(force: true) }
                    }
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
                grid(current, featuredId: current.first?.id, past: false, now: now)
                if !past.isEmpty {
                    Text("Past Events")
                        .font(.title3.weight(.semibold))
                        .padding(.top, current.isEmpty ? 0 : 8)
                        .accessibilityAddTraits(.isHeader)
                    grid(past, featuredId: nil, past: true, now: now)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 32)
            .frame(maxWidth: 1100)
            .frame(maxWidth: .infinity)
            .animation(.smooth, value: tickets)
            .animation(.smooth, value: error)
        }
        .refreshable { await refresh(force: true) }
    }

    private func grid(_ tickets: [Ticket], featuredId: String?, past: Bool, now: Date) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 330, maximum: 560), spacing: 16, alignment: .top)], spacing: 16) {
            ForEach(tickets) { t in
                TicketCard(
                    ticket: t,
                    now: now,
                    featured: t.id == featuredId,
                    past: past,
                    open: { open(t) },
                    finishRegistration: t.onboardingUrl.flatMap(URL.init(string:)).map { url in { openWeb(url) } }
                )
                .transition(.scale(scale: 0.96).combined(with: .opacity))
            }
        }
    }

    private func open(_ t: Ticket) {
        Haptics.tap()
        router.open(.ticket(id: t.id))
    }

    private func openWeb(_ url: URL) {
        Haptics.tap()
        web = TicketWebPage(url: url)
    }

    private func refresh(force: Bool) async {
        guard !refreshing else { return }
        guard force || TicketPassLogic.refreshDue(last: lastRefresh) else { return }
        refreshing = true
        defer { refreshing = false }
        do {
            try await app.tickets.refresh()
            error = nil
            lastRefresh = Date()
        } catch where error.isCancellation {
            return
        } catch {
            self.error = error.friendlyMessage
            if force { Haptics.reject() }
        }
    }
}

// MARK: - Card

/// One registration as a pass-like card: calendar block, name, dates and city above the perforation;
/// status and when below. Incomplete registrations explain who needs to act.
struct TicketCard: View {
    let ticket: Ticket
    let now: Date
    var featured = false
    var past = false
    var open: () -> Void
    var finishRegistration: (() -> Void)?

    @State private var notchY: CGFloat?

    private var status: TicketLogic.Status { TicketLogic.status(ticket) }
    private var event: TicketEvent { ticket.event }
    private var onBrand: Bool { featured }

    var body: some View {
        VStack(spacing: 0) {
            Button(action: open) {
                VStack(spacing: 0) {
                    header
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { notchY = $0 }
                    TicketPerforation(color: onBrand ? .white.opacity(0.35) : Color(uiColor: .separator))
                        .padding(.horizontal, 22)
                    footer
                }
                .contentShape(.rect)
            }
            .buttonStyle(TicketPressableStyle())
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityText)
            .accessibilityHint(ticket.confirmed ? "Opens your pass" : "Opens your registration")
            .accessibilityAddTraits(.isButton)

            if case .incomplete(let label, let reason) = status {
                TicketIncompleteCallout(label: label, reason: reason, action: finishRegistration)
                    .padding([.horizontal, .bottom], 12)
            }
        }
        .foregroundStyle(onBrand ? .white : .primary)
        .background(background, in: TicketPassShape(cornerRadius: 24, notchRadius: 11, notchY: notchY))
        .shadow(color: .black.opacity(onBrand ? 0.18 : 0.06), radius: onBrand ? 14 : 8, y: onBrand ? 8 : 3)
        .contextMenu {
            if ticket.confirmed {
                Button("Show Pass", systemImage: "qrcode", action: open)
            } else {
                Button("View Registration", systemImage: "doc.text", action: open)
            }
            if let finishRegistration, case .incomplete = status {
                Button("Finish Registration", systemImage: "safari", action: finishRegistration)
            }
            if let code = ticket.shortCode?.nonBlank, ticket.confirmed {
                Button("Copy Code", systemImage: "doc.on.doc") {
                    UIPasteboard.general.string = code
                    Haptics.confirm()
                }
            }
        }
    }

    private var background: AnyShapeStyle {
        if onBrand {
            AnyShapeStyle(LinearGradient(colors: [HackClub.red, Color(hex: 0xD62845)], startPoint: .topLeading, endPoint: .bottomTrailing))
        } else {
            AnyShapeStyle(Color(uiColor: .secondarySystemGroupedBackground))
        }
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 14) {
            TicketCalendarBlock(event: event, onBrand: onBrand, dimmed: past)
            VStack(alignment: .leading, spacing: 3) {
                Text(event.name)
                    .font(.title3.weight(.bold))
                    .foregroundStyle(past ? AnyShapeStyle(.secondary) : AnyShapeStyle(.primary))
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Text(Time.range(event.startsAt, event.endsAt, tz: event.timezone, now: now) ?? "Dates to be announced")
                    .font(.subheadline)
                    .foregroundStyle(secondary)
                    .lineLimit(1)
                if let city = event.locationCity?.nonBlank {
                    Label(city, systemImage: "mappin.and.ellipse")
                        .font(.subheadline)
                        .foregroundStyle(secondary)
                        .labelStyle(TicketTightLabelStyle())
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 18)
    }

    private var footer: some View {
        HStack(spacing: 8) {
            TicketStatusPill(status: status, onBrand: onBrand)
            if !past, !status.isClosed {
                let live = TicketLogic.countdown(event, now: now) == .live
                TicketWhenPill(label: TicketLogic.relativeLabel(event, now: now), live: live, onBrand: onBrand)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(onBrand ? AnyShapeStyle(.white.opacity(0.8)) : AnyShapeStyle(.tertiary))
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
    }

    private var secondary: AnyShapeStyle {
        onBrand ? AnyShapeStyle(.white.opacity(0.85)) : AnyShapeStyle(.secondary)
    }

    private var accessibilityText: String {
        var parts = [event.name, Time.range(event.startsAt, event.endsAt, tz: event.timezone, now: now) ?? "Dates to be announced"]
        if let city = event.locationCity?.nonBlank { parts.append(city) }
        parts.append(status.label)
        if !past, !status.isClosed { parts.append(TicketLogic.relativeLabel(event, now: now)) }
        return parts.joined(separator: ", ")
    }
}

/// "OCT / 3" in a rounded tile.
private struct TicketCalendarBlock: View {
    let event: TicketEvent
    var onBrand = false
    var dimmed = false
    @ScaledMetric(relativeTo: .title2) private var size: CGFloat = 58

    var body: some View {
        let (month, day) = TicketPassLogic.calendarBlock(event)
        VStack(spacing: 0) {
            Text(month)
                .font(.caption2.weight(.heavy))
                .tracking(1)
                .foregroundStyle(onBrand ? HackClub.red : dimmed ? Color.secondary : HackClub.red)
            Text(day)
                .font(.system(.title2, design: .rounded, weight: .bold))
                .foregroundStyle(onBrand ? Color.black : dimmed ? Color.secondary : Color.primary)
        }
        .frame(width: size, height: size)
        .background(onBrand ? AnyShapeStyle(.white) : AnyShapeStyle(Color(uiColor: .tertiarySystemFill)),
                    in: .rect(cornerRadius: 14, style: .continuous))
        .accessibilityHidden(true)
    }
}

/// Who needs to act on an unfinished registration, with a way to finish it.
private struct TicketIncompleteCallout: View {
    let label: String
    let reason: String
    var action: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Image(systemName: "hourglass")
                    .foregroundStyle(Tone.warning.color)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.subheadline.weight(.semibold))
                    Text(reason).font(.footnote)
                }
                .foregroundStyle(Tone.warning.onContainer)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if let action {
                Button(action: action) {
                    Label("Finish Registration", systemImage: "arrow.up.forward.app")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Tone.warning.color)
                .foregroundStyle(Tone.warning.onColor)
                .accessibilityHint("Opens your registration form")
            }
        }
        .padding(12)
        .background(Tone.warning.container, in: .rect(cornerRadius: 16, style: .continuous))
    }
}

/// Icon and title with a small gap (for inline metadata).
struct TicketTightLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) {
            configuration.icon.imageScale(.small)
            configuration.title
        }
    }
}

/// Cards dip slightly while pressed, like Wallet passes.
struct TicketPressableStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .opacity(configuration.isPressed ? 0.9 : 1)
            .animation(.snappy(duration: 0.2), value: configuration.isPressed)
    }
}

/// `.navigationSubtitle` on iOS 26; nothing on older systems (the large title stands alone).
struct TicketSubtitleModifier: ViewModifier {
    let subtitle: String?

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *), let subtitle {
            content.navigationSubtitle(subtitle)
        } else {
            content
        }
    }
}
