import MapKit
import SwiftUI

/// A rounded card with a tinted heading, used for everything below the pass.
struct TicketInfoCard<Content: View>: View {
    let title: String
    let systemImage: String
    var tint: Color = HackClub.red
    var background: Color = Color(uiColor: .secondarySystemGroupedBackground)
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(title, systemImage: systemImage)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(tint)
                .accessibilityAddTraits(.isHeader)
            content
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(background, in: .rect(cornerRadius: 22, style: .continuous))
    }
}

// MARK: - Countdown

/// Ticks every second until doors open; then "Happening now", then "Event ended".
struct TicketCountdownCard: View {
    let event: TicketEvent

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let cd = TicketLogic.countdown(event, now: context.date)
            let live = cd == .live
            TicketInfoCard(title: title(cd), systemImage: symbol(cd),
                     tint: live ? Tone.success.onContainer : HackClub.red,
                     background: live ? Tone.success.container : Color(uiColor: .secondarySystemGroupedBackground)) {
                headline(cd, now: context.date)
                dates(live: live)
            }
        }
    }

    private func title(_ cd: TicketLogic.Countdown) -> String {
        switch cd {
        case .upcoming: "Countdown"
        case .live: "Happening Now"
        case .ended: "Event Ended"
        case .unknown: "When"
        }
    }

    private func symbol(_ cd: TicketLogic.Countdown) -> String {
        switch cd {
        case .upcoming: "timer"
        case .live: "sparkles"
        case .ended: "flag.checkered"
        case .unknown: "calendar"
        }
    }

    @ViewBuilder
    private func headline(_ cd: TicketLogic.Countdown, now: Date) -> some View {
        switch cd {
        case .upcoming(let remaining):
            VStack(alignment: .leading, spacing: 2) {
                Text(TicketLogic.clock(remaining))
                    .font(.system(.largeTitle, design: .rounded, weight: .bold))
                    .monospacedDigit()
                    .contentTransition(.numericText(countsDown: true))
                    .animation(.snappy, value: Int(remaining))
                    .accessibilityLabel(TicketLogic.relativeLabel(event, now: now))
                Text("until doors open")
                    .font(.body)
                    .foregroundStyle(.secondary)
            }
        case .live:
            Text("Enjoy the event!")
                .font(.title2.weight(.bold))
                .foregroundStyle(Tone.success.onContainer)
        case .ended:
            Text("Thanks for coming! 👋").font(.title3.weight(.semibold))
        case .unknown:
            Text("Dates to be announced").font(.title3.weight(.semibold))
        }
    }

    @ViewBuilder
    private func dates(live: Bool) -> some View {
        if event.startsAt != nil {
            let secondary = live ? AnyShapeStyle(Tone.success.onContainer.opacity(0.75)) : AnyShapeStyle(.secondary)
            Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 4) {
                GridRow {
                    Text("Starts").foregroundStyle(secondary)
                    Text(Time.dayTime(event.startsAt, tz: event.timezone) ?? "TBA").fontWeight(.medium)
                }
                if let end = Time.dayTime(event.endsAt, tz: event.timezone) {
                    GridRow {
                        Text("Ends").foregroundStyle(secondary)
                        Text(end).fontWeight(.medium)
                    }
                }
            }
            .font(.subheadline)
            .foregroundStyle(live ? Tone.success.onContainer : .primary)
            .padding(.top, 2)
            if let note = TicketPassLogic.timeZoneNote(event) {
                Label(note, systemImage: "globe")
                    .font(.footnote)
                    .foregroundStyle(secondary)
            }
        }
    }
}

// MARK: - Venue

/// Where it is: a map snippet (tap for Maps), the address, and Directions in Apple Maps. Without
/// coordinates the address is looked up with MapKit; offline, Directions falls back to a Maps link.
struct TicketVenueCard: View {
    let event: TicketEvent
    var fallback: (URL) -> Void

    @State private var resolved: MKMapItem?

    private var knownCoordinate: CLLocationCoordinate2D? {
        guard let lat = event.locationLatitude, let lon = event.locationLongitude else { return nil }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    private var coordinate: CLLocationCoordinate2D? {
        knownCoordinate ?? resolved.map(Self.coordinate(of:))
    }

    var body: some View {
        let (line1, line2) = TicketLogic.venueLines(event)
        TicketInfoCard(title: "Venue", systemImage: "mappin.and.ellipse") {
            if let coordinate {
                Map(initialPosition: .region(MKCoordinateRegion(center: coordinate, latitudinalMeters: 900, longitudinalMeters: 900)),
                    interactionModes: []) {
                    Marker(event.name, systemImage: "ticket.fill", coordinate: coordinate)
                        .tint(HackClub.red)
                }
                .mapStyle(.standard(pointsOfInterest: .excludingAll))
                .id("\(coordinate.latitude),\(coordinate.longitude)")
                .frame(height: 150)
                .clipShape(.rect(cornerRadius: 14, style: .continuous))
                .contentShape(.rect)
                .onTapGesture { directions() }
                .accessibilityElement()
                .accessibilityLabel("Map of the venue")
                .accessibilityHint("Opens Apple Maps")
                .accessibilityAddTraits(.isButton)
                .transition(.opacity)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(line1).font(.headline)
                if let line2 { Text(line2).font(.subheadline).foregroundStyle(.secondary) }
            }
            if TicketLogic.hasVenue(event) {
                Button {
                    Haptics.tap()
                    directions()
                } label: {
                    Label("Directions", systemImage: "arrow.triangle.turn.up.right.diamond.fill")
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .accessibilityHint("Opens Apple Maps")
            }
        }
        .animation(.smooth, value: resolved)
        .task(id: event.id) { await resolve() }
    }

    private func resolve() async {
        guard knownCoordinate == nil, resolved == nil, let query = TicketLogic.venueQuery(event) else { return }
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = query
        request.resultTypes = [.address, .pointOfInterest]
        resolved = try? await MKLocalSearch(request: request).start().mapItems.first
    }

    private func directions() {
        let item: MKMapItem
        if let c = knownCoordinate {
            item = Self.mapItem(at: c)
            item.name = event.name
        } else if let resolved {
            item = resolved
        } else {
            if let url = TicketPassLogic.appleMapsURL(event) { fallback(url) }
            return
        }
        item.openInMaps(launchOptions: [MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeDefault])
    }

    private static func mapItem(at c: CLLocationCoordinate2D) -> MKMapItem {
        if #available(iOS 26.0, *) {
            MKMapItem(location: CLLocation(latitude: c.latitude, longitude: c.longitude), address: nil)
        } else {
            MKMapItem(placemark: MKPlacemark(coordinate: c))
        }
    }

    private static func coordinate(of item: MKMapItem) -> CLLocationCoordinate2D {
        if #available(iOS 26.0, *) {
            item.location.coordinate
        } else {
            item.placemark.coordinate
        }
    }
}

// MARK: - Travel

/// The attendee's inbound travel as the organisers have it.
struct TicketTravelCard: View {
    let travel: TicketTravel
    let timezone: String?

    var body: some View {
        TicketInfoCard(title: "Arriving", systemImage: TicketPassLogic.modeSymbol(travel.mode)) {
            if !travel.legs.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(Array(travel.legs.enumerated()), id: \.offset) { i, leg in
                        if i > 0 { Divider() }
                        let lines = TicketPassLogic.legLines(leg, tz: timezone)
                        HStack(alignment: .center, spacing: 12) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(lines.route)
                                    .font(.system(.title3, design: .rounded, weight: .semibold))
                                if let times = lines.times {
                                    Text(times).font(.subheadline).foregroundStyle(.secondary)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            if let code = leg.flightCode?.nonBlank {
                                Pill(text: code, tone: .info, systemImage: "airplane")
                            }
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
            } else {
                let lines = TicketPassLogic.travelLines(travel, tz: timezone)
                VStack(alignment: .leading, spacing: 2) {
                    Text(lines.title).font(.headline)
                    if let route = lines.route { Text(route).font(.subheadline) }
                    if let times = lines.times { Text(times).font(.subheadline).foregroundStyle(.secondary) }
                }
                .accessibilityElement(children: .combine)
            }
            Text("Times in the event's local time. The event team knows your plans.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Messages

/// Messages the organisers sent this attendee (HTML shown as rich text, never as a web view).
struct TicketMessagesCard: View {
    let messages: [TicketMessage]

    var body: some View {
        TicketInfoCard(title: messages.count == 1 ? "Message from the Organisers" : "Messages from the Organisers", systemImage: "envelope.fill") {
            VStack(alignment: .leading, spacing: 14) {
                ForEach(Array(messages.enumerated()), id: \.element.id) { i, m in
                    if i > 0 { Divider() }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(TicketPassLogic.messageMeta(m))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if let subject = m.subject?.nonBlank {
                            Text(subject).font(.headline)
                        }
                        let body = Self.body(m.body)
                        if !body.characters.isEmpty {
                            Text(body)
                                .font(.subheadline)
                                .tint(HackClub.red)
                                .textSelection(.enabled)
                        }
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
    }

    private static func body(_ html: String?) -> AttributedString {
        let markdown = TicketPassLogic.messageMarkdown(html)
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: markdown, options: options)) ?? AttributedString(TicketPassLogic.messageText(html))
    }
}

// MARK: - Safety

/// Always-available help: the incident report form and the 24/7 hotline.
struct TicketSafetyCard: View {
    var report: () -> Void
    var call: () -> Void

    var body: some View {
        TicketInfoCard(title: "Safety", systemImage: "cross.case.fill") {
            Text("If something's wrong at the event, you can always get help — anonymously if you prefer.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            VStack(spacing: 0) {
                row("Report an Incident", detail: "hack.club/incident", symbol: "exclamationmark.bubble.fill", tint: Tone.warning.color,
                    trailing: "arrow.up.forward", action: report)
                Divider().padding(.leading, 52)
                row("24/7 Event Hotline", detail: TicketLogic.hotlineDisplay, symbol: "phone.fill", tint: Tone.success.color,
                    trailing: "phone.arrow.up.right", action: call)
            }
        }
    }

    private func row(_ title: String, detail: String, symbol: String, tint: Color, trailing: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: symbol)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 36, height: 36)
                    .background(tint, in: .rect(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.body.weight(.medium)).foregroundStyle(.primary)
                    Text(detail).font(.footnote).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: trailing)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.vertical, 8)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}
