import SwiftUI

enum EventPicker {
    /// Live first, then upcoming (soonest first), then past (most recent first), then undated.
    static func ordered(_ events: [Event], now: Date = Date()) -> [Event] {
        sections(events, now: now).flatMap(\.events)
    }

    static func sections(_ events: [Event], now: Date = Date()) -> [(title: String, events: [Event])] {
        func phase(_ e: Event) -> Time.Phase { Time.phase(e.startsAt, e.endsAt, now: now) }
        func start(_ e: Event) -> Date { Time.parse(e.startsAt) ?? .distantPast }
        return [
            ("Happening Now", events.filter { phase($0) == .live }),
            ("Upcoming", events.filter { phase($0) == .upcoming }.sorted { start($0) < start($1) }),
            ("Past", events.filter { phase($0) == .past }.sorted { start($0) > start($1) }),
            ("Undated", events.filter { phase($0) == .unknown }),
        ].filter { !$0.1.isEmpty }
    }

    /// "Sat 3 – Sun 4 Oct · Sydney"
    static func subtitle(_ e: Event) -> String {
        [Time.range(e.startsAt, e.endsAt, tz: e.timezone), e.locationCity?.nonBlank].compactMap { $0 }.joined(separator: " · ")
    }
}

/// Full list of the organizer's events, searchable and grouped by when they happen.
struct EventPickerSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var filtered: [Event] {
        let events = app.events.events ?? []
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return events }
        return events.filter { $0.name.localizedCaseInsensitiveContains(q) || ($0.locationCity ?? "").localizedCaseInsensitiveContains(q) }
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(EventPicker.sections(filtered), id: \.title) { section in
                    Section(section.title) {
                        ForEach(section.events) { e in
                            Button {
                                Haptics.selection()
                                app.events.select(e.id)
                                dismiss()
                            } label: {
                                EventRow(event: e, selected: e.id == app.events.selectedEvent?.id, live: section.title == "Happening Now")
                            }
                            .tint(.primary)
                        }
                    }
                }
            }
            .overlay {
                if (app.events.events ?? []).isEmpty {
                    ContentUnavailableView("No Events Yet", systemImage: "calendar.badge.exclamationmark",
                                           description: Text("Ask an event admin to add you as staff on attend.hackclub.com."))
                } else if filtered.isEmpty {
                    ContentUnavailableView.search(text: query)
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search events")
            .navigationTitle("Choose Event")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .refreshable { _ = try? await app.events.refresh() }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct EventRow: View {
    let event: Event
    let selected: Bool
    let live: Bool

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(event.name).font(.body.weight(.semibold))
                    if live { Pill(text: "Live", tone: .success) }
                }
                Text(EventPicker.subtitle(event))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let role = EventLogic.roleLabel(event.role) {
                    Text(role).font(.caption).foregroundStyle(.tertiary)
                }
            }
            Spacer()
            if selected {
                Image(systemName: "checkmark.circle.fill")
                    .font(.title3)
                    .foregroundStyle(.tint)
                    .accessibilityLabel("Selected")
            }
        }
        .contentShape(.rect)
    }
}
