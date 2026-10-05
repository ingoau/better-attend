import SwiftUI

/// Settings → Event Staff: everyone with access to the event, grouped by role. Event admins (and
/// series members / global admins) can add people, change roles and remove them; series-inherited
/// rows are locked. Settings only links here for roles that can manage staff.
struct EventStaffView: View {
    let eventId: String

    @Environment(AppModel.self) private var app
    @State private var model: EventStaffModel
    @State private var adding = false
    @State private var selected: StaffMember?
    @State private var confirmRemove: StaffMember?

    init(eventId: String) {
        self.eventId = eventId
        _model = State(initialValue: EventStaffModel(eventId: eventId))
    }

    private var event: Event? { app.events.events?.first { $0.id == eventId } }
    private var canManage: Bool { EventPermissions.canManageStaff(event) && !model.forbidden }

    var body: some View {
        content
            .navigationTitle("Event Staff")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if canManage && !model.roles.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Add Staff", systemImage: "plus") {
                            Haptics.tap()
                            adding = true
                        }
                    }
                }
            }
            .toast($model.toast)
            .sheet(isPresented: $adding) {
                AddStaffSheet(model: model)
            }
            .sheet(item: $selected) { member in
                StaffMemberSheet(model: model, member: member, event: event)
            }
            .confirmationDialog(
                confirmRemove.map { "Remove \($0.user.displayName) from the staff?" } ?? "",
                isPresented: Binding(get: { confirmRemove != nil }, set: { if !$0 { confirmRemove = nil } }),
                titleVisibility: .visible,
                presenting: confirmRemove
            ) { member in
                Button("Remove", role: .destructive) { remove(member) }
                Button("Cancel", role: .cancel) {}
            } message: { member in
                Text(StaffText.removeMessage(member, user: app.user, event: event))
            }
            .alert("Added to Attend", isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(model.notice ?? "")
            }
            .task(id: canManage) {
                if canManage { await model.load(app) }
            }
    }

    @ViewBuilder
    private var content: some View {
        if !canManage {
            ContentUnavailableView {
                Label("You Don't Have Access to This", systemImage: "lock")
            } description: {
                Text("Only event admins can see and manage who works on \(event?.name ?? "this event").")
            }
        } else if model.staff != nil {
            list
        } else if let error = model.error {
            ContentUnavailableView {
                Label("Couldn't Load Staff", systemImage: "wifi.exclamationmark")
            } description: {
                Text(error)
            } actions: {
                Button("Try Again") { Task { await model.load(app) } }
                    .buttonStyle(.borderedProminent)
            }
        } else {
            ProgressView().controlSize(.large)
        }
    }

    private var list: some View {
        List {
            if let error = model.error {
                Section {
                    NoticeBanner(message: "Showing the last list. \(error)", retry: { Task { await model.load(app) } })
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
            }
            ForEach(model.sections) { section in
                Section {
                    ForEach(section.members) { member in row(member) }
                } header: {
                    Text(section.title)
                } footer: {
                    if let summary = section.summary?.nonBlank { Text(summary) }
                }
            }
        }
        .listStyle(.insetGrouped)
        .overlay {
            if model.sections.isEmpty {
                ContentUnavailableView("No Staff Yet", systemImage: "person.3",
                                       description: Text("Add someone to give them access to this event."))
            }
        }
        .refreshable { await model.load(app) }
        .animation(.smooth, value: model.staff)
    }

    @ViewBuilder
    private func row(_ member: StaffMember) -> some View {
        let locked = !EventPermissions.canChangeStaffMember(member)
        let rowContent = StaffRow(member: member, isSelf: StaffLogic.isSelf(member, user: app.user), locked: locked)
        if locked {
            // Inherited from the event series: no controls, just the label saying why.
            rowContent
        } else {
            Button {
                Haptics.tap()
                selected = member
            } label: {
                rowContent.contentShape(.rect)
            }
            .tint(.primary)
            .accessibilityHint("Change role or remove")
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button {
                    confirmRemove = member
                } label: {
                    Label("Remove", systemImage: "person.fill.xmark")
                }
                .tint(Tone.danger.color)
            }
        }
    }

    private func remove(_ member: StaffMember) {
        Task {
            if let problem = await model.remove(app, member: member) {
                model.toast = .error("Couldn't remove: \(problem)")
            }
        }
    }
}

/// Confirmation copy shared by the list's swipe action and the member sheet.
@MainActor
enum StaffText {
    static func removeMessage(_ member: StaffMember, user: User?, event: Event?) -> String {
        let eventName = event?.name ?? "this event"
        if StaffLogic.losesStaffAccess(member, newRole: nil, user: user, event: event) {
            return "This is you. You'll lose access to managing staff for \(eventName), and to the event itself unless you have another role."
        }
        return "They'll lose access to \(eventName) straight away. You can add them again later."
    }
}

// MARK: - Row

private struct StaffRow: View {
    let member: StaffMember
    let isSelf: Bool
    let locked: Bool

    var body: some View {
        HStack(spacing: 12) {
            Avatar(name: member.user.displayName, size: 40)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(member.user.displayName)
                        .font(.body.weight(.semibold))
                        .lineLimit(1)
                    if isSelf {
                        Text("You")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                Text(member.user.email)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                if member.user.globalAdmin || locked {
                    HStack(spacing: 6) {
                        if member.user.globalAdmin {
                            Pill(text: "Global admin", tone: .danger, systemImage: "shield.lefthalf.filled")
                        }
                        if locked {
                            Pill(text: "From series", tone: .info, systemImage: "square.stack.3d.up.fill")
                        }
                    }
                    .padding(.top, 2)
                }
            }
            Spacer(minLength: 8)
            if locked {
                VStack(alignment: .trailing, spacing: 2) {
                    Image(systemName: "lock.fill")
                        .foregroundStyle(.tertiary)
                    if let series = StaffLogic.seriesLabel(member) {
                        Text(series)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            } else {
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityValue(locked ? "Managed by the event series" : "")
    }
}

// MARK: - Role options

/// The server's role catalogue as a checkmark list, each with its summary.
private struct RoleOptions: View {
    let roles: [StaffRole]
    @Binding var selection: String?

    var body: some View {
        ForEach(roles) { r in
            Button {
                Haptics.selection()
                selection = r.role
            } label: {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(r.label)
                            .foregroundStyle(.primary)
                        if let summary = r.summary?.nonBlank {
                            Text(summary)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    Spacer(minLength: 8)
                    if selection == r.role {
                        Image(systemName: "checkmark")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(.tint)
                    }
                }
                .contentShape(.rect)
            }
            .tint(.primary)
            .accessibilityAddTraits(selection == r.role ? .isSelected : [])
        }
    }
}

/// Inline error row at the top of a form.
private struct FormError: View {
    let message: String

    var body: some View {
        Section {
            Label {
                Text(message)
            } icon: {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Tone.danger.color)
            }
            .foregroundStyle(Tone.danger.onContainer)
            .listRowBackground(Tone.danger.container)
        }
    }
}

// MARK: - Add

private struct AddStaffSheet: View {
    let model: EventStaffModel

    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var email = ""
    @State private var role: String?
    @State private var emailProblem: String?
    @State private var serverError: String?
    @State private var saving = false
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                if let serverError { FormError(message: serverError) }
                Section {
                    TextField("Email", text: $email, prompt: Text("name@example.com"))
                        .keyboardType(.emailAddress)
                        .textContentType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($focused)
                } header: {
                    Text("Email")
                } footer: {
                    if let emailProblem {
                        Text(emailProblem).foregroundStyle(Tone.danger.color)
                    } else {
                        Text("They're emailed to say they've been added. Use the address they sign in to Hack Club with.")
                    }
                }
                Section("Role") {
                    RoleOptions(roles: model.roles, selection: $role)
                }
            }
            .disabled(saving)
            .navigationTitle("Add Staff")
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
                        Button("Add") { add() }
                            .fontWeight(.semibold)
                            .disabled(email.isBlank || role == nil)
                    }
                }
            }
            .onAppear { focused = true }
            .onChange(of: email) {
                if emailProblem != nil { emailProblem = StaffLogic.emailProblem(email) }
            }
        }
        .presentationDetents([.large])
        .interactiveDismissDisabled(!email.isBlank || saving)
    }

    private func add() {
        guard let role, !saving else { return }
        let problem = StaffLogic.emailProblem(email)
        withAnimation { emailProblem = problem }
        guard problem == nil else {
            Haptics.reject()
            return
        }
        saving = true
        withAnimation { serverError = nil }
        let address = email
        Task {
            let failure = await model.add(app, email: address, role: role)
            saving = false
            if let failure {
                withAnimation { serverError = failure }
            } else {
                dismiss()
            }
        }
    }
}

// MARK: - Change role / remove

private struct StaffMemberSheet: View {
    let model: EventStaffModel
    let member: StaffMember
    let event: Event?

    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var role: String?
    @State private var saving = false
    @State private var serverError: String?
    @State private var confirmRemove = false
    @State private var confirmDemote = false

    init(model: EventStaffModel, member: StaffMember, event: Event?) {
        self.model = model
        self.member = member
        self.event = event
        _role = State(initialValue: member.role)
    }

    private var isSelf: Bool { StaffLogic.isSelf(member, user: app.user) }
    private var changed: Bool { role != nil && role != member.role }
    private var allowed: Bool { EventPermissions.canManageStaff(event) && EventPermissions.canChangeStaffMember(member) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 14) {
                        Avatar(name: member.user.displayName, size: 56)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(member.user.displayName + (isSelf ? " (you)" : ""))
                                .font(.title3.weight(.semibold))
                            Text(member.user.email)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)
                            if member.user.globalAdmin {
                                Pill(text: "Global admin", tone: .danger, systemImage: "shield.lefthalf.filled")
                                    .padding(.top, 2)
                            }
                        }
                    }
                    .padding(.vertical, 4)
                    .accessibilityElement(children: .combine)
                }

                if !allowed {
                    Section {
                        Text(member.inheritedFromSeries
                             ? "Their access comes from the event series, so it's managed on the series, not here."
                             : "You don't have access to change event staff.")
                            .foregroundStyle(.secondary)
                    }
                } else {
                    if let serverError { FormError(message: serverError) }
                    Section {
                        RoleOptions(roles: model.roles, selection: $role)
                    } header: {
                        Text("Role")
                    } footer: {
                        if changed && StaffLogic.losesStaffAccess(member, newRole: role, user: app.user, event: event) {
                            Label("You'll lose access to managing staff.", systemImage: "exclamationmark.triangle.fill")
                                .foregroundStyle(Tone.warning.color)
                        }
                    }
                    Section {
                        Button(role: .destructive) {
                            Haptics.tap()
                            confirmRemove = true
                        } label: {
                            Label("Remove from Event Staff", systemImage: "person.fill.xmark")
                        }
                        .foregroundStyle(Tone.danger.color)
                    }
                }
            }
            .disabled(saving)
            .navigationTitle(model.roleLabel(member.role))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(changed ? "Cancel" : "Close") { dismiss() }
                        .disabled(saving)
                }
                if allowed {
                    ToolbarItem(placement: .confirmationAction) {
                        if saving {
                            ProgressView()
                        } else {
                            Button("Save") { save(confirmed: false) }
                                .fontWeight(.semibold)
                                .disabled(!changed)
                        }
                    }
                }
            }
            .confirmationDialog("Remove \(member.user.displayName) from the staff?", isPresented: $confirmRemove, titleVisibility: .visible) {
                Button("Remove", role: .destructive) { remove() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(StaffText.removeMessage(member, user: app.user, event: event))
            }
            .alert("Change Your Own Role?", isPresented: $confirmDemote) {
                Button("Cancel", role: .cancel) {}
                Button("Change Role", role: .destructive) { save(confirmed: true) }
            } message: {
                Text("You'll lose access to managing staff for \(event?.name ?? "this event").")
            }
        }
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled(changed || saving)
    }

    private func save(confirmed: Bool) {
        guard let newRole = role, newRole != member.role, !saving else { return }
        if !confirmed && StaffLogic.losesStaffAccess(member, newRole: newRole, user: app.user, event: event) {
            Haptics.warn()
            confirmDemote = true
            return
        }
        saving = true
        withAnimation { serverError = nil }
        Task {
            let failure = await model.changeRole(app, member: member, role: newRole)
            saving = false
            if let failure {
                withAnimation { serverError = failure }
            } else {
                dismiss()
            }
        }
    }

    private func remove() {
        guard !saving else { return }
        saving = true
        withAnimation { serverError = nil }
        Task {
            let failure = await model.remove(app, member: member)
            saving = false
            if let failure {
                withAnimation { serverError = failure }
            } else {
                dismiss()
            }
        }
    }
}
