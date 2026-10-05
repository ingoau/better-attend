import SwiftUI

/// Invites a walk-in: adds them to the roster as invited and emails them a link to finish
/// registering. Event admins (and series members) only; the People tab hides the entry otherwise.
struct InviteParticipantSheet: View {
    let event: Event
    /// Called with the new registration's id when the organizer taps View (the sheet then closes).
    let onView: (String) -> Void

    private struct Sent: Equatable {
        var result: InviteResult
        var email: String
        var name: String
    }

    private enum Field: Hashable { case email, firstName, lastName }

    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var email = ""
    @State private var firstName = ""
    @State private var lastName = ""
    @State private var sending = false
    @State private var emailProblem: String?
    @State private var serverError: String?
    @State private var sent: Sent?
    @FocusState private var focus: Field?

    private var hasDraft: Bool { !email.isBlank || !firstName.isBlank || !lastName.isBlank }

    var body: some View {
        NavigationStack {
            Group {
                if !ParticipantActionVisibility(event).invite {
                    ContentUnavailableView("You Don't Have Access to This", systemImage: "lock",
                                           description: Text("Only event admins can invite people to \(event.name)."))
                } else if let sent {
                    success(sent)
                } else {
                    form
                }
            }
            .navigationTitle(sent == nil ? "Invite Someone" : "")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if sent == nil {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                            .disabled(sending)
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        if sending {
                            ProgressView()
                        } else {
                            Button("Invite") { invite() }
                                .fontWeight(.semibold)
                                .disabled(email.isBlank || !ParticipantActionVisibility(event).invite)
                        }
                    }
                }
                if sent != nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { dismiss() }
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled((hasDraft && sent == nil) || sending)
    }

    private var form: some View {
        Form {
            if let serverError {
                Section {
                    Label {
                        Text(serverError)
                    } icon: {
                        Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Tone.danger.color)
                    }
                    .foregroundStyle(Tone.danger.onContainer)
                    .listRowBackground(Tone.danger.container)
                }
            }
            Section {
                TextField("Email", text: $email, prompt: Text("Email address"))
                    .keyboardType(.emailAddress)
                    .textContentType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
                    .focused($focus, equals: .email)
                    .onSubmit { focus = .firstName }
            } header: {
                Text("Email")
            } footer: {
                if let emailProblem {
                    Text(emailProblem).foregroundStyle(Tone.danger.color)
                }
            }
            Section {
                TextField("First name", text: $firstName)
                    .textContentType(.givenName)
                    .submitLabel(.next)
                    .focused($focus, equals: .firstName)
                    .onSubmit { focus = .lastName }
                TextField("Last name", text: $lastName)
                    .textContentType(.familyName)
                    .submitLabel(.send)
                    .focused($focus, equals: .lastName)
                    .onSubmit { invite() }
            } header: {
                Text("Name (Optional)")
            } footer: {
                Text("They'll be added to the roster as invited and emailed a link to finish registering.")
            }
        }
        .disabled(sending)
        .onAppear { focus = .email }
        .onChange(of: email) {
            if emailProblem != nil { emailProblem = InviteLogic.emailProblem(email) }
        }
    }

    private func success(_ sent: Sent) -> some View {
        ContentUnavailableView {
            Label(sent.result.held ? "Invitation Saved" : "Invitation Sent",
                  systemImage: sent.result.held ? "clock.fill" : "paperplane.fill")
        } description: {
            Text(InviteLogic.successMessage(sent.result, email: sent.email))
        } actions: {
            if let id = sent.result.participantEventId {
                Button("View \(sent.name)") {
                    Haptics.tap()
                    onView(id)
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
            }
            Button("Invite Someone Else") {
                Haptics.tap()
                email = ""
                firstName = ""
                lastName = ""
                serverError = nil
                withAnimation { self.sent = nil }
                focus = .email
            }
        }
    }

    private func invite() {
        guard !sending, ParticipantActionVisibility(event).invite else { return }
        let problem = InviteLogic.emailProblem(email)
        withAnimation { emailProblem = problem }
        guard problem == nil else {
            Haptics.reject()
            focus = .email
            return
        }
        guard app.isOnline else {
            Haptics.reject()
            withAnimation { serverError = "You're offline. Inviting someone needs a connection." }
            return
        }
        let address = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let first = InviteLogic.optionalName(firstName)
        let last = InviteLogic.optionalName(lastName)
        let eventId = event.id
        sending = true
        withAnimation { serverError = nil }
        Task {
            do {
                let result = try await app.api.inviteParticipant(eventId: eventId, email: address, firstName: first, lastName: last)
                sending = false
                Haptics.confirm()
                let name = first ?? address
                withAnimation { sent = Sent(result: result, email: address, name: name) }
                // Pull the new registration into the saved roster (a delta sync).
                Task { _ = try? await app.participants.sync(eventId) }
            } catch {
                sending = false
                guard !error.isCancellation else { return }
                Haptics.reject()
                withAnimation { serverError = error.friendlyMessage }
            }
        }
    }
}
