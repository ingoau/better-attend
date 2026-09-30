import SwiftUI

enum ScanSheet: String, Identifiable {
    case find, pending, recent
    var id: String { rawValue }
}

// MARK: - Find person

/// Manual check-in: filters the cached roster instantly, then merges server results. Typed or pasted
/// ticket ids can be checked in directly; 8-character short codes resolve through search.
struct FindPersonSheet: View {
    let model: ScanModel
    var onCheckIn: (Participant) -> Void
    var onSubmitDirect: (ScanInput) -> Void

    @Environment(\.dismiss) private var dismiss
    @FocusState private var searchFocused: Bool

    private var state: ScanSearchState { model.search }
    private var context: ScanContext? { model.selectedContext }
    private var action: String { ScanLogic.actionLabel(checksIn: context?.checksIn ?? true) }

    var body: some View {
        NavigationStack {
            List {
                if let input = state.directInput {
                    Section {
                        DirectInputRow(input: input, action: action) { onSubmitDirect(input) }
                    }
                }
                if !state.results.isEmpty {
                    Section {
                        ForEach(state.results) { p in
                            FindPersonRow(participant: p, action: action, timezone: model.event?.timezone,
                                      selectedContextId: model.selectedContextId) { onCheckIn(p) }
                        }
                    } header: {
                        if let context { Text("\(action == "Check In" ? "Check in" : "Scan") at \(context.name)") }
                    } footer: {
                        if let err = state.remoteError { Text("Showing offline results only. \(err)") }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .overlay { placeholder }
            .animation(.smooth(duration: 0.25), value: state.results.map(\.participantEventId))
            .searchable(text: Binding(get: { state.query }, set: { model.setQuery($0) }),
                        placement: .navigationBarDrawer(displayMode: .always), prompt: "Name, email, ticket code or ID")
            .searchFocused($searchFocused)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .onSubmit(of: .search) {
                if let input = state.directInput { onSubmitDirect(input) } else if let only = state.results.first, state.results.count == 1 { onCheckIn(only) }
            }
            .navigationTitle("Find Person")
            .navigationBarTitleDisplayMode(.inline)
            .scanSubtitle(context.map { "\(action == "Check In" ? "Checking in" : "Scanning") at \($0.name)" })
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", role: .cancel) { dismiss() }
                }
                if state.remoteLoading {
                    ToolbarItem(placement: .topBarTrailing) { ProgressView() }
                }
            }
            .task { searchFocused = true }
        }
        .presentationDetents([.large])
    }

    @ViewBuilder private var placeholder: some View {
        let trimmed = state.query.trimmingCharacters(in: .whitespaces)
        if !state.canSearch, state.directInput == nil {
            ContentUnavailableView("Search Isn't Available", systemImage: "person.crop.circle.badge.questionmark",
                                   description: Text("Your role can't look people up. Scan their ticket, tap their badge, or paste their ticket ID above."))
        } else if trimmed.isEmpty {
            ContentUnavailableView {
                Label("Find Someone", systemImage: "person.text.rectangle")
            } description: {
                Text(state.rosterEmpty
                    ? "Type a name, email, or the 8-character code on their ticket."
                    : "Type a name, email, or the 8-character code on their ticket. Results appear instantly, even offline.")
            }
        } else if state.results.isEmpty, state.directInput == nil, !state.remoteLoading {
            ContentUnavailableView {
                Label("No Results for “\(trimmed)”", systemImage: "magnifyingglass")
            } description: {
                Text(state.remoteError.map { "Showing offline results only. \($0)" } ?? "Check the spelling, or try their email.")
            }
        }
    }
}

private struct DirectInputRow: View {
    let input: ScanInput
    let action: String
    let onSubmit: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "qrcode")
                .font(.title3)
                .foregroundStyle(Tone.info.onContainer)
                .frame(width: 44, height: 44)
                .background(Tone.info.container, in: .rect(cornerRadius: 12, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text("Use This Ticket ID").font(.body.weight(.semibold))
                Text(input.participantId ?? "")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer(minLength: 4)
            Button(action, action: onSubmit)
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                .controlSize(.small)
                .fontWeight(.semibold)
        }
    }
}

private struct FindPersonRow: View {
    let participant: Participant
    let action: String
    let timezone: String?
    let selectedContextId: String?
    let onAction: () -> Void

    var body: some View {
        let p = participant
        let status = ScanLogic.personStatus(p, selectedContextId: selectedContextId, tz: timezone)
        let done = status.text.hasPrefix("Scanned here") || !p.isActive
        HStack(spacing: 12) {
            Avatar(name: p.name, url: p.headshotUrl, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(p.fullName?.nonBlank ?? p.name).font(.body.weight(.semibold)).lineLimit(1)
                    if p.hasSafetyAlert {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.caption)
                            .foregroundStyle(Tone.danger.color)
                            .accessibilityLabel("Safety alert")
                    }
                }
                Text([p.pronouns, p.shortCode].compactMap { $0 }.joined(separator: " · "))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Text(status.text)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(status.tone == .neutral ? Color.secondary : status.tone.color)
            }
            Spacer(minLength: 4)
            Group {
                if done {
                    Button(action, action: onAction).buttonStyle(.bordered)
                } else {
                    Button(action, action: onAction).buttonStyle(.borderedProminent)
                }
            }
            .buttonBorderShape(.capsule)
            .controlSize(.small)
            .fontWeight(.semibold)
            .accessibilityLabel("\(action) \(p.name)")
        }
        .accessibilityElement(children: .contain)
    }
}

// MARK: - Offline queue

struct PendingScansSheet: View {
    let model: ScanModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmDiscard: PendingScan?

    private var pending: [PendingScan] { model.app.scans.pending }

    var body: some View {
        NavigationStack {
            List {
                if !pending.isEmpty {
                    Section {
                        HStack(alignment: .top, spacing: 12) {
                            Image(systemName: "icloud.and.arrow.up")
                                .font(.title3)
                                .foregroundStyle(Tone.info.color)
                            Text("These send automatically once you're connected. Their original scan times are kept.")
                                .font(.subheadline)
                        }
                        Button {
                            model.syncNow()
                        } label: {
                            HStack {
                                if model.syncing { ProgressView().padding(.trailing, 4) }
                                Text(model.syncing ? "Syncing…" : "Sync Now")
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .disabled(model.syncing)
                        .fontWeight(.semibold)
                    }
                    Section("\(pending.count) \(pending.count == 1 ? "Scan" : "Scans") Saved Offline") {
                        ForEach(pending) { p in
                            PendingRow(scan: p, timezone: model.event?.timezone)
                                .swipeActions(edge: .trailing) {
                                    Button("Discard", systemImage: "trash", role: .destructive) { confirmDiscard = p }
                                }
                                .contextMenu {
                                    Button("Discard Scan", systemImage: "trash", role: .destructive) { confirmDiscard = p }
                                }
                        }
                    }
                }
            }
            .overlay {
                if pending.isEmpty {
                    ContentUnavailableView("All Caught Up", systemImage: "checkmark.icloud", description: Text("Every scan has reached Attend."))
                }
            }
            .animation(.smooth, value: pending.map(\.clientScanId))
            .navigationTitle("Waiting to Sync")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .confirmationDialog("Discard this scan?", isPresented: Binding(get: { confirmDiscard != nil }, set: { if !$0 { confirmDiscard = nil } }),
                                titleVisibility: .visible, presenting: confirmDiscard) { p in
                Button("Discard Scan", role: .destructive) {
                    Haptics.warn()
                    Task { await model.discard(p.clientScanId) }
                }
            } message: { p in
                Text("\(p.displayName) won't be marked as scanned. This can't be undone.")
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct PendingRow: View {
    let scan: PendingScan
    let timezone: String?

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: scan.input.badgeToken != nil ? "wave.3.right" : "qrcode")
                .foregroundStyle(Tone.info.onContainer)
                .frame(width: 36, height: 36)
                .background(Tone.info.container, in: .circle)
            VStack(alignment: .leading, spacing: 2) {
                Text(scan.displayName).font(.body.weight(.semibold)).lineLimit(1)
                Text([scan.scanContextName, Time.time(scan.scannedAt, tz: timezone).map { "saved \($0)" }].compactMap { $0 }.joined(separator: " · "))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let error = scan.lastError {
                    Text(error).font(.caption).foregroundStyle(.tertiary).lineLimit(1)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Recent scans

struct RecentScansSheet: View {
    let model: ScanModel
    var onOpen: ((Participant) -> Void)?
    @Environment(\.dismiss) private var dismiss

    private var entries: [ScanLogEntry] { model.app.scans.log }

    var body: some View {
        NavigationStack {
            List {
                if !entries.isEmpty {
                    Section {
                        ForEach(entries, id: \.self) { e in
                            if let p = e.outcome.participant, let onOpen {
                                Button { onOpen(p) } label: {
                                    RecentRow(entry: e, timezone: model.event?.timezone, showsChevron: true)
                                }
                                .tint(.primary)
                            } else {
                                RecentRow(entry: e, timezone: model.event?.timezone, showsChevron: false)
                            }
                        }
                    } footer: {
                        Text("Scans made on this device, newest first.")
                    }
                }
            }
            .overlay {
                if entries.isEmpty {
                    ContentUnavailableView("No Scans Yet", systemImage: "clock.arrow.circlepath",
                                           description: Text("Scans you make on this device appear here so you can double-check what just happened."))
                }
            }
            .navigationTitle("Recent Scans")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct RecentRow: View {
    let entry: ScanLogEntry
    let timezone: String?
    let showsChevron: Bool

    var body: some View {
        let kind = entry.outcome.kind
        let p = entry.outcome.participant
        HStack(spacing: 12) {
            ZStack(alignment: .bottomTrailing) {
                if let p {
                    Avatar(name: p.name, url: p.headshotUrl, size: 44)
                    Image(systemName: kind.systemImage)
                        .font(.system(size: 9, weight: .heavy))
                        .foregroundStyle(kind.tone.onColor)
                        .frame(width: 18, height: 18)
                        .background(kind.tone.color, in: .circle)
                        .overlay(Circle().stroke(Color(uiColor: .secondarySystemGroupedBackground), lineWidth: 2))
                        .offset(x: 3, y: 3)
                } else {
                    Image(systemName: kind.systemImage)
                        .font(.body.weight(.bold))
                        .foregroundStyle(kind.tone.onContainer)
                        .frame(width: 44, height: 44)
                        .background(kind.tone.container, in: .circle)
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.outcome.displayName).font(.body.weight(.semibold)).lineLimit(1)
                Text(entry.outcome.label)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(kind.tone == .neutral ? Color.secondary : kind.tone.color)
                    .lineLimit(2)
                Text([entry.contextName, Time.time(entry.at, tz: timezone)].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .contentShape(.rect)
        .accessibilityElement(children: .combine)
    }
}
