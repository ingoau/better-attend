import Foundation

// Wire models for https://attend.hackclub.com/api/v1. Every id is a UUID string.
// Keys are snake_case on the wire and decoded with `.convertFromSnakeCase` (see `AttendJSON`).
// Fields the server omits for less-privileged roles are optional or have defaults.

struct User: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var name: String?
    var email: String
    @Default<False> var globalAdmin: Bool = false
    @Default<False> var isOrganizer: Bool = false
    @Default<False> var isParticipant: Bool = false

    var displayName: String {
        if let name, !name.trimmingCharacters(in: .whitespaces).isEmpty { return name }
        return String(email.split(separator: "@", maxSplits: 1).first ?? Substring(email))
    }
}

struct SessionResponse: Codable, Hashable, Sendable {
    var token: String
    var expiresAt: String
    var user: User
}

struct Event: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var name: String
    var slug: String
    var startsAt: String?
    var endsAt: String?
    var timezone: String?
    var locationCity: String?
    var logoUrl: String?
    var bannerUrl: String?
    /// global_admin | series_member | event_admin | safeguarding_lead | ops | limited | read_only
    var role: String?
    @Default<False> var canViewParticipantPii: Bool = false
    @Default<True> var canViewParticipants: Bool = true
    @Default<False> var canViewSensitiveData: Bool = false
    @Default<False> var travelEnabled: Bool = false
}

struct EventsResponse: Codable, Sendable {
    @Default<Empty<Event>> var events: [Event] = []
}

// MARK: - Tickets (participant side)

struct TicketEvent: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var name: String
    var slug: String?
    var startsAt: String?
    var endsAt: String?
    var timezone: String?
    var locationCity: String?
    var locationAddress: String?
    var locationCountry: String?
    var locationLatitude: Double?
    var locationLongitude: Double?
    var logoUrl: String?
    var bannerUrl: String?
}

struct TicketTravelLeg: Codable, Hashable, Sendable {
    var flightCode: String?
    var departureAirport: String?
    var arrivalAirport: String?
    var departureTime: String?
    var arrivalTime: String?
}

struct TicketTravel: Codable, Hashable, Sendable {
    var direction: String?
    var mode: String?
    var carrier: String?
    var flightNumber: String?
    var departureCity: String?
    var arrivalCity: String?
    var departureTime: String?
    var arrivalTime: String?
    @Default<Empty<TicketTravelLeg>> var legs: [TicketTravelLeg] = []
}

struct TicketMessage: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var subject: String?
    var body: String?
    var senderName: String?
    var deliveredAt: String?
}

struct Ticket: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var participantId: String
    var status: String
    var displayStatus: String?
    @Default<False> var confirmed: Bool = false
    @Default<False> var checkedIn: Bool = false
    var attendeeName: String?
    var qrPayload: String
    var shortCode: String?
    var appleWalletUrl: String?
    var onboardingUrl: String?
    var event: TicketEvent
    // detail-only extras
    @Default<False> var canDownloadTicket: Bool = false
    @Default<False> var canDownloadExcuseLetter: Bool = false
    var travelInbound: TicketTravel?
    @Default<Empty<TicketMessage>> var messages: [TicketMessage] = []
}

struct TicketsResponse: Codable, Sendable {
    @Default<Empty<Ticket>> var tickets: [Ticket] = []
}

struct TicketResponse: Codable, Sendable { var ticket: Ticket }

struct URLResponseBody: Codable, Sendable { var url: String }

// MARK: - Scan contexts & scans

struct ScanContext: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var name: String
    @Default<False> var checksIn: Bool = false
    @Default<False> var isTravelPickup: Bool = false
    @Default<False> var isAirport: Bool = false
    @Default<Zero> var position: Int = 0
    var startsAt: String?
    var endsAt: String?
}

struct ScanContextsResponse: Codable, Sendable {
    @Default<Empty<ScanContext>> var scanContexts: [ScanContext] = []
}

struct ScanContextRef: Codable, Hashable, Sendable {
    var id: String
    var name: String
    @Default<False> var checksIn: Bool = false
    @Default<False> var isTravelPickup: Bool = false
}

struct Scan: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var participantId: String?
    var participantEventId: String?
    var scannedAt: String
    var scannedBy: String?
    var clientScanId: String?
    var source: String?
    var scanContext: ScanContextRef?
    var createdAt: String?
}

struct ScansResponse: Codable, Sendable {
    @Default<Empty<Scan>> var scans: [Scan] = []
    @Default<False> var hasMore: Bool = false
    var syncedAt: String?
}

struct ScanResult: Codable, Hashable, Sendable {
    @Default<True> var success: Bool = true
    /// "scanned" | "already_scanned"
    var outcome: String?
    @Default<True> var firstScanInContext: Bool = true
    var firstScannedAt: String?
    @Default<False> var deduplicated: Bool = false
    var scan: Scan?
    var scanContext: ScanContextRef?
    var participant: Participant?

    var isAlreadyScanned: Bool {
        (outcome ?? (firstScanInContext ? "scanned" : "already_scanned")) == "already_scanned"
    }
}

struct UndoResult: Codable, Hashable, Sendable {
    @Default<True> var success: Bool = true
    @Default<Zero> var deletedScans: Int = 0
    var participantEventId: String?
    var scanContextId: String?
}

// MARK: - Participants

struct ContextScanSummary: Codable, Hashable, Sendable {
    var scanContextId: String
    var scanContextName: String?
    @Default<False> var checksIn: Bool = false
    @Default<False> var isTravelPickup: Bool = false
    @Default<Zero> var scanCount: Int = 0
    var firstScannedAt: String?
    var lastScannedAt: String?
}

struct EmergencyContact: Codable, Hashable, Sendable {
    var id: String?
    var name: String?
    var phone: String?
    var email: String?
    var relationship: String?
    var priority: Int?
}

struct TravelLeg: Codable, Hashable, Sendable {
    var id: String?
    @Default<Zero> var position: Int = 0
    var flightCode: String?
    var departureAirport: String?
    var arrivalAirport: String?
    var departureTime: String?
    var arrivalTime: String?
    var liveStatus: String?
    var liveDepartureTime: String?
    var liveArrivalTime: String?
    var travelPickedUpAt: String?
}

struct Travel: Codable, Hashable, Sendable {
    var id: String?
    var direction: String?
    /// plane | train | car | bus | other
    var mode: String?
    var visaRequired: Bool?
    var visaStatus: String?
    var visaType: String?
    var visaNumber: String?
    var passportNationality: String?
    @Default<False> var isUnaccompaniedMinor: Bool = false
    var carrier: String?
    var flightNumber: String?
    var trainDepartureStation: String?
    var trainArrivalStation: String?
    var departureStation: String?
    var arrivalStation: String?
    var departureCity: String?
    var arrivalCity: String?
    var departureTime: String?
    var arrivalTime: String?
    var expectedArrivalTime: String?
    var busDepartureLocation: String?
    var busArrivalLocation: String?
    var originAddress: String?
    var otherDetails: String?
    var notes: String?
    var pickupDismissedAt: String?
    @Default<Empty<TravelLeg>> var legs: [TravelLeg] = []
}

struct ParticipantGroup: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var name: String
    var color: String?
}

struct Address: Codable, Hashable, Sendable {
    var line1: String?
    var line2: String?
    var city: String?
    var state: String?
    var postalCode: String?
    var country: String?
}

struct Personal: Codable, Hashable, Sendable {
    var legalFirstName: String?
    var legalLastName: String?
    var preferredName: String?
    var age: Int?
    var tshirtSize: String?
    var engagementPreference: String?
    var engagementNotes: String?
    var dateOfBirth: String?
    var address: Address?
}

struct Accommodation: Codable, Hashable, Sendable {
    var checkInDate: String?
    var checkOutDate: String?
    var genderIdentity: String?
    var genderIdentityOther: String?
    var assignedRoom: String?
    var roomingExempt: Bool?
    var venueName: String?
    var preferredRoommateGenders: [String]?
    var roommatePreferences: String?
    var roommateExclusions: String?
    var quietRoomPreference: Bool?
    var roomTypePreference: String?
    var accessibilityNeeds: String?
    var notes: String?
}

struct Consent: Codable, Hashable, Sendable {
    var id: String?
    var consentType: String?
    var status: String?
    var pendingOn: String?
    var sentAt: String?
    var participantSignedAt: String?
    var guardianSignedAt: String?
    var signedAt: String?
    var documentUrl: String?
    var failureReason: String?
}

struct Guardian: Codable, Hashable, Sendable {
    var id: String?
    var guardianId: String?
    var name: String?
    var email: String?
    var phone: String?
    var relationship: String?
    @Default<False> var isPrimary: Bool = false
    var status: String?
    var acceptedAt: String?
    var completedAt: String?
    var mediaPermission: Bool?
    var photoPermission: Bool?
    var travelPermission: Bool?
    var emergencyMedicalConsent: Bool?
    var otcMedicationConsent: Bool?
    @Default<Empty<EmergencyContact>> var emergencyContacts: [EmergencyContact] = []
}

struct MedicalDetail: Codable, Hashable, Sendable {
    var allergySeverity: String?
    var emergencyActionPlan: String?
    var additionalNotes: String?
}

struct DietaryDetail: Codable, Hashable, Sendable {
    var intolerances: String?
    var notes: String?
}

struct Accessibility: Codable, Hashable, Sendable {
    var mobilityNeeds: String?
    var usesWheelchair: Bool?
    var stepFreeRequired: Bool?
    var sensoryNeeds: String?
    var lightSensitivity: Bool?
    var noiseSensitivity: Bool?
    var strobeSensitivity: Bool?
    var communicationNeeds: String?
    var needsCaptioning: Bool?
    var needsLargePrint: Bool?
    var needsSignLanguage: Bool?
    var neurodivergentNotes: String?
    var hasAdhd: Bool?
    var hasAutism: Bool?
    var hasDyslexia: Bool?
    var religiousPractices: String?
    var prayerSpaceRequired: Bool?
    var requiresPrivateSpace: Bool?
    var distanceLimitations: String?
    var unavailableTimes: String?
    var otherNeeds: String?
}

struct SafeguardingDetail: Codable, Hashable, Sendable {
    var highSupportNotes: String?
    var authorizedPickupAdults: String?
    var otherInstructions: String?
}

/// A participant's registration for one event. `participantEventId` is the id most
/// event-scoped endpoints want; `participantId` is the person (and what ticket QR codes encode).
/// The detail-only blocks (personal, accommodation, consents, …) are nil in list responses.
struct Participant: Codable, Hashable, Sendable, Identifiable {
    var participantId: String
    var participantEventId: String
    var displayName: String?
    var fullName: String?
    var email: String?
    var phone: String?
    var slackUserId: String?
    var pronouns: String?
    var headshotUrl: String?
    var tshirtSize: String?
    /// invited | in_progress | awaiting_guardian | complete | withdrawn | rejected
    var status: String?
    var checkedInAt: String?
    var nfcBadgeToken: String?
    @Default<False> var nfcBadgeAssigned: Bool = false
    @Default<False> var hasAnaphylaxisRisk: Bool = false
    @Default<False> var requiresRefrigeration: Bool = false
    @Default<False> var crossContaminationRisk: Bool = false
    @Default<False> var highSupportFlag: Bool = false
    @Default<False> var canLeaveUnaccompanied: Bool = false
    @Default<False> var waiverSigned: Bool = false
    var updatedAt: String?
    // sensitive (global admin / safeguarding lead only)
    var allergies: String?
    var medicalConditions: String?
    var medications: String?
    var dietType: String?
    var lifeThreateningAllergies: String?
    var freedomWaiverGranted: Bool?
    var emergencyContacts: [EmergencyContact]?
    var parentGuardianName: String?
    var parentGuardianPhone: String?
    var parentGuardianEmail: String?
    var travelInbound: Travel?
    var travelOutbound: Travel?
    @Default<Empty<ContextScanSummary>> var scansByContext: [ContextScanSummary] = []
    @Default<Empty<ParticipantGroup>> var groups: [ParticipantGroup] = []
    // detail-only
    var personal: Personal?
    var accommodation: Accommodation?
    var consents: [Consent]?
    var guardians: [Guardian]?
    var medicalDetail: MedicalDetail?
    var dietaryDetail: DietaryDetail?
    var accessibility: Accessibility?
    var safeguardingDetail: SafeguardingDetail?

    var id: String { participantEventId }

    var name: String {
        if let displayName, !displayName.isBlank { return displayName }
        if let fullName, !fullName.isBlank { return fullName }
        return email ?? "Unknown"
    }
    var isCheckedIn: Bool { checkedInAt != nil }
    var isActive: Bool { status != "withdrawn" && status != "rejected" }
    var hasSafetyAlert: Bool { hasAnaphylaxisRisk || highSupportFlag || requiresRefrigeration }
    /// First block of the participant id, upper-cased: what's printed under ticket QR codes.
    var shortCode: String {
        String(participantId.split(separator: "-", maxSplits: 1).first ?? Substring(participantId)).uppercased()
    }
}

struct ParticipantsResponse: Codable, Sendable {
    @Default<Empty<Participant>> var participants: [Participant] = []
    var syncedAt: String?
}

struct ParticipantResponse: Codable, Sendable { var participant: Participant }

struct SearchResponse: Codable, Sendable {
    @Default<Empty<Participant>> var results: [Participant] = []
}

// MARK: - Notes

struct NoteAuthor: Codable, Hashable, Sendable {
    var id: String?
    var name: String?
    var email: String?
}

enum OpsNoteType: DefaultProvider { static var value: String { "ops" } }
enum NormalSensitivity: DefaultProvider { static var value: String { "normal" } }

struct Note: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var content: String
    /// ops | safeguarding | logistical
    @Default<OpsNoteType> var noteType: String = "ops"
    /// normal | restricted
    @Default<NormalSensitivity> var sensitivity: String = "normal"
    var createdAt: String?
    var author: NoteAuthor?
}

struct NotesResponse: Codable, Sendable {
    @Default<Empty<Note>> var notes: [Note] = []
}

struct NoteResponse: Codable, Sendable { var note: Note }

// MARK: - NFC

struct NfcBadge: Codable, Hashable, Sendable {
    var badgeToken: String
    @Default<False> var assigned: Bool = false
    var assignedAt: String?
    @Default<True> var success: Bool = true
}

// MARK: - Travel calendar (camelCase on the wire)

struct TravelEntry: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var participantId: String?
    var participantEventId: String?
    var participantName: String?
    var participantPreferredName: String?
    /// inbound | outbound
    var direction: String?
    var mode: String?
    var primaryTimeAt: String?
    var agendaDate: String?
    var route: String?
    var reference: String?
    var details: String?
    /// awaiting_pickup | collected | checked_in | pickup_not_needed | nil
    var pickupState: String?
    @Default<False> var isUnaccompaniedMinor: Bool = false
    @Default<Empty<ParticipantGroup>> var groups: [ParticipantGroup] = []

    var name: String {
        if let participantPreferredName, !participantPreferredName.isBlank { return participantPreferredName }
        return participantName ?? "Unknown"
    }
}

struct TravelCounts: Codable, Hashable, Sendable {
    @Default<Zero> var total: Int = 0
    @Default<Zero> var inbound: Int = 0
    @Default<Zero> var outbound: Int = 0
    @Default<Zero> var scheduled: Int = 0
    @Default<Zero> var unscheduled: Int = 0
    @Default<Zero> var awaitingPickup: Int = 0
    @Default<Zero> var collected: Int = 0
    @Default<Zero> var checkedIn: Int = 0
    @Default<Zero> var pickupNotNeeded: Int = 0
}

enum NoTravelCounts: DefaultProvider { static var value: TravelCounts { TravelCounts() } }

struct TravelCalendar: Codable, Hashable, Sendable {
    var eventTimezone: String?
    @Default<Empty<String>> var dates: [String] = []
    @Default<Empty<TravelEntry>> var entries: [TravelEntry] = []
    @Default<NoTravelCounts> var counts: TravelCounts = TravelCounts()
}

// MARK: - Slack blasts

struct SlackBlast: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var message: String
    /// pending | in_progress | completed | failed
    var status: String
    @Default<Zero> var recipientCount: Int = 0
    @Default<Zero> var sentCount: Int = 0
    @Default<Zero> var failedCount: Int = 0
    var createdAt: String?
    var sentBy: String?
}

struct SlackBlastsResponse: Codable, Sendable {
    @Default<Empty<SlackBlast>> var slackBlasts: [SlackBlast] = []
}

struct SlackBlastResponse: Codable, Sendable { var slackBlast: SlackBlast }

struct ErrorBody: Codable, Sendable {
    var error: String?
    var message: String?
}

// MARK: - JSON

/// Coders matching the Attend wire format. Also used for on-disk caches, so every cached model
/// round-trips through the same key strategy.
enum AttendJSON {
    static func decoder() -> JSONDecoder {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }

    static func encoder() -> JSONEncoder {
        let e = JSONEncoder()
        e.keyEncodingStrategy = .convertToSnakeCase
        return e
    }
}

extension String {
    var isBlank: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// nil when blank, else the string: `name.nonBlank ?? "Unknown"`.
    var nonBlank: String? { isBlank ? nil : self }
}
