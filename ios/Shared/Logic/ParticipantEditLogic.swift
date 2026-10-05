import Foundation

// MARK: - Edit participant details

/// The edit form's fields as the user sees them: plain strings ("" = empty), plus the date of birth
/// as yyyy-MM-dd. Built from the detailed participant so it starts with what's on file.
struct ParticipantEditForm: Hashable, Sendable {
    var legalFirstName = ""
    var legalLastName = ""
    var preferredName = ""
    var email = ""
    var phone = ""
    var pronouns = ""
    var tshirtSize = ""
    /// yyyy-MM-dd, or nil when none is on file / it's been cleared.
    var dateOfBirth: String?

    init() {}

    init(_ p: Participant) {
        legalFirstName = p.personal?.legalFirstName ?? ""
        legalLastName = p.personal?.legalLastName ?? ""
        preferredName = p.personal?.preferredName ?? ""
        email = p.email ?? ""
        phone = p.phone ?? ""
        pronouns = p.pronouns ?? ""
        tshirtSize = p.personal?.tshirtSize?.nonBlank ?? p.tshirtSize ?? ""
        dateOfBirth = CalendarDay(iso: p.personal?.dateOfBirth)?.description
    }
}

/// Which field a validation problem belongs to, so the form can show it inline.
enum ParticipantEditField: String, Hashable, Sendable {
    case legalFirstName, email
}

enum ParticipantEditLogic {
    /// Common sizes, smallest first. Any other size already on file is kept as an option.
    static let tshirtSizes = ["XS", "S", "M", "L", "XL", "2XL", "3XL"]

    /// Picker options: the standard sizes, plus the current value if it isn't one of them.
    static func sizeOptions(current: String) -> [String] {
        let c = current.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !c.isEmpty, !tshirtSizes.contains(c) else { return tshirtSizes }
        return tshirtSizes + [c]
    }

    /// Only what changed, trimmed. A field emptied by the user is sent as "" (clears it); untouched
    /// fields stay nil (not sent). Phone and date of birth are left out unless `includePII`.
    static func diff(original: ParticipantEditForm, edited: ParticipantEditForm, includePII: Bool) -> ParticipantEdit {
        func changed(_ old: String, _ new: String) -> String? {
            let o = old.trimmingCharacters(in: .whitespacesAndNewlines)
            let n = new.trimmingCharacters(in: .whitespacesAndNewlines)
            return o == n ? nil : n
        }
        var edit = ParticipantEdit()
        edit.legalFirstName = changed(original.legalFirstName, edited.legalFirstName)
        edit.legalLastName = changed(original.legalLastName, edited.legalLastName)
        edit.preferredName = changed(original.preferredName, edited.preferredName)
        edit.email = changed(original.email, edited.email)
        edit.pronouns = changed(original.pronouns, edited.pronouns)
        edit.tshirtSize = changed(original.tshirtSize, edited.tshirtSize)
        if includePII {
            edit.phone = changed(original.phone, edited.phone)
            edit.dateOfBirth = changed(original.dateOfBirth ?? "", edited.dateOfBirth ?? "")
        }
        return edit
    }

    /// Problems that would stop a save, by field. Empty = OK to save.
    static func validate(original: ParticipantEditForm, edited: ParticipantEditForm) -> [ParticipantEditField: String] {
        var problems: [ParticipantEditField: String] = [:]
        if !original.legalFirstName.isBlank && edited.legalFirstName.isBlank {
            problems[.legalFirstName] = "First name can't be blank."
        }
        let email = edited.email.trimmingCharacters(in: .whitespacesAndNewlines)
        if email.isEmpty {
            if !original.email.isBlank { problems[.email] = "Email can't be blank." }
        } else if !looksLikeEmail(email) {
            problems[.email] = "Enter a valid email address."
        }
        return problems
    }

    /// A deliberately loose check (something@domain.tld, no spaces); the server has the final say.
    static func looksLikeEmail(_ s: String) -> Bool {
        let e = s.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !e.isEmpty, !e.contains(where: \.isWhitespace) else { return false }
        let parts = e.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty else { return false }
        let domain = parts[1]
        let labels = domain.split(separator: ".", omittingEmptySubsequences: false)
        return labels.count >= 2 && labels.allSatisfy { !$0.isEmpty }
    }

    /// yyyy-MM-dd for a date picked on this device's calendar.
    static func isoDay(_ date: Date, in zone: TimeZone = .current) -> String {
        CalendarDay(date, in: zone).description
    }

    /// The picker's date for a yyyy-MM-dd string (midnight on this device's calendar).
    static func pickerDate(_ iso: String?, in zone: TimeZone = .current) -> Date? {
        CalendarDay(iso: iso)?.start(in: zone)
    }
}

// MARK: - Invite a walk-in

enum InviteLogic {
    /// nil when the email is fine to send, else why not.
    static func emailProblem(_ email: String) -> String? {
        let e = email.trimmingCharacters(in: .whitespacesAndNewlines)
        if e.isEmpty { return "Enter their email address." }
        return ParticipantEditLogic.looksLikeEmail(e) ? nil : "Enter a valid email address."
    }

    /// Trimmed, or nil when blank (so it isn't sent).
    static func optionalName(_ s: String) -> String? {
        s.trimmingCharacters(in: .whitespacesAndNewlines).nonBlank
    }

    /// What to tell the organizer once the invite went through.
    static func successMessage(_ result: InviteResult, email: String) -> String {
        let address = email.trimmingCharacters(in: .whitespacesAndNewlines)
        if result.held { return "Invitation saved — it'll be emailed when the event releases invitations." }
        return "Invitation sent to \(address). They'll appear on the roster as invited."
    }
}

// MARK: - Which participant actions to offer

/// The participant actions this user may see on an event, all from `EventPermissions`. Anything
/// false is hidden, never shown disabled.
struct ParticipantActionVisibility: Hashable, Sendable {
    var edit: Bool
    var editPII: Bool
    var withdraw: Bool
    var remove: Bool
    var invite: Bool

    init(_ event: Event?) {
        edit = EventPermissions.canEditParticipant(event)
        editPII = EventPermissions.canEditPII(event)
        withdraw = EventPermissions.canEditParticipant(event)
        remove = EventPermissions.canRemoveParticipants(event)
        // Inviting goes through the participants API, which also requires roster access.
        invite = EventPermissions.canInviteParticipants(event) && EventPermissions.canViewParticipants(event)
    }

    /// The detail page's bottom "registration" section has something in it.
    var showsRegistrationSection: Bool { withdraw || remove }
}
