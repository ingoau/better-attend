import SwiftUI

/// Everyone with medical or safety needs, from the cached roster (works offline). Medical details and
/// contacts only for roles that may see sensitive data; everyone else sees the flags.
struct FirstAidView: View {
    let eventId: String
    @Environment(AppModel.self) private var app

    private var event: Event? { app.events.events?.first { $0.id == eventId } }

    var body: some View {
        Group {
            if app.events.events == nil {
                ProgressView().controlSize(.large)
            } else if let event, EventPermissions.canViewParticipants(event) {
                FirstAidList(event: event)
            } else {
                RosterToolNoAccessView()
            }
        }
        .navigationTitle("First Aid")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct FirstAidList: View {
    let event: Event
    @Environment(AppModel.self) private var app
    @State private var scope: FirstAidScope = .everyone
    @State private var query = ""
    @State private var syncError: String?
    @State private var loaded = false
    @State private var now = Date()

    var body: some View {
        let roster = app.participants.roster(event.id)
        let people = roster?.participants ?? []
        let sensitive = EventPermissions.canViewSensitiveData(event)
        let entries = FirstAidLogic.entries(people, canViewSensitive: sensitive, scope: scope, query: query)
        let counts = FirstAidLogic.scopeCounts(people, canViewSensitive: sensitive)
        let rosterAt = ScanAdmission.rosterTime(roster)
        let stale = rosterAt != nil && ScanAdmission.isRosterStale(rosterAt, now: now)
        let staleHint = syncError.map { " \($0)" } ?? " Pull down to refresh."

        List {
            if !sensitive {
                Section {
                    NoticeBanner(message: FirstAidLogic.restrictedNotice, systemImage: "lock")
                        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                        .listRowBackground(Color.clear)
                }
            }
            if stale, let age = ScanAdmission.rosterAgeLabel(rosterAt, now: now) {
                Section {
                    NoticeBanner(message: "\(age).\(staleHint)",
                                 systemImage: "clock.arrow.circlepath", tone: .warning,
                                 retry: { Task { await sync() } })
                        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                        .listRowBackground(Color.clear)
                }
            }
            ForEach(entries) { e in
                FirstAidCard(entry: e, eventId: event.id, canViewSensitive: sensitive)
            }
        }
        .listStyle(.insetGrouped)
        .overlay { emptyOverlay(roster: roster, isEmpty: entries.isEmpty, everyone: counts[.everyone] ?? 0, stale: stale, rosterAt: rosterAt) }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Name or ticket code")
        .topBar {
            Picker("Show", selection: $scope) {
                ForEach(FirstAidScope.allCases) { s in
                    Text(roster?.syncedAt != nil ? "\(s.label) \(counts[s] ?? 0)" : s.label).tag(s)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .onChange(of: scope) { Haptics.selection() }
        }
        .refreshable { await sync() }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Print", systemImage: "printer") {
                    Haptics.tap()
                    let html = FirstAidLogic.html(eventName: event.name, entries: entries, canViewSensitive: sensitive, scope: scope,
                                                  tz: event.timezone, rosterAt: rosterAt)
                    HTMLPrinter.present(html: html, jobName: FirstAidLogic.title(eventName: event.name))
                }
                .disabled(entries.isEmpty)
            }
        }
        .task(id: event.id) {
            await app.participants.load(event.id)
            loaded = true
            await sync()
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                now = Date()
            }
        }
    }

    private func sync() async {
        do {
            try await app.participants.sync(event.id)
            syncError = nil
        } catch where !error.isCancellation {
            syncError = error.friendlyMessage
        } catch {}
    }

    @ViewBuilder
    private func emptyOverlay(roster: Roster?, isEmpty: Bool, everyone: Int, stale: Bool, rosterAt: String?) -> some View {
        if isEmpty {
            if roster == nil || roster?.syncedAt == nil {
                if let syncError, loaded {
                    ContentUnavailableView {
                        Label("Couldn't Load People", systemImage: "wifi.exclamationmark")
                    } description: {
                        Text(syncError)
                    } actions: {
                        Button("Try Again") { Task { await sync() } }
                            .buttonStyle(.borderedProminent)
                    }
                } else {
                    ProgressView("Loading People…").controlSize(.large)
                }
            } else if !query.isBlank {
                ContentUnavailableView.search(text: query)
            } else {
                ContentUnavailableView {
                    Label("No Medical or Safety Flags", systemImage: "cross.case")
                } description: {
                    if scope == .hereNow && everyone > 0 {
                        Text("No one here right now has medical or safety flags. Choose Everyone to see people who haven't arrived.")
                    } else if stale, let age = ScanAdmission.rosterAgeLabel(rosterAt, now: now) {
                        Text("No one has medical or safety flags. \(age).")
                    } else {
                        Text("No one has medical or safety flags.")
                    }
                }
            }
        }
    }
}

/// One person: a header that opens their profile (with the emergency action plan), then medical
/// details and contacts when the viewer may see them.
private struct FirstAidCard: View {
    let entry: FirstAidEntry
    let eventId: String
    let canViewSensitive: Bool

    var body: some View {
        Section {
            NavigationLink(value: Route.participant(eventId: eventId, participantEventId: entry.id)) {
                FirstAidHeader(entry: entry)
            }
            if canViewSensitive {
                ForEach(entry.medicalLines) { line in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(line.label)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(line.urgent ? Tone.danger.color : Color.secondary)
                        Text(line.text)
                            .font(.body)
                            .textSelection(.enabled)
                    }
                    .accessibilityElement(children: .combine)
                }
                ForEach(Array(entry.contacts.enumerated()), id: \.offset) { _, contact in
                    FirstAidContactRow(contact: contact)
                }
            }
        }
    }
}

private struct FirstAidHeader: View {
    let entry: FirstAidEntry

    var body: some View {
        let p = entry.participant
        HStack(alignment: .top, spacing: 12) {
            Avatar(name: p.fullName ?? p.name, url: p.headshotUrl, size: 44)
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(PeopleText.listTitle(p))
                        .font(.headline)
                        .lineLimit(2)
                    if let pronouns = p.pronouns?.nonBlank {
                        Text(pronouns)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                FlowLayout(spacing: 6, lineSpacing: 6) {
                    Pill(text: entry.isHere ? "Here" : "Not here", tone: entry.isHere ? .success : .neutral,
                         systemImage: entry.isHere ? "checkmark.circle.fill" : "circle.dashed")
                    ForEach(entry.flags, id: \.label) { f in
                        Pill(text: f.label, tone: f.danger ? .danger : .warning, systemImage: f.systemImage)
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

private struct FirstAidContactRow: View {
    let contact: FirstAidContact
    @Environment(\.openURL) private var openURL

    var body: some View {
        let detail = [contact.relationship, contact.phone].compactMap { $0 }.joined(separator: " · ")
        HStack(spacing: 12) {
            Image(systemName: "person.crop.circle.badge.exclamationmark")
                .font(.title3)
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(contact.name).font(.body.weight(.medium))
                if !detail.isEmpty {
                    Text(detail).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 8)
            if let phone = contact.phone, let url = ContactLinks.call(phone) {
                Button {
                    Haptics.tap()
                    openURL(url)
                } label: {
                    Label("Call", systemImage: "phone.fill")
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .tint(Tone.success.color)
                .accessibilityLabel("Call \(contact.name)")
            } else if let email = contact.email, let url = ContactLinks.email(email) {
                Button {
                    Haptics.tap()
                    openURL(url)
                } label: {
                    Label("Email", systemImage: "envelope.fill")
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .accessibilityLabel("Email \(contact.name)")
            }
        }
    }
}
