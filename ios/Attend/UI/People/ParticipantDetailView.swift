import SwiftUI

/// Participant detail. Opened from the People list it can page through that list (same filter and
/// sort) with Mail-style up / down chevrons; opened from anywhere else it's just this person.
struct ParticipantDetailView: View {
    let eventId: String
    let participantEventId: String
    var browseIds: [String] = []

    @State private var current: String?
    @State private var forward = true

    var body: some View {
        let pages = BrowseOrder.pages(browseIds, current: participantEventId)
        let id = current ?? participantEventId
        let previous = BrowseOrder.previous(pages, current: id)
        let next = BrowseOrder.next(pages, current: id)
        ParticipantPage(eventId: eventId, participantEventId: id, position: BrowseOrder.position(pages, current: id))
            .id(id)
            .transition(.asymmetric(
                insertion: .move(edge: forward ? .bottom : .top).combined(with: .opacity),
                removal: .move(edge: forward ? .top : .bottom).combined(with: .opacity)
            ))
            .toolbar {
                if pages.count > 1 {
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button("Previous Person", systemImage: "chevron.up") { go(to: previous, forward: false) }
                            .disabled(previous == nil)
                            .keyboardShortcut(.upArrow, modifiers: [.command])
                        Button("Next Person", systemImage: "chevron.down") { go(to: next, forward: true) }
                            .disabled(next == nil)
                            .keyboardShortcut(.downArrow, modifiers: [.command])
                    }
                }
            }
    }

    private func go(to id: String?, forward: Bool) {
        guard let id else { return }
        Haptics.selection()
        self.forward = forward
        withAnimation(.snappy(duration: 0.32)) { current = id }
    }
}

/// One person's detail (the page is re-created when paging, so its state starts fresh).
private struct ParticipantPage: View {
    let eventId: String
    let participantEventId: String
    let position: String?

    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var model: ParticipantDetailModel
    @State private var confirm: DetailConfirmation?
    @State private var addingNote = false
    @State private var editing = false
    @State private var headerHidden = false

    init(eventId: String, participantEventId: String, position: String?) {
        self.eventId = eventId
        self.participantEventId = participantEventId
        self.position = position
        _model = State(initialValue: ParticipantDetailModel(eventId: eventId, participantEventId: participantEventId))
    }

    var body: some View {
        let p = model.participant(app)
        Group {
            if let p {
                DetailList(participant: p, model: model, confirm: $confirm, addingNote: $addingNote, headerHidden: $headerHidden, editing: $editing)
            } else if !model.attempted || model.loading {
                ProgressView().controlSize(.large)
            } else {
                ContentUnavailableView {
                    Label("Couldn't Open This Person", systemImage: "person.crop.circle.badge.exclamationmark")
                } description: {
                    Text(model.error ?? "They may have been removed from this event.")
                } actions: {
                    Button("Try Again") { Task { await model.refresh(app) } }
                        .buttonStyle(.borderedProminent)
                }
            }
        }
        .navigationTitle(p?.name ?? "Participant")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text(p?.name ?? "")
                        .font(.headline)
                        .lineLimit(1)
                        .opacity(headerHidden ? 1 : 0)
                    if let position {
                        Text(position)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                            .contentTransition(.numericText())
                    }
                }
                .animation(.smooth(duration: 0.2), value: headerHidden)
                .accessibilityElement(children: .combine)
            }
            if let p {
                ToolbarItem(placement: .topBarTrailing) {
                    MoreMenu(participant: p, model: model, confirm: $confirm)
                }
            }
        }
        .toast($model.toast)
        .sheet(isPresented: $addingNote) {
            AddNoteSheet(name: p?.name ?? "them") { content, type, sensitivity in
                model.addNote(app, content: content, type: type, sensitivity: sensitivity)
            }
        }
        .sheet(isPresented: $editing) {
            if let p {
                ParticipantEditSheet(participant: p, model: model, canEditPII: ParticipantActionVisibility(model.event(app)).editPII)
            }
        }
        .confirmationDialog(confirm?.title(p?.name ?? "them") ?? "", isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }),
                            titleVisibility: .visible, presenting: confirm) { c in
            Button(c.action, role: c.destructive ? .destructive : nil) { perform(c) }
            Button("Cancel", role: .cancel) {}
        } message: { c in
            Text(c.message)
        }
        .alert("Badge Not Written", isPresented: Binding(get: { model.badge.isFailure }, set: { if !$0 { model.dismissBadgeError() } })) {
            if case .failed(_, true) = model.badge {
                Button("Try Again") { Task { await model.writeBadge(app) } }
            }
            Button("Close", role: .cancel) { model.dismissBadgeError() }
        } message: {
            if case let .failed(message, _) = model.badge { Text(message) }
        }
        .task { model.start(app) }
    }

    private func perform(_ c: DetailConfirmation) {
        Task {
            switch c {
            case let .undo(contextId, _, _): await model.undo(app, scanContextId: contextId)
            case .undoAll: await model.undo(app, scanContextId: nil)
            case .withdraw: await model.setWithdrawn(app, true)
            case .reinstate: await model.setWithdrawn(app, false)
            case .resetBadge: await model.resetBadge(app)
            case .remove: await remove()
            }
        }
    }

    /// Deletes the registration, then closes the page and says so on the screen underneath.
    private func remove() async {
        let name = model.participant(app)?.name ?? "them"
        guard await model.remove(app) else { return }
        router.toast = Toast(message: "Removed \(name)", systemImage: "person.fill.xmark", tone: .neutral)
        dismiss()
    }
}

/// Things that need a confirmation dialog first.
enum DetailConfirmation: Equatable {
    case undo(contextId: String, place: String, scans: Int)
    case undoAll(scans: Int, places: Int)
    case withdraw
    case reinstate
    case resetBadge
    /// Delete the registration outright (`eventName` is for the dialog).
    case remove(eventName: String)

    func title(_ name: String) -> String {
        switch self {
        case let .undo(_, place, _): "Undo \(name)'s scan at \(place)?"
        case .undoAll: "Undo all of \(name)'s scans?"
        case .withdraw: "Withdraw \(name)?"
        case .reinstate: "Reinstate \(name)?"
        case .resetBadge: "Reset \(name)'s badge?"
        case let .remove(eventName): "Remove \(name) from \(eventName)?"
        }
    }

    var message: String {
        switch self {
        case let .undo(_, _, scans):
            "This deletes \(scans == 1 ? "the scan" : "all \(scans) scans") there. You can check them in again."
        case let .undoAll(scans, places):
            "This deletes \(scans == 1 ? "1 scan" : "all \(scans) scans")\(places > 1 ? " in \(places) places" : ""). You can check them in again."
        case .withdraw: "They'll be hidden from the list and lose access to their ticket. You can reinstate them later."
        case .reinstate: "Their registration goes back to in progress so they can finish signing up."
        case .resetBadge: "Their current NFC badge will stop working at scanners. Write a new badge afterwards."
        case .remove:
            "This deletes their registration, travel, consents and scans for this event. This can't be undone. (Withdrawing is reversible.)"
        }
    }

    var action: String {
        switch self {
        case let .undo(_, _, scans): scans == 1 ? "Delete Scan" : "Delete \(scans) Scans"
        case let .undoAll(scans, _): scans == 1 ? "Delete Scan" : "Delete All \(scans) Scans"
        case .withdraw: "Withdraw"
        case .reinstate: "Reinstate"
        case .resetBadge: "Reset Badge"
        case .remove: "Remove"
        }
    }

    var destructive: Bool { self != .reinstate }
}

/// The "…" menu: copy id / ticket code, badge reset, withdraw / reinstate, remove. Edit and the
/// web link are buttons under the name.
private struct MoreMenu: View {
    let participant: Participant
    let model: ParticipantDetailModel
    @Binding var confirm: DetailConfirmation?
    @Environment(AppModel.self) private var app

    var body: some View {
        let event = model.event(app)
        let can = ParticipantActionVisibility(event)
        Menu {
            Button("Copy Participant ID", systemImage: "doc.on.doc") {
                Clipboard.copy(participant.participantEventId)
                model.toast = .info("Participant ID copied", systemImage: "doc.on.doc.fill")
            }
            Button("Copy Ticket Code", systemImage: "number") {
                Clipboard.copy(participant.shortCode)
                model.toast = .info("Copied \(participant.shortCode)", systemImage: "doc.on.doc.fill")
            }
            if participant.nfcBadgeAssigned {
                Button("Reset NFC Badge…", systemImage: "arrow.counterclockwise") { confirm = .resetBadge }
            }
            if can.showsRegistrationSection {
                Divider()
                if can.withdraw {
                    if participant.status == "withdrawn" {
                        Button("Reinstate…", systemImage: "person.fill.checkmark") { confirm = .reinstate }
                    } else {
                        Button("Withdraw…", systemImage: "person.fill.xmark", role: .destructive) { confirm = .withdraw }
                    }
                }
                if can.remove {
                    Button("Remove from Event…", systemImage: "trash", role: .destructive) {
                        confirm = .remove(eventName: event?.name ?? "this event")
                    }
                }
            }
        } label: {
            Label("More", systemImage: "ellipsis")
        }
    }
}
