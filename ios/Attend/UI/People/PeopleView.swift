import SwiftUI

/// The People tab: everyone registered for the selected event, searchable and filterable, with
/// swipe-to-check-in. Tapping a person opens their detail, which can page through this same list.
struct PeopleView: View {
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @State private var model = PeopleModel()
    @State private var showFilters = false
    @State private var now = Date()

    private var event: Event? { app.events.selectedEvent }

    var body: some View {
        content
            .eventToolbar(fallbackTitle: "People")
            // Inline title: the pinned filter bar's scroll-edge blur would sit over a large title.
            .navigationBarTitleDisplayMode(.inline)
            .modifier(UpdatedSubtitle(text: updatedText))
            .toast($model.toast)
            .onChange(of: event?.id, initial: true) { _, id in model.bind(eventId: id) }
            .task(id: event?.id) {
                guard let event, event.canViewParticipants else { return }
                await app.participants.load(event.id)
                await app.events.loadContexts(event.id)
                _ = try? await app.events.refreshContexts(event.id)
            }
            // Delta-sync on open and every minute while the list is on screen.
            .poll(every: .seconds(60), id: event?.id) {
                if let event { await model.sync(app, event: event) }
            }
            .task {
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(30))
                    now = Date()
                }
            }
    }

    private var updatedText: String? {
        guard let event, event.canViewParticipants else { return nil }
        if let last = app.participants.roster(event.id)?.lastSyncAt, let ago = Time.ago(last, now: now) {
            return "Updated \(ago)"
        }
        return app.participants.syncing.contains(event.id) ? "Syncing…" : nil
    }

    @ViewBuilder
    private var content: some View {
        if app.events.events == nil {
            ProgressView().controlSize(.large)
        } else if let event {
            if !event.canViewParticipants {
                ContentUnavailableView {
                    Label("People List Unavailable", systemImage: "person.2.slash")
                } description: {
                    Text("Your role on \(event.name) is scan-only, so participant details are hidden. You can still check people in by scanning their ticket or badge.")
                } actions: {
                    Button("Open Scanner") { router.switchTab(.scan) }
                        .buttonStyle(.borderedProminent)
                }
            } else {
                roster(for: event)
            }
        } else {
            ContentUnavailableView {
                Label("No Event Selected", systemImage: "calendar.badge.exclamationmark")
            } description: {
                Text("Choose an event to see who's registered and who's arrived.")
            } actions: {
                Button("Choose Event") { router.sheet = .eventPicker }
                    .buttonStyle(.borderedProminent)
            }
        }
    }

    @ViewBuilder
    private func roster(for event: Event) -> some View {
        let roster = app.participants.roster(event.id)
        let syncing = app.participants.syncing.contains(event.id)
        let people = roster?.participants ?? []
        if people.isEmpty && model.syncError == nil && (roster == nil || roster?.syncedAt == nil || syncing) {
            ProgressView("Loading People…").controlSize(.large)
        } else if people.isEmpty, let error = model.syncError {
            ContentUnavailableView {
                Label("Couldn't Load People", systemImage: "wifi.exclamationmark")
            } description: {
                Text(error)
            } actions: {
                Button("Try Again") { Task { await model.sync(app, event: event) } }
                    .buttonStyle(.borderedProminent)
            }
        } else {
            PeopleList(event: event, roster: roster, model: model, showFilters: $showFilters, updatedText: updatedText)
                .sheet(isPresented: $showFilters) {
                    PeopleFilterSheet(
                        event: event,
                        people: people,
                        contexts: app.events.cachedContexts(event.id) ?? [],
                        model: model
                    )
                }
        }
    }
}

/// "Updated 2 min ago" under the title on iOS 26; older systems show it in the list instead.
private struct UpdatedSubtitle: ViewModifier {
    let text: String?

    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            content.navigationSubtitle(text ?? "")
        } else {
            content
        }
    }
}

// MARK: - List

private struct PeopleList: View {
    let event: Event
    let roster: Roster?
    @Bindable var model: PeopleModel
    @Binding var showFilters: Bool
    let updatedText: String?
    @Environment(AppModel.self) private var app

    var body: some View {
        let people = roster?.participants ?? []
        let complete = roster?.syncedAt != nil
        let canSensitive = event.canViewSensitiveData
        let result = PeopleFilter.apply(people, query: model.query, quick: model.quick, options: model.options, sort: model.sort,
                                        canViewSensitive: canSensitive)
        let sections = PeopleFilter.sections(result.participants, model.sort)
        let order = result.participants.map(\.participantEventId)
        let searching = !model.query.isBlank
        let showRemote = result.participants.isEmpty && model.query.trimmingCharacters(in: .whitespaces).count >= 2

        List {
            if let error = model.syncError {
                Section {
                    NoticeBanner(message: "Showing the saved list. \(error)", retry: { Task { await model.sync(app, event: event) } })
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                }
            }
            if !searching && !people.isEmpty && model.quick == .all && model.options.activeCount == 0 {
                Section {
                    Group {
                        if complete {
                            SummaryCard(stats: EventStats.from(people), counts: result.counts, footnote: legacyUpdated) { select($0) }
                        } else {
                            PartialRosterCard(known: people.count, failed: model.syncError != nil)
                        }
                    }
                    .listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 12, trailing: 16))
                }
            }
            ForEach(sections) { section in
                Section {
                    ForEach(section.participants) { p in
                        row(p, order: order)
                    }
                } header: {
                    if let letter = section.letter { Text(letter) }
                }
                .modifier(SectionIndexLabel(letter: section.letter))
            }
            if showRemote {
                remoteSection
            }
        }
        .listStyle(.plain)
        .modifier(SectionIndexVisible(visible: model.sort == .name && sections.count > 4))
        .animation(.smooth, value: order)
        .overlay {
            if result.participants.isEmpty && !(showRemote && (model.remoteSearching || !(model.remoteResults ?? []).isEmpty)) {
                emptyState(people: people, searching: searching)
            }
        }
        .searchable(text: $model.query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Name, email or ticket code")
        .task(id: model.query) { await model.remoteSearch(app, eventId: event.id, roster: people) }
        .refreshable { await model.sync(app, event: event) }
        .topBar {
            QuickFilterBar(model: model, counts: complete ? result.counts : nil, showFilters: $showFilters) { select($0) }
        }
    }

    /// On iOS 18 the "Updated … ago" line lives in the summary card.
    private var legacyUpdated: String? {
        if #available(iOS 26, *) { return nil }
        return updatedText
    }

    private func select(_ f: QuickFilter) {
        guard f != model.quick else { return }
        Haptics.selection()
        withAnimation(.smooth) { model.quick = f }
    }

    @ViewBuilder
    private func row(_ p: Participant, order: [String]) -> some View {
        NavigationLink(value: Route.participant(eventId: event.id, participantEventId: p.participantEventId, browseIds: order)) {
            PersonRow(participant: p, timezone: event.timezone, busy: model.busy.contains(p.id))
        }
        .swipeActions(edge: .leading, allowsFullSwipe: !p.isCheckedIn) {
            if p.isCheckedIn {
                Button { Task { await model.undoCheckIn(app, event: event, p) } } label: {
                    Label("Undo", systemImage: "arrow.uturn.backward")
                }
                .tint(.orange)
            } else if p.isActive {
                Button { Task { await model.checkIn(app, event: event, p) } } label: {
                    Label("Check In", systemImage: "checkmark.circle")
                }
                .tint(Tone.success.color)
            }
        }
        .contextMenu { PersonMenu(participant: p, event: event, model: model) }
    }

    @ViewBuilder
    private var remoteSection: some View {
        if model.remoteSearching {
            HStack(spacing: 10) {
                ProgressView()
                Text("Searching all of Attend…").foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 24)
            .listRowSeparator(.hidden)
        } else if let results = model.remoteResults, !results.isEmpty {
            let order = results.map(\.participantEventId)
            Section {
                ForEach(results) { p in row(p, order: order) }
            } header: {
                Text("Found on Attend")
            } footer: {
                Text("Not in the saved list yet. Pull to refresh to sync everyone.")
            }
        }
    }

    @ViewBuilder
    private func emptyState(people: [Participant], searching: Bool) -> some View {
        let q = model.query.trimmingCharacters(in: .whitespacesAndNewlines)
        let filtered = model.quick != .all || model.options.activeCount > 0
        if people.isEmpty {
            ContentUnavailableView {
                Label("No One's Registered Yet", systemImage: "person.crop.circle.badge.questionmark")
            } description: {
                Text("Pull down to refresh once invitations go out.")
            }
        } else if searching {
            ContentUnavailableView {
                Label("No Results for “\(q)”", systemImage: "magnifyingglass")
            } description: {
                if let error = model.remoteError {
                    Text("Couldn't search Attend: \(error)")
                } else {
                    Text(q.count < 2 ? "Keep typing to search everyone registered." : "Check the spelling, or try their email or ticket code.")
                }
            } actions: {
                if filtered {
                    Button("Search Everyone") { Haptics.tap(); withAnimation { model.quick = .all; model.options = FilterOptions() } }
                }
            }
        } else {
            ContentUnavailableView {
                Label(model.quick.emptyTitle, systemImage: "line.3.horizontal.decrease.circle")
            } description: {
                if model.options.activeCount > 0 { Text("Try removing a filter.") }
            } actions: {
                Button("Show Everyone") { Haptics.tap(); withAnimation { model.clearAll() } }
            }
        }
    }
}

private struct SectionIndexLabel: ViewModifier {
    let letter: String?

    func body(content: Content) -> some View {
        if #available(iOS 26, *), let letter {
            content.sectionIndexLabel(Text(letter))
        } else {
            content
        }
    }
}

private struct SectionIndexVisible: ViewModifier {
    let visible: Bool

    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            content.listSectionIndexVisibility(visible ? .visible : .hidden)
        } else {
            content
        }
    }
}

extension View {
    /// A bar pinned under the navigation bar (chips, filters). On iOS 26 it gets the scroll edge effect.
    @ViewBuilder
    func topBar<Bar: View>(@ViewBuilder _ bar: () -> Bar) -> some View {
        if #available(iOS 26, *) {
            safeAreaBar(edge: .top) { bar() }
        } else {
            safeAreaInset(edge: .top, spacing: 0) {
                bar()
                    .background(.bar)
                    .overlay(alignment: .bottom) { Divider() }
            }
        }
    }
}

// MARK: - Row

struct PersonRow: View {
    let participant: Participant
    let timezone: String?
    var busy = false

    var body: some View {
        let p = participant
        let v = StatusVisual.of(p)
        HStack(spacing: 12) {
            Avatar(name: p.fullName ?? p.name, url: p.headshotUrl, size: 44)
                .opacity(p.isActive ? 1 : 0.5)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(PeopleText.listTitle(p))
                        .font(.body.weight(.semibold))
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
                HStack(spacing: 5) {
                    if busy {
                        ProgressView().controlSize(.mini)
                    } else {
                        Image(systemName: v.systemImage)
                            .foregroundStyle(v.kind == .here ? Tone.success.color : v.kind.tone == .neutral ? Color.secondary : v.kind.tone.color)
                            .imageScale(.small)
                    }
                    Text(PeopleFilter.checkInLine(p, tz: timezone) ?? v.label)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .contentTransition(.opacity)
                }
                .font(.subheadline)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
        .animation(.smooth, value: p.isCheckedIn)
        .accessibilityElement(children: .combine)
    }
}

/// Long-press menu on a row.
private struct PersonMenu: View {
    let participant: Participant
    let event: Event
    let model: PeopleModel
    @Environment(AppModel.self) private var app
    @Environment(\.openURL) private var openURL

    var body: some View {
        let p = participant
        Section {
            if p.isCheckedIn {
                Button("Undo Check-In", systemImage: "arrow.uturn.backward") { Task { await model.undoCheckIn(app, event: event, p) } }
            } else if p.isActive {
                Button("Check In", systemImage: "checkmark.circle") { Task { await model.checkIn(app, event: event, p) } }
            }
        }
        if event.canViewParticipantPii, let phone = p.phone?.nonBlank {
            Section {
                if let url = ContactLinks.call(phone) { Button("Call", systemImage: "phone") { openURL(url) } }
                if let url = ContactLinks.sms(phone) { Button("Message", systemImage: "message") { openURL(url) } }
            }
        }
        if event.canViewParticipantPii, let email = p.email?.nonBlank, let url = ContactLinks.email(email) {
            Button("Email", systemImage: "envelope") { openURL(url) }
        }
        Section {
            Button("Copy Ticket Code", systemImage: "doc.on.doc") {
                Clipboard.copy(p.shortCode)
                model.toast = .info("Copied \(p.shortCode)", systemImage: "doc.on.doc.fill")
            }
        }
    }
}

// MARK: - Chips

private struct QuickFilterBar: View {
    @Bindable var model: PeopleModel
    /// nil while the roster is only partially loaded, so chips don't show misleading numbers.
    let counts: [QuickFilter: Int]?
    @Binding var showFilters: Bool
    let select: (QuickFilter) -> Void

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                filtersButton
                ForEach(QuickFilter.allCases) { f in
                    chip(f)
                }
            }
            .padding(.vertical, 8)
        }
        .scrollIndicators(.hidden)
        .contentMargins(.horizontal, 16, for: .scrollContent)
    }

    private var filtersButton: some View {
        let active = model.options.activeCount
        return Button {
            Haptics.tap()
            showFilters = true
        } label: {
            HStack(spacing: 5) {
                Image(systemName: "line.3.horizontal.decrease")
                Text(active > 0 ? "Filters · \(active)" : "Filters")
                    .contentTransition(.numericText(value: Double(active)))
            }
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 12)
            .frame(minHeight: 34)
            .foregroundStyle(active > 0 ? Color.white : Color.accentColor)
            .background(active > 0 ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(Color.accentColor.opacity(0.14)), in: .capsule)
            .animation(.smooth, value: active)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(active > 0 ? "Filters and sort, \(active) active" : "Filters and sort")
    }

    private func chip(_ f: QuickFilter) -> some View {
        let on = model.quick == f
        let count = counts.map { $0[f] ?? 0 }
        let alert = f == .needsAttention && (count ?? 0) > 0
        return Button { select(f) } label: {
            HStack(spacing: 5) {
                if alert {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(on ? Color.white : Tone.danger.color)
                        .imageScale(.small)
                }
                Text(f.label)
                if let count {
                    Text("\(count)")
                        .fontWeight(.bold)
                        .monospacedDigit()
                        .foregroundStyle(on ? Color.white.opacity(0.85) : Color.secondary)
                        .contentTransition(.numericText(value: Double(count)))
                }
            }
            .font(.subheadline.weight(on ? .semibold : .medium))
            .padding(.horizontal, 12)
            .frame(minHeight: 34)
            .foregroundStyle(on ? Color.white : Color.primary)
            .background(on ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(Color(uiColor: .tertiarySystemFill)), in: .capsule)
            .animation(.smooth, value: count)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(count.map { "\(f.label), \($0)" } ?? f.label)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

// MARK: - Summary

private struct SummaryCard: View {
    let stats: EventStats
    let counts: [QuickFilter: Int]
    let footnote: String?
    let select: (QuickFilter) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text("\(stats.checkedIn)")
                    .font(.stat(44))
                    .contentTransition(.numericText(value: Double(stats.checkedIn)))
                Text("of \(stats.expected) here")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
                Text(stats.progress.formatted(.percent.precision(.fractionLength(0))))
                    .font(.subheadline.weight(.semibold).monospacedDigit())
                    .foregroundStyle(Tone.success.color)
                    .contentTransition(.numericText(value: stats.progress))
            }
            .accessibilityElement(children: .combine)
            ProgressView(value: stats.progress)
                .tint(Tone.success.color)
                .animation(.smooth, value: stats.progress)
                .accessibilityHidden(true)
            HStack(spacing: 8) {
                MiniStat(value: counts[.notHere] ?? 0, label: "Not here", systemImage: "circle.dashed", tone: .neutral) { select(.notHere) }
                MiniStat(value: counts[.needsAttention] ?? 0, label: "Need attention", systemImage: "exclamationmark.triangle.fill", tone: .danger) { select(.needsAttention) }
                MiniStat(value: counts[.notComplete] ?? 0, label: "Incomplete", systemImage: "hourglass", tone: .warning) { select(.notComplete) }
            }
            if let footnote {
                Text(footnote).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemBackground), in: .rect(cornerRadius: 22))
    }
}

private struct MiniStat: View {
    let value: Int
    let label: String
    let systemImage: String
    let tone: Tone
    let action: () -> Void

    var body: some View {
        Button {
            action()
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Image(systemName: systemImage)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(tone == .neutral ? Color.secondary : tone.color)
                    Text("\(value)")
                        .font(.title3.weight(.bold).monospacedDigit())
                        .contentTransition(.numericText(value: Double(value)))
                }
                Text(label)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(Color(uiColor: .tertiarySystemBackground), in: .rect(cornerRadius: 14))
            .contentShape(.rect(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(value) \(label)")
        .accessibilityHint("Shows only these people")
    }
}

/// Stands in for the summary while only part of the roster is known, instead of a misleading "3 of 3 here".
private struct PartialRosterCard: View {
    let known: Int
    let failed: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                if !failed { ProgressView() }
                Text(failed ? "Full List Not Loaded Yet" : "Loading Everyone…").font(.headline)
            }
            Text("Showing \(known) \(known == 1 ? "person" : "people") seen so far. Numbers will appear once the full list \(failed ? "syncs" : "is in").")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(uiColor: .secondarySystemBackground), in: .rect(cornerRadius: 22))
        .accessibilityElement(children: .combine)
    }
}
