import SwiftUI

/// The "New Announcement" sheet: who it reaches, the message with a character count, and a
/// confirmation before anything goes out (announcements can't be edited or unsent).
struct BlastComposer: View {
    let model: BlastsModel
    @Binding var draft: String
    /// People the blast will reach, when the roster can tell us.
    let recipientEstimate: Int?

    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool
    @State private var confirming = false
    @State private var discarding = false

    static let recipientRule = "Sends a Slack DM to every confirmed participant with a linked Slack account."

    private var over: Bool { draft.count > BlastLogic.maxLength }
    private var canSend: Bool { !draft.isBlank && !over && !model.sending }

    var body: some View {
        NavigationStack {
            Form {
                Section("To") {
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: "person.2.fill")
                            .font(.title3)
                            .foregroundStyle(.tint)
                            .frame(width: 32)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(recipientEstimate.map { "About \($0) \($0 == 1 ? "person" : "people")" } ?? "Confirmed Participants")
                                .font(.headline)
                            Text(Self.recipientRule)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 2)
                    .accessibilityElement(children: .combine)
                }

                Section {
                    TextEditor(text: $draft)
                        .focused($focused)
                        .frame(minHeight: 180)
                        .overlay(alignment: .topLeading) {
                            if draft.isEmpty {
                                Text("What does everyone need to know?")
                                    .foregroundStyle(.tertiary)
                                    .padding(.top, 8)
                                    .padding(.leading, 5)
                                    .allowsHitTesting(false)
                                    .accessibilityHidden(true)
                            }
                        }
                        .accessibilityLabel("Message")
                        .onChange(of: draft) { if model.sendError != nil { model.sendError = nil } }
                } header: {
                    Text("Message")
                } footer: {
                    footer
                }
            }
            .disabled(model.sending)
            .navigationTitle("New Announcement")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar }
            .alert(recipientEstimate.map { "Send to about \($0) people?" } ?? "Send this announcement?", isPresented: $confirming) {
                Button("Keep Editing", role: .cancel) {}
                Button("Send Now") { Task { await send() } }
            } message: {
                Text("Everyone gets a Slack DM straight away. Announcements can't be edited or unsent.")
            }
            .interactiveDismissDisabled(!draft.isBlank || model.sending)
            .onAppear { focused = true }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
    }

    private var footer: some View {
        let problem = model.sendError ?? (over ? "Too long for one Slack message." : nil)
        return HStack(alignment: .firstTextBaseline, spacing: 12) {
            if let problem {
                Label(problem, systemImage: "exclamationmark.circle.fill")
                    .foregroundStyle(Tone.danger.color)
            } else {
                Text("Line breaks are kept.")
            }
            Spacer(minLength: 0)
            Text("\(draft.count.formatted()) / \(BlastLogic.maxLength.formatted())")
                .monospacedDigit()
                .foregroundStyle(over ? Tone.danger.color : .secondary)
                .contentTransition(.numericText(value: Double(draft.count)))
                .accessibilityLabel("\(draft.count) of \(BlastLogic.maxLength) characters")
        }
        .animation(.snappy, value: problem)
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            Button("Cancel") {
                if draft.isBlank {
                    dismiss()
                } else {
                    discarding = true
                }
            }
            .disabled(model.sending)
            .confirmationDialog("Discard this announcement?", isPresented: $discarding, titleVisibility: .hidden) {
                Button("Discard Draft", role: .destructive) {
                    draft = ""
                    dismiss()
                }
                Button("Keep Draft") { dismiss() }
            }
        }
        ToolbarItem(placement: .confirmationAction) {
            if model.sending {
                ProgressView()
            } else {
                Button("Send", systemImage: "paperplane.fill") {
                    Haptics.tap()
                    focused = false
                    confirming = true
                }
                .disabled(!canSend)
            }
        }
    }

    private func send() async {
        if await model.send(draft, api: app.api) {
            draft = ""
            dismiss()
        } else {
            focused = true
        }
    }
}
