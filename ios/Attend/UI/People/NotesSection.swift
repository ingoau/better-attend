import SwiftUI

/// Staff notes on a person: newest first, sent optimistically (Saving… / Not saved with retry).
struct NotesSection: View {
    let model: ParticipantDetailModel
    @Binding var addingNote: Bool
    @Environment(AppModel.self) private var app

    var body: some View {
        let notes = model.notes
        Section {
            Button {
                Haptics.tap()
                addingNote = true
            } label: {
                Label("Add Note", systemImage: "square.and.pencil")
            }
            if let notes {
                if notes.isEmpty {
                    Text("No notes yet. Handovers, incidents and requests go here.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                ForEach(notes) { item in
                    NoteRow(item: item, retry: { model.retryNote(app, id: item.id) }, discard: { withAnimation { model.discardNote(id: item.id) } })
                }
            } else if let error = model.notesError {
                VStack(alignment: .leading, spacing: 6) {
                    Label("Couldn't load notes", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(Tone.danger.color)
                    Text(error).font(.subheadline).foregroundStyle(.secondary)
                    Button("Try Again") { Task { await model.loadNotes(app) } }
                        .buttonStyle(.borderless)
                }
            } else {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Loading notes…").foregroundStyle(.secondary)
                }
            }
        } header: {
            Text(notes.map { $0.isEmpty ? "Notes" : "Notes (\($0.count))" } ?? "Notes")
        }
    }
}

private struct NoteRow: View {
    let item: NoteItem
    let retry: () -> Void
    let discard: () -> Void

    var body: some View {
        let n = item.note
        let restricted = n.sensitivity == "restricted"
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 4) {
                Text(n.author?.name?.nonBlank ?? n.author?.email ?? "Someone")
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                if let ago = Time.ago(n.createdAt) {
                    Text("· \(ago)").font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Text(n.content)
                .fixedSize(horizontal: false, vertical: true)
            FlowLayout(spacing: 6) {
                Pill(text: NoteRules.typeLabel(n.noteType), tone: n.noteType == "safeguarding" ? .info : .neutral,
                     systemImage: NoteRules.typeSymbol(n.noteType))
                if restricted { Pill(text: "Restricted", tone: .warning, systemImage: "lock.fill") }
            }
            if item.pending {
                HStack(spacing: 6) {
                    ProgressView().controlSize(.mini)
                    Text("Saving…")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            } else if item.failed {
                HStack {
                    Label("Not saved", systemImage: "exclamationmark.circle.fill")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Tone.danger.color)
                    Spacer()
                    Button("Discard", role: .destructive, action: discard)
                    Button("Retry", action: retry).fontWeight(.semibold)
                }
                .buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 2)
        .opacity(item.pending ? 0.7 : 1)
        .contextMenu {
            Button("Copy Note", systemImage: "doc.on.doc") { Clipboard.copy(n.content) }
        }
        .swipeActions {
            if item.failed {
                Button("Discard", systemImage: "trash", role: .destructive, action: discard)
                Button("Retry", systemImage: "arrow.clockwise", action: retry).tint(.blue)
            }
        }
    }
}

/// Composer: text, type and "restricted".
struct AddNoteSheet: View {
    let name: String
    /// Returns an error message, or nil when the note was accepted.
    let onAdd: (_ content: String, _ type: String, _ sensitivity: String) -> String?

    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""
    @State private var type = "ops"
    @State private var restricted = false
    @State private var problem: String?
    @FocusState private var focused: Bool

    private var length: Int { draft.trimmingCharacters(in: .whitespacesAndNewlines).count }
    private var tooLong: Bool { length > NoteRules.maxLength }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Add a note for the team…", text: $draft, axis: .vertical)
                        .lineLimit(4...12)
                        .focused($focused)
                } footer: {
                    HStack {
                        if let problem { Text(problem).foregroundStyle(Tone.danger.color) }
                        Spacer()
                        Text("\(length)/\(NoteRules.maxLength)")
                            .monospacedDigit()
                            .foregroundStyle(tooLong ? AnyShapeStyle(Tone.danger.color) : AnyShapeStyle(.secondary))
                    }
                }

                Section("Type") {
                    Picker("Type", selection: $type) {
                        ForEach(NoteRules.types, id: \.self) { t in
                            Text(NoteRules.typeLabel(t)).tag(t)
                        }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }

                Section {
                    Toggle(isOn: $restricted) {
                        Label("Restricted", systemImage: restricted ? "lock.fill" : "lock.open")
                    }
                } footer: {
                    // Attend flags restricted notes but doesn't hide them from other staff, so say so.
                    Text(restricted
                         ? "Marked restricted. Staff with People access can still read it, so keep details to what's needed."
                         : "Visible to staff with access to \(name)'s profile.")
                }
            }
            .navigationTitle("New Note")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { add() }
                        .fontWeight(.semibold)
                        .disabled(draft.isBlank || tooLong)
                }
            }
            .onChange(of: type) { Haptics.selection() }
            .onChange(of: restricted) { Haptics.selection() }
            .onAppear { focused = true }
        }
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled(!draft.isBlank)
    }

    private func add() {
        if let problem = onAdd(draft, type, restricted ? "restricted" : "normal") {
            withAnimation { self.problem = problem }
        } else {
            dismiss()
        }
    }
}
