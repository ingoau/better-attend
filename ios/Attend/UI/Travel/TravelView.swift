import SwiftUI

/// Arrivals and departures for the selected event, grouped by day in the event's timezone.
struct TravelView: View {
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router

    @State private var query = ""
    @State private var filter: TravelFilter = .all
    @State private var mode: TravelMode?
    /// Last refresh failure per event (cached data, if any, is still shown).
    @State private var errors: [String: String] = [:]

    private var event: Event? { app.events.selectedEvent }
    /// Only poll events that have travel turned on.
    private var pollId: String? { event?.travelEnabled == true ? event?.id : nil }

    var body: some View {
        content
            .eventToolbar(fallbackTitle: "Travel")
            .animation(.smooth, value: phase)
            // The server caches travel for 5 min but busts it on pickup / check-in scans.
            .poll(every: .seconds(120), id: pollId) {
                if let pollId { await refresh(pollId) }
            }
    }

    private enum Phase: Hashable { case noEvent, disabled, failed, loading, content }

    private var phase: Phase {
        guard let event else { return .noEvent }
        guard event.travelEnabled else { return .disabled }
        if app.travel.calendars[event.id] == nil {
            return errors[event.id] != nil ? .failed : .loading
        }
        return .content
    }

    @ViewBuilder private var content: some View {
        switch phase {
        case .noEvent:
            ContentUnavailableView {
                Label("No Event Selected", systemImage: "calendar.badge.exclamationmark")
            } description: {
                Text("Pick an event to see who's arriving and leaving.")
            } actions: {
                Button("Choose Event") {
                    Haptics.tap()
                    router.sheet = .eventPicker
                }
                .buttonStyle(.borderedProminent)
            }
        case .disabled:
            ContentUnavailableView(
                "Travel Isn't On", systemImage: "suitcase.rolling",
                description: Text("Once travel is enabled for this event on attend.hackclub.com, arrivals, departures and airport pickups show up here.")
            )
        case .failed:
            ScrollView {
                ContentUnavailableView {
                    Label("Couldn't Load Travel", systemImage: app.isOnline ? "exclamationmark.icloud" : "wifi.slash")
                } description: {
                    Text(event.flatMap { errors[$0.id] } ?? "Something went wrong.")
                } actions: {
                    Button("Try Again") {
                        Haptics.tap()
                        guard let id = event?.id else { return }
                        errors[id] = nil
                        Task { await refresh(id) }
                    }
                    .buttonStyle(.bordered)
                }
                .containerRelativeFrame(.vertical)
            }
            .refreshable { if let id = event?.id { await userRefresh(id) } }
        case .loading:
            ProgressView("Loading travel…")
                .controlSize(.large)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .content:
            if let event, let calendar = app.travel.calendars[event.id] {
                TravelList(
                    event: event, calendar: calendar, query: $query, filter: $filter, mode: $mode,
                    error: errors[event.id], isOnline: app.isOnline,
                    refresh: { await userRefresh(event.id) }
                )
                .id(event.id)
            }
        }
    }

    /// Shows the cached calendar straight away, then refreshes from the server.
    private func refresh(_ eventId: String) async {
        await app.travel.load(eventId)
        do {
            try await app.travel.refresh(eventId)
            errors[eventId] = nil
        } catch {
            if error.isCancellation { return }
            errors[eventId] = error.friendlyMessage
        }
    }

    /// Pull to refresh: runs in its own task so the gesture ending can't cancel the request.
    private func userRefresh(_ eventId: String) async {
        await Task { await refresh(eventId) }.value
    }
}

// MARK: - List

private struct TravelList: View {
    let event: Event
    let calendar: TravelCalendar
    @Binding var query: String
    @Binding var filter: TravelFilter
    @Binding var mode: TravelMode?
    let error: String?
    let isOnline: Bool
    let refresh: () async -> Void

    @Environment(Router.self) private var router
    @State private var scrolledToToday = false

    private var tz: String? { calendar.eventTimezone ?? event.timezone }
    private var hasFilters: Bool { filter != .all || mode != nil }

    var body: some View {
        let all = calendar.entries
        let filtered = TravelLogic.filter(all, query: query, filter: filter, mode: mode)
        let today = TravelLogic.today(tz: tz)
        let sections = TravelLogic.sections(filtered, today: today)

        ScrollViewReader { proxy in
            List {
                // Zone, filters and notices scroll away; the day headers below stay pinned.
                Section {
                    VStack(alignment: .leading, spacing: 0) {
                        if let tz, TimeZone(identifier: tz) != nil {
                            TimeZoneButton(tz: tz)
                                .padding(.horizontal)
                                .padding(.top, 4)
                        }
                        if !all.isEmpty {
                            TravelFilterBar(all: all, query: query, filter: $filter, mode: $mode)
                        }
                        banner
                    }
                    .buttonStyle(.borderless)
                    .listRowInsets(EdgeInsets())
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
                }
                ForEach(sections) { section in
                    Section {
                        ForEach(section.entries) { entry in row(entry) }
                    } header: {
                        TravelSectionHeader(section: section, isToday: section.date == today)
                    }
                    .id(section.id)
                }
            }
            .listStyle(.plain)
            .overlay { emptyState(all: all, filtered: filtered) }
            .searchable(text: $query, prompt: "Name, route or flight")
            .refreshable { await refresh() }
            .task {
                // Jump to today once, the first time there's something before it.
                guard !scrolledToToday else { return }
                scrolledToToday = true
                guard let i = sections.firstIndex(where: { $0.date == today }), i > 0 else { return }
                try? await Task.sleep(for: .milliseconds(80))
                proxy.scrollTo(sections[i].id, anchor: .top)
            }
        }
        .animation(.snappy, value: filtered.map(\.id))
    }

    @ViewBuilder private var banner: some View {
        if let error {
            NoticeBanner(message: "Showing saved travel. \(error)", systemImage: isOnline ? "exclamationmark.icloud" : "wifi.slash",
                         tone: .warning) {
                Task { await refresh() }
            }
            .padding(.horizontal)
            .padding(.bottom, 8)
        } else if !isOnline {
            NoticeBanner(message: "You're offline. Showing saved travel.")
                .padding(.horizontal)
                .padding(.bottom, 8)
        }
    }

    @ViewBuilder private func row(_ entry: TravelEntry) -> some View {
        let canOpen = event.canViewParticipants && entry.participantEventId != nil
        Group {
            if canOpen, let pe = entry.participantEventId {
                NavigationLink(value: Route.participant(eventId: event.id, participantEventId: pe)) {
                    TravelRow(entry: entry, tz: tz)
                }
            } else {
                TravelRow(entry: entry, tz: tz)
            }
        }
        .listRowBackground(entry.isUnaccompaniedMinor ? Tone.warning.container.opacity(0.35) : nil)
        .contextMenu {
            if canOpen, let pe = entry.participantEventId {
                Button("View Participant", systemImage: "person.crop.circle") {
                    router.openParticipant(eventId: event.id, participantEventId: pe)
                }
            }
            if let reference = entry.reference?.nonBlank {
                Button("Copy \(reference)", systemImage: "doc.on.doc") {
                    UIPasteboard.general.string = reference
                    Haptics.confirm()
                }
            }
            if let route = entry.route?.nonBlank {
                Button("Copy Route", systemImage: "arrow.triangle.swap") {
                    UIPasteboard.general.string = route
                    Haptics.confirm()
                }
            }
        }
    }

    @ViewBuilder private func emptyState(all: [TravelEntry], filtered: [TravelEntry]) -> some View {
        if all.isEmpty {
            ContentUnavailableView(
                "No Travel Yet", systemImage: "airplane.arrival",
                description: Text("Journeys appear here once confirmed participants add their travel details.")
            )
        } else if filtered.isEmpty {
            if hasFilters || query.isBlank {
                ContentUnavailableView {
                    Label("No Matches", systemImage: "line.3.horizontal.decrease.circle")
                } description: {
                    Text(query.isBlank ? "No journeys match these filters."
                                       : "No journeys match “\(query.trimmingCharacters(in: .whitespaces))” with these filters.")
                } actions: {
                    Button("Clear Filters") {
                        Haptics.tap()
                        withAnimation(.snappy) {
                            query = ""
                            filter = .all
                            mode = nil
                        }
                    }
                    .buttonStyle(.bordered)
                }
            } else {
                ContentUnavailableView.search(text: query)
            }
        }
    }
}

// MARK: - Pieces

/// "Today  Saturday 3 October            7"
private struct TravelSectionHeader: View {
    let section: TravelSection
    let isToday: Bool

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(section.title)
                .font(.headline)
                .foregroundStyle(isToday ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
            if let subtitle = section.subtitle {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Text(section.entries.count, format: .number)
                .font(.subheadline.weight(.semibold))
                .monospacedDigit()
                .foregroundStyle(.secondary)
                .contentTransition(.numericText(value: Double(section.entries.count)))
                .accessibilityLabel("\(section.entries.count) journeys")
        }
        .textCase(nil)
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

/// "Times in Sydney · GMT+10" above the filters; tap to learn that times are in the event's zone.
private struct TimeZoneButton: View {
    let tz: String
    @State private var showsInfo = false

    var body: some View {
        let now = Date()
        let differs = TravelLogic.deviceZoneDiffers(tz, now: now)
        Button {
            Haptics.tap()
            showsInfo = true
        } label: {
            HStack(spacing: 5) {
                Image(systemName: differs ? "globe" : "clock")
                Text("Times in \(TravelLogic.zoneLabel(tz, now: now) ?? tz)")
                if differs { Text("· not your time zone") }
                Image(systemName: "info.circle").foregroundStyle(.tertiary)
            }
            .font(.footnote.weight(.medium))
            .foregroundStyle(differs ? AnyShapeStyle(Tone.warning.color) : AnyShapeStyle(.secondary))
            .lineLimit(1)
            .minimumScaleFactor(0.85)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Times shown in \(TravelLogic.zoneLongName(tz, now: now) ?? tz)")
        .popover(isPresented: $showsInfo) {
            VStack(alignment: .leading, spacing: 8) {
                Label("Event Time", systemImage: differs ? "globe" : "clock")
                    .font(.headline)
                Text("Times are shown in the event's time zone, \(TravelLogic.zoneLongName(tz, now: now) ?? tz).")
                if differs {
                    Text("That's different from this device (\(TravelLogic.zoneLabel(TimeZone.current.identifier, now: now) ?? TimeZone.current.identifier)).")
                        .foregroundStyle(Tone.warning.color)
                }
            }
            .font(.subheadline)
            .fixedSize(horizontal: false, vertical: true)
            .padding()
            .frame(width: 300, alignment: .leading)
            .presentationCompactAdaptation(.popover)
        }
    }
}
