import SwiftUI

/// Roll call (muster): a headcount against a list frozen from the cached roster. Works offline; can
/// also record each tick as a scan at a scan point the user picks. One roll call per event, kept in
/// the encrypted cache so it survives the app closing.
struct RollCallView: View {
    let eventId: String
    @Environment(AppModel.self) private var app
    @State private var model = RollCallModel()
    /// The saved roll call (if any) has been read from disk.
    @State private var restored = false

    private var event: Event? { app.events.events?.first { $0.id == eventId } }

    var body: some View {
        content
            .navigationTitle("Roll Call")
            .navigationBarTitleDisplayMode(.inline)
            .toast($model.toast)
            .task(id: eventId) {
                await app.rollCalls.load(eventId)
                await app.participants.load(eventId)
                restored = true
            }
    }

    @ViewBuilder
    private var content: some View {
        if app.events.events == nil || !restored {
            ProgressView().controlSize(.large)
        } else if let event, EventPermissions.canViewParticipants(event) {
            if let session = app.rollCalls.session(event.id) {
                RollCallActiveView(event: event, session: session, model: model)
            } else {
                RollCallSetupView(event: event)
            }
        } else {
            RosterToolNoAccessView()
        }
    }
}

/// Shown instead of a roster tool (roll call, first aid) opened without access, e.g. from a deep link.
struct RosterToolNoAccessView: View {
    var body: some View {
        ContentUnavailableView {
            Label("You Don't Have Access to This", systemImage: "lock")
        } description: {
            Text("Your role on this event doesn't include the people list, so this isn't available.")
        }
    }
}

// MARK: - Setup

private struct RollCallSetupView: View {
    let event: Event
    @Environment(AppModel.self) private var app
    @State private var expected: RollCallExpected = .checkedIn
    /// nil = only on this phone. Never preselected: a scan point is only used if the user picks it.
    @State private var scanPointId: String?
    @State private var contextsLoading = false
    @State private var contextsError: String?
    @State private var syncError: String?

    var body: some View {
        let roster = app.participants.roster(event.id)
        let people = roster?.participants ?? []
        let ready = roster?.syncedAt != nil
        let count = RollCallLogic.expectedCount(people, expected: expected)

        Form {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Label("Count Heads", systemImage: "checklist")
                        .font(.headline)
                    Text("Tick people off as you find them. The list is fixed when you start, so late check-ins don't move the goalposts, and it's saved on this phone in case the app closes.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }

            Section {
                ForEach(RollCallExpected.allCases) { option in
                    ChoiceRow(title: option.label,
                              subtitle: option.detail,
                              systemImage: option == .checkedIn ? "person.crop.circle.badge.checkmark" : "person.3",
                              trailing: ready ? "\(RollCallLogic.expectedCount(people, expected: option))" : nil,
                              selected: expected == option) {
                        expected = option
                    }
                }
            } header: {
                Text("Who's Expected")
            }

            // Scanning is open to every organizer role (it's how the Scan tab decides too).
            if app.isOrganizer {
                recordSection
            }

            if !ready {
                Section {
                    if let syncError {
                        VStack(alignment: .leading, spacing: 8) {
                            Label("Couldn't load the people list", systemImage: "wifi.exclamationmark")
                                .font(.subheadline.weight(.semibold))
                            Text(syncError).font(.footnote).foregroundStyle(.secondary)
                            Button("Try Again") { Haptics.tap(); Task { await syncRoster() } }
                        }
                    } else {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Loading the people list…").foregroundStyle(.secondary)
                        }
                    }
                }
            }

            Section {
                Button {
                    start(people: people)
                } label: {
                    Text("Start Roll Call")
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(!ready || count == 0)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            } footer: {
                if ready && count == 0 {
                    Text(expected == .checkedIn
                         ? "No one has checked in yet. Choose Everyone registered to count everyone."
                         : "No one is registered yet.")
                } else if ready, let age = ScanAdmission.rosterAgeLabel(ScanAdmission.rosterTime(roster)) {
                    Text("\(count) \(count == 1 ? "person" : "people") · \(age)")
                }
            }
        }
        .task(id: event.id) {
            await syncRoster()
            await loadContexts()
        }
    }

    @ViewBuilder
    private var recordSection: some View {
        let contexts = (app.events.cachedContexts(event.id) ?? []).sorted { $0.position < $1.position }
        let selected = contexts.first { $0.id == scanPointId }
        Section {
            ChoiceRow(title: "Only on this phone", subtitle: "Nothing is sent. Works offline.", systemImage: "iphone",
                      selected: scanPointId == nil) {
                scanPointId = nil
            }
            ForEach(contexts) { c in
                ChoiceRow(title: c.name, subtitle: c.purpose.map { "Record as a scan · \($0)" } ?? "Record as a scan",
                          systemImage: c.systemImage, selected: scanPointId == c.id) {
                    scanPointId = c.id
                }
            }
            if contexts.isEmpty {
                if contextsLoading {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Loading scan points…").foregroundStyle(.secondary)
                    }
                } else if let contextsError {
                    Text("Couldn't load scan points: \(contextsError)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        } header: {
            Text("Record Ticks")
        } footer: {
            if let selected {
                Text("Each tick is recorded as a scan at \(selected.name), and waits to sync if you're offline. Unticking someone takes back the scan the roll call made.")
            } else if !contexts.isEmpty {
                Text("To also record ticks in Attend, choose a scan point.")
            }
        }
    }

    private func syncRoster() async {
        do {
            try await app.participants.sync(event.id)
            syncError = nil
        } catch where !error.isCancellation {
            syncError = error.friendlyMessage
        } catch {}
    }

    private func loadContexts() async {
        await app.events.loadContexts(event.id)
        contextsLoading = true
        defer { contextsLoading = false }
        do {
            try await app.events.refreshContexts(event.id)
            contextsError = nil
        } catch where !error.isCancellation {
            contextsError = error.friendlyMessage
        } catch {}
    }

    private func start(people: [Participant]) {
        let contexts = app.events.cachedContexts(event.id) ?? []
        var point: RollCallScanPoint?
        if let scanPointId {
            // Only ever the scan point the user picked; if it's gone, make them choose again.
            guard let picked = contexts.first(where: { $0.id == scanPointId }) else {
                self.scanPointId = nil
                Haptics.reject()
                return
            }
            point = RollCallScanPoint(picked)
        }
        Haptics.confirm()
        withAnimation(.smooth) {
            app.rollCalls.start(RollCallLogic.start(eventId: event.id, participants: people, expected: expected, scanPoint: point))
        }
    }
}

/// A selectable row with a checkmark, for single-choice lists in a Form.
private struct ChoiceRow: View {
    let title: String
    var subtitle: String?
    let systemImage: String
    var trailing: String?
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button {
            Haptics.selection()
            action()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(selected ? Color.accentColor : Color.secondary)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(.primary)
                    if let subtitle {
                        Text(subtitle).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 8)
                if let trailing {
                    Text(trailing)
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Image(systemName: "checkmark")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Color.accentColor)
                    .opacity(selected ? 1 : 0)
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

// MARK: - In progress

private struct RollCallActiveView: View {
    let event: Event
    let session: RollCallSession
    @Bindable var model: RollCallModel
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router

    var body: some View {
        let roster = app.participants.roster(event.id)?.participants ?? []
        let entries = RollCallLogic.entries(session, roster: roster)
        let counts = RollCallLogic.counts(entries)
        let shown = RollCallLogic.filter(entries, filter: model.filter, query: model.query)
        let filterCounts = RollCallLogic.filterCounts(entries, query: model.query)
        let pending = session.scanPoint.map { point in
            app.scans.pending.count(where: { $0.eventId == event.id && $0.scanContextId == point.id })
        } ?? 0

        List {
            Section {
                RollCallCounterCard(counts: counts, session: session, timezone: event.timezone, pendingScans: pending)
                    .listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 12, trailing: 16))
            }
            Section {
                ForEach(shown) { e in
                    row(e)
                }
                if shown.isEmpty {
                    emptyState(searching: !model.query.isBlank)
                        .padding(.vertical, 24)
                        .listRowSeparator(.hidden)
                }
            }
        }
        .listStyle(.plain)
        .animation(.smooth, value: shown.map(\.id))
        .searchable(text: $model.query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Name or ticket code")
        .topBar {
            Picker("Show", selection: $model.filter) {
                ForEach(RollCallFilter.allCases) { f in
                    Text("\(f.label) \(filterCounts[f] ?? 0)").tag(f)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .onChange(of: model.filter) { Haptics.selection() }
        }
        .refreshable { _ = try? await app.participants.sync(event.id) }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Add Someone", systemImage: "person.badge.plus") {
                    Haptics.tap()
                    model.showAdd = true
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Finish") {
                    Haptics.tap()
                    model.confirmFinish = true
                }
                .fontWeight(.semibold)
            }
        }
        .confirmationDialog("Finish the roll call?", isPresented: $model.confirmFinish, titleVisibility: .visible) {
            Button("Finish") { model.showSummary = true }
            Button("Keep Counting", role: .cancel) {}
        } message: {
            Text(counts.missing == 0 ? "Everyone is accounted for." : "\(counts.missing) still missing. You'll see who before ending it.")
        }
        .sheet(isPresented: $model.showAdd) {
            RollCallAddSheet(event: event, model: model)
        }
        .sheet(isPresented: $model.showSummary) {
            RollCallSummarySheet(event: event, session: session, entries: entries) {
                model.showSummary = false
                model.end(app, eventId: event.id)
                router.back()
            }
        }
    }

    @ViewBuilder
    private func row(_ e: RollCallEntry) -> some View {
        Button {
            model.toggle(app, event: event, e)
        } label: {
            RollCallRow(entry: e, scanPointName: session.scanPoint?.name, timezone: event.timezone)
        }
        .buttonStyle(.plain)
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            Button {
                model.toggle(app, event: event, e)
            } label: {
                if e.accounted {
                    Label("Missing", systemImage: "circle")
                } else {
                    Label("Here", systemImage: "checkmark.circle")
                }
            }
            .tint(e.accounted ? Color.orange : Tone.success.color)
        }
        .contextMenu {
            Button(e.accounted ? "Mark Missing" : "Mark Accounted For", systemImage: e.accounted ? "circle" : "checkmark.circle") {
                model.toggle(app, event: event, e)
            }
            if e.known {
                Button("Open Profile", systemImage: "person.crop.circle") {
                    router.openParticipant(eventId: event.id, participantEventId: e.id)
                }
            }
        }
    }

    @ViewBuilder
    private func emptyState(searching: Bool) -> some View {
        if searching {
            ContentUnavailableView.search(text: model.query)
        } else {
            ContentUnavailableView {
                Label(model.filter.emptyTitle, systemImage: model.filter == .missing ? "checkmark.circle" : "checklist")
            } description: {
                if model.filter == .missing {
                    Text("Tap Finish to wrap up.")
                }
            }
        }
    }
}

/// "37 / 52 accounted for · 15 missing" with a progress bar and where ticks are going.
private struct RollCallCounterCard: View {
    let counts: RollCallCounts
    let session: RollCallSession
    let timezone: String?
    let pendingScans: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text("\(counts.accounted)")
                    .font(.stat(44))
                    .contentTransition(.numericText(value: Double(counts.accounted)))
                Text("/ \(counts.total) accounted for")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .combine)
            ProgressView(value: counts.progress)
                .tint(counts.isComplete ? Tone.success.color : Color.accentColor)
                .animation(.smooth, value: counts.progress)
                .accessibilityHidden(true)
            HStack(spacing: 8) {
                if counts.missing == 0 {
                    Label("Everyone's accounted for", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(Tone.success.color)
                } else {
                    Label("\(counts.missing) missing", systemImage: "person.fill.questionmark")
                        .foregroundStyle(Tone.warning.color)
                        .contentTransition(.numericText(value: Double(counts.missing)))
                }
                Spacer(minLength: 0)
                if counts.added > 0 {
                    Text("+\(counts.added) added")
                        .foregroundStyle(.secondary)
                }
            }
            .font(.subheadline.weight(.semibold))
            HStack(spacing: 6) {
                Image(systemName: session.scanPoint == nil ? "iphone" : "mappin.and.ellipse")
                Text(recordingLine)
                    .lineLimit(2)
                Spacer(minLength: 0)
                if pendingScans > 0 {
                    Pill(text: "\(pendingScans) to sync", tone: .warning, systemImage: "icloud.and.arrow.up")
                }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemBackground), in: .rect(cornerRadius: 22))
        .animation(.smooth, value: counts)
    }

    private var recordingLine: String {
        let started = Time.time(session.startedAt, tz: timezone).map { "Started \($0)" }
        let destination = session.scanPoint.map { "Recording scans at \($0.name)" } ?? "Only on this phone"
        return [started, destination].compactMap { $0 }.joined(separator: " · ")
    }
}

private struct RollCallRow: View {
    let entry: RollCallEntry
    let scanPointName: String?
    let timezone: String?

    var body: some View {
        let p = entry.participant
        HStack(spacing: 12) {
            Avatar(name: p.fullName ?? p.name, url: p.headshotUrl, size: 44)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(PeopleText.listTitle(p))
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                        .layoutPriority(1)
                    if let pronouns = p.pronouns?.nonBlank {
                        Text(pronouns)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    SafetyIcons(participant: p)
                }
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(entry.stillRecorded && !entry.accounted ? Tone.warning.color : Color.secondary)
                    .lineLimit(1)
                    .contentTransition(.opacity)
            }
            Spacer(minLength: 0)
            Image(systemName: entry.accounted ? "checkmark.circle.fill" : "circle")
                .font(.title2)
                .foregroundStyle(entry.accounted ? Tone.success.color : Color.secondary)
                .contentTransition(.symbolEffect(.replace))
        }
        .padding(.vertical, 2)
        .contentShape(.rect)
        .animation(.smooth, value: entry.accounted)
        .accessibilityElement(children: .combine)
        .accessibilityValue(entry.accounted ? "Accounted for" : "Missing")
        .accessibilityHint(entry.accounted ? "Marks them missing" : "Marks them accounted for")
    }

    private var subtitle: String {
        let added = entry.added ? "Added" : nil
        if entry.accounted {
            let ticked = Time.time(entry.accountedAt, tz: timezone).map { "Ticked \($0)" } ?? "Ticked"
            return [ticked, added].compactMap { $0 }.joined(separator: " · ")
        }
        if entry.stillRecorded, let scanPointName { return "Missing · scan still recorded at \(scanPointName)" }
        if !entry.known { return "No longer in the people list" }
        return PeopleFilter.checkInLine(entry.participant, tz: timezone) ?? StatusVisual.of(entry.participant).label
    }
}

// MARK: - Add someone

/// Search the whole roster and mark someone present, even if they weren't on the expected list.
private struct RollCallAddSheet: View {
    let event: Event
    let model: RollCallModel
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        let roster = app.participants.roster(event.id)?.participants ?? []
        let session = app.rollCalls.session(event.id)
        let candidates = session.map { RollCallLogic.addCandidates(roster, session: $0, query: query) } ?? []
        NavigationStack {
            List(candidates) { p in
                Button {
                    model.add(app, event: event, p)
                    dismiss()
                } label: {
                    HStack(spacing: 12) {
                        Avatar(name: p.fullName ?? p.name, url: p.headshotUrl, size: 40)
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 6) {
                                Text(PeopleText.listTitle(p))
                                    .font(.body.weight(.semibold))
                                    .foregroundStyle(.primary)
                                    .lineLimit(1)
                                SafetyIcons(participant: p)
                            }
                            Text(session?.isOnList(p.participantEventId) == true ? "On the list · missing" : "Not on the list")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 0)
                        Image(systemName: "plus.circle.fill")
                            .font(.title3)
                            .foregroundStyle(Color.accentColor)
                    }
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
            }
            .listStyle(.plain)
            .overlay {
                if candidates.isEmpty {
                    if query.isBlank {
                        ContentUnavailableView("Everyone's Accounted For", systemImage: "checkmark.circle",
                                               description: Text("There's no one left in the people list to add."))
                    } else {
                        ContentUnavailableView.search(text: query)
                    }
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search everyone registered")
            .navigationTitle("Add Someone")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
    }
}

// MARK: - Summary

private struct RollCallSummarySheet: View {
    let event: Event
    let session: RollCallSession
    let entries: [RollCallEntry]
    let onEnd: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var confirmEnd = false

    var body: some View {
        let counts = RollCallLogic.counts(entries)
        let missing = entries.filter { !$0.accounted }
        let added = entries.filter { $0.added && $0.accounted }
        let shareText = RollCallLogic.missingShareText(eventName: event.name, session: session, entries: entries, tz: event.timezone)
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(RollCallLogic.headline(counts))
                            .font(.headline)
                        ProgressView(value: counts.progress)
                            .tint(counts.isComplete ? Tone.success.color : Color.accentColor)
                        if let point = session.scanPoint {
                            Text("Ticks were recorded as scans at \(point.name). Ending the roll call keeps those scans.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }
                Section {
                    if missing.isEmpty {
                        Label("Everyone is accounted for", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(Tone.success.color)
                    }
                    ForEach(missing) { e in
                        summaryRow(e)
                    }
                } header: {
                    Text("Missing (\(missing.count))")
                }
                if !added.isEmpty {
                    Section {
                        ForEach(added) { e in summaryRow(e) }
                    } header: {
                        Text("Added During Roll Call (\(added.count))")
                    }
                }
                Section {
                    Button("End Roll Call", role: .destructive) {
                        Haptics.tap()
                        confirmEnd = true
                    }
                } footer: {
                    Text("Clears the roll call from this phone.")
                }
            }
            .navigationTitle("Roll Call Summary")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Keep Counting") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    ShareLink(item: shareText) {
                        Label("Share Missing List", systemImage: "square.and.arrow.up")
                    }
                }
            }
            .confirmationDialog("End this roll call?", isPresented: $confirmEnd, titleVisibility: .visible) {
                Button("End Roll Call", role: .destructive) { onEnd() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(missing.isEmpty ? "Everyone was accounted for." : "\(missing.count) \(missing.count == 1 ? "person is" : "people are") still missing. Share the list first if you need it.")
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func summaryRow(_ e: RollCallEntry) -> some View {
        let p = e.participant
        return HStack(spacing: 12) {
            Avatar(name: p.fullName ?? p.name, url: p.headshotUrl, size: 36)
            VStack(alignment: .leading, spacing: 1) {
                Text(PeopleText.listTitle(p)).font(.body.weight(.medium))
                if let pronouns = p.pronouns?.nonBlank {
                    Text(pronouns).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
            SafetyIcons(participant: p)
        }
        .accessibilityElement(children: .combine)
    }
}
