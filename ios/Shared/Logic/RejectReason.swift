import Foundation

/// Why a scan isn't letting someone in. Shared by the server verdict and the offline pre-check.
enum RejectReason: String, Codable, Hashable, Sendable, CaseIterable {
    case withdrawn, registrationRejected, consentMissing, wrongEvent, alreadyCheckedIn, notRegistered

    /// Card title, e.g. "Withdrawn".
    var title: String {
        switch self {
        case .withdrawn: "Withdrawn"
        case .registrationRejected: "Registration rejected"
        case .consentMissing: "Consent not signed"
        case .wrongEvent: "Wrong event"
        case .alreadyCheckedIn: "Already scanned"
        case .notRegistered: "Not registered"
        }
    }

    /// Lower-case reason for banners and logs: "Mia Chen: consent not signed".
    var short: String {
        switch self {
        case .withdrawn: "registration withdrawn"
        case .registrationRejected: "registration rejected"
        case .consentMissing: "consent not signed"
        case .wrongEvent: "registered for another event"
        case .alreadyCheckedIn: "already checked in"
        case .notRegistered: "not registered for this event"
        }
    }

    /// One sentence for the result card.
    func message(name: String?, detail: String?) -> String {
        let who = name ?? "This person"
        switch self {
        case .withdrawn: return "\(who) has withdrawn from this event."
        case .registrationRejected: return "\(who)'s registration was rejected."
        case .consentMissing: return "\(who)'s waiver hasn't been signed. Sort it out before checking them in."
        case .wrongEvent: return "Registered for \(detail ?? "another event"), not this one."
        case .alreadyCheckedIn: return "\(who) has already been scanned here."
        case .notRegistered: return "No registration for this event matches that code."
        }
    }
}
