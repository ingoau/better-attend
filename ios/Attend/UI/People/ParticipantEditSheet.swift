import SwiftUI
import UIKit

/// Edits a participant's profile: names, contact details, pronouns, T-shirt size and (for roles that
/// can see them) phone and date of birth. Only the fields that changed are sent. Server errors show
/// inline and the form stays open.
struct ParticipantEditSheet: View {
    let model: ParticipantDetailModel
    /// Phone and date of birth are only offered to roles that can read them.
    let canEditPII: Bool

    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var original: ParticipantEditForm
    @State private var form: ParticipantEditForm
    @State private var problems: [ParticipantEditField: String] = [:]
    @State private var serverError: String?
    @State private var saving = false

    init(participant: Participant, model: ParticipantDetailModel, canEditPII: Bool) {
        self.model = model
        self.canEditPII = canEditPII
        let initial = ParticipantEditForm(participant)
        _original = State(initialValue: initial)
        _form = State(initialValue: initial)
    }

    private var edit: ParticipantEdit {
        ParticipantEditLogic.diff(original: original, edited: form, includePII: canEditPII)
    }

    var body: some View {
        NavigationStack {
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
                    field("First name", text: $form.legalFirstName, content: .givenName)
                    field("Last name", text: $form.legalLastName, content: .familyName)
                    field("Preferred name", text: $form.preferredName, content: .nickname)
                    field("Pronouns", text: $form.pronouns, content: nil)
                } header: {
                    Text("Name")
                } footer: {
                    if let problem = problems[.legalFirstName] {
                        Text(problem).foregroundStyle(Tone.danger.color)
                    } else {
                        Text("Legal names are used for travel, insurance and consent forms.")
                    }
                }

                Section {
                    LabeledContent("Email") {
                        TextField("Email", text: $form.email, prompt: Text("name@example.com"))
                            .keyboardType(.emailAddress)
                            .textContentType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .multilineTextAlignment(.trailing)
                    }
                    if canEditPII {
                        LabeledContent("Phone") {
                            TextField("Phone", text: $form.phone, prompt: Text("+61 400 000 000"))
                                .keyboardType(.phonePad)
                                .textContentType(.telephoneNumber)
                                .multilineTextAlignment(.trailing)
                        }
                    }
                } header: {
                    Text("Contact")
                } footer: {
                    if let problem = problems[.email] {
                        Text(problem).foregroundStyle(Tone.danger.color)
                    } else if edit.email != nil {
                        Text("Their ticket emails and sign-in will use the new address.")
                    }
                }

                Section("Details") {
                    Picker("T-shirt size", selection: $form.tshirtSize) {
                        Text("Not set").tag("")
                        ForEach(ParticipantEditLogic.sizeOptions(current: original.tshirtSize), id: \.self) { size in
                            Text(size).tag(size)
                        }
                    }
                    if canEditPII { birthdayRows }
                }
            }
            .disabled(saving)
            .navigationTitle("Edit Details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if saving {
                        ProgressView()
                    } else {
                        Button("Save") { save() }
                            .fontWeight(.semibold)
                            .disabled(edit.isEmpty)
                    }
                }
            }
            .onChange(of: form) {
                // Once problems are showing, clear each as soon as it's fixed.
                if !problems.isEmpty {
                    problems = ParticipantEditLogic.validate(original: original, edited: form)
                }
            }
            .onChange(of: form.tshirtSize) { Haptics.selection() }
        }
        .presentationDetents([.large])
        .interactiveDismissDisabled(!edit.isEmpty || saving)
    }

    private func field(_ title: String, text: Binding<String>, content: UITextContentType?) -> some View {
        LabeledContent(title) {
            TextField(title, text: text, prompt: Text("None"))
                .textContentType(content)
                .textInputAutocapitalization(.words)
                .multilineTextAlignment(.trailing)
        }
    }

    @ViewBuilder private var birthdayRows: some View {
        if let date = ParticipantEditLogic.pickerDate(form.dateOfBirth) {
            DatePicker("Date of birth", selection: Binding(
                get: { date },
                set: { form.dateOfBirth = ParticipantEditLogic.isoDay($0) }
            ), in: ...Date(), displayedComponents: .date)
            // Undo an "Add" (an existing birthday isn't cleared from here).
            if original.dateOfBirth == nil {
                Button("Remove Date of Birth", role: .destructive) { form.dateOfBirth = nil }
            }
        } else {
            Button("Add Date of Birth") {
                let start = Calendar.current.date(byAdding: .year, value: -16, to: Date()) ?? Date()
                form.dateOfBirth = ParticipantEditLogic.isoDay(start)
            }
        }
    }

    private func save() {
        let found = ParticipantEditLogic.validate(original: original, edited: form)
        withAnimation { problems = found }
        guard found.isEmpty else {
            Haptics.reject()
            return
        }
        let edit = self.edit
        guard !edit.isEmpty else {
            dismiss()
            return
        }
        saving = true
        withAnimation { serverError = nil }
        Task {
            let problem = await model.saveEdits(app, edit)
            saving = false
            if let problem {
                withAnimation { serverError = problem }
            } else {
                dismiss()
            }
        }
    }
}
