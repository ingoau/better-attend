package au.ingo.betterattend.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire models for https://attend.hackclub.com/api/v1. Every id is a UUID string.
// Fields the server omits for less-privileged roles are nullable with defaults.

@Serializable
data class User(
    val id: String,
    val name: String? = null,
    val email: String,
    @SerialName("global_admin") val globalAdmin: Boolean = false,
    @SerialName("is_organizer") val isOrganizer: Boolean = false,
    @SerialName("is_participant") val isParticipant: Boolean = false,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: email.substringBefore('@')
}

@Serializable
data class SessionResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
    val user: User,
)

@Serializable
data class Event(
    val id: String,
    val name: String,
    val slug: String,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    val timezone: String? = null,
    @SerialName("location_city") val locationCity: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    /** global_admin | series_member | event_admin | safeguarding_lead | ops | limited | read_only */
    val role: String? = null,
    @SerialName("can_view_participant_pii") val canViewParticipantPii: Boolean = false,
    @SerialName("can_view_participants") val canViewParticipants: Boolean = true,
    @SerialName("can_view_sensitive_data") val canViewSensitiveData: Boolean = false,
    @SerialName("travel_enabled") val travelEnabled: Boolean = false,
)

@Serializable
data class EventsResponse(val events: List<Event> = emptyList())

// ---------- Tickets (participant side) ----------

@Serializable
data class TicketEvent(
    val id: String,
    val name: String,
    val slug: String? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    val timezone: String? = null,
    @SerialName("location_city") val locationCity: String? = null,
    @SerialName("location_address") val locationAddress: String? = null,
    @SerialName("location_country") val locationCountry: String? = null,
    @SerialName("location_latitude") val latitude: Double? = null,
    @SerialName("location_longitude") val longitude: Double? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
)

@Serializable
data class TicketTravelLeg(
    @SerialName("flight_code") val flightCode: String? = null,
    @SerialName("departure_airport") val departureAirport: String? = null,
    @SerialName("arrival_airport") val arrivalAirport: String? = null,
    @SerialName("departure_time") val departureTime: String? = null,
    @SerialName("arrival_time") val arrivalTime: String? = null,
)

@Serializable
data class TicketTravel(
    val direction: String? = null,
    val mode: String? = null,
    val carrier: String? = null,
    @SerialName("flight_number") val flightNumber: String? = null,
    @SerialName("departure_city") val departureCity: String? = null,
    @SerialName("arrival_city") val arrivalCity: String? = null,
    @SerialName("departure_time") val departureTime: String? = null,
    @SerialName("arrival_time") val arrivalTime: String? = null,
    val legs: List<TicketTravelLeg> = emptyList(),
)

@Serializable
data class TicketMessage(
    val id: String,
    val subject: String? = null,
    val body: String? = null,
    @SerialName("sender_name") val senderName: String? = null,
    @SerialName("delivered_at") val deliveredAt: String? = null,
)

@Serializable
data class Ticket(
    val id: String,
    @SerialName("participant_id") val participantId: String,
    val status: String,
    @SerialName("display_status") val displayStatus: String? = null,
    val confirmed: Boolean = false,
    @SerialName("checked_in") val checkedIn: Boolean = false,
    @SerialName("attendee_name") val attendeeName: String? = null,
    @SerialName("qr_payload") val qrPayload: String,
    @SerialName("short_code") val shortCode: String? = null,
    @SerialName("apple_wallet_url") val appleWalletUrl: String? = null,
    @SerialName("onboarding_url") val onboardingUrl: String? = null,
    val event: TicketEvent,
    // detail-only extras
    @SerialName("can_download_ticket") val canDownloadTicket: Boolean = false,
    @SerialName("can_download_excuse_letter") val canDownloadExcuseLetter: Boolean = false,
    @SerialName("travel_inbound") val travelInbound: TicketTravel? = null,
    val messages: List<TicketMessage> = emptyList(),
)

@Serializable
data class TicketsResponse(val tickets: List<Ticket> = emptyList())

@Serializable
data class TicketResponse(val ticket: Ticket)

@Serializable
data class UrlResponse(val url: String)

// ---------- Scan contexts & scans ----------

@Serializable
data class ScanContext(
    val id: String,
    val name: String,
    @SerialName("checks_in") val checksIn: Boolean = false,
    @SerialName("is_travel_pickup") val isTravelPickup: Boolean = false,
    @SerialName("is_airport") val isAirport: Boolean = false,
    val position: Int = 0,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
)

@Serializable
data class ScanContextsResponse(@SerialName("scan_contexts") val scanContexts: List<ScanContext> = emptyList())

@Serializable
data class ScanContextRef(
    val id: String,
    val name: String,
    @SerialName("checks_in") val checksIn: Boolean = false,
    @SerialName("is_travel_pickup") val isTravelPickup: Boolean = false,
)

@Serializable
data class Scan(
    val id: String,
    @SerialName("participant_id") val participantId: String? = null,
    @SerialName("participant_event_id") val participantEventId: String? = null,
    @SerialName("scanned_at") val scannedAt: String,
    @SerialName("scanned_by") val scannedBy: String? = null,
    @SerialName("client_scan_id") val clientScanId: String? = null,
    val source: String? = null,
    @SerialName("scan_context") val scanContext: ScanContextRef? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class ScansResponse(
    val scans: List<Scan> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("synced_at") val syncedAt: String? = null,
)

@Serializable
data class ScanResult(
    val success: Boolean = true,
    /** "scanned" | "already_scanned" */
    val outcome: String? = null,
    @SerialName("first_scan_in_context") val firstScanInContext: Boolean = true,
    @SerialName("first_scanned_at") val firstScannedAt: String? = null,
    val deduplicated: Boolean = false,
    val scan: Scan? = null,
    @SerialName("scan_context") val scanContext: ScanContextRef? = null,
    val participant: Participant? = null,
) {
    val isAlreadyScanned: Boolean get() = (outcome ?: if (firstScanInContext) "scanned" else "already_scanned") == "already_scanned"
}

@Serializable
data class UndoResult(
    val success: Boolean = true,
    @SerialName("deleted_scans") val deletedScans: Int = 0,
    @SerialName("participant_event_id") val participantEventId: String? = null,
    @SerialName("scan_context_id") val scanContextId: String? = null,
)

// ---------- Participants ----------

@Serializable
data class ContextScanSummary(
    @SerialName("scan_context_id") val scanContextId: String,
    @SerialName("scan_context_name") val scanContextName: String? = null,
    @SerialName("checks_in") val checksIn: Boolean = false,
    @SerialName("is_travel_pickup") val isTravelPickup: Boolean = false,
    @SerialName("scan_count") val scanCount: Int = 0,
    @SerialName("first_scanned_at") val firstScannedAt: String? = null,
    @SerialName("last_scanned_at") val lastScannedAt: String? = null,
)

@Serializable
data class EmergencyContact(
    val id: String? = null,
    val name: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val relationship: String? = null,
    val priority: Int? = null,
)

@Serializable
data class TravelLeg(
    val id: String? = null,
    val position: Int = 0,
    @SerialName("flight_code") val flightCode: String? = null,
    @SerialName("departure_airport") val departureAirport: String? = null,
    @SerialName("arrival_airport") val arrivalAirport: String? = null,
    @SerialName("departure_time") val departureTime: String? = null,
    @SerialName("arrival_time") val arrivalTime: String? = null,
    @SerialName("live_status") val liveStatus: String? = null,
    @SerialName("live_departure_time") val liveDepartureTime: String? = null,
    @SerialName("live_arrival_time") val liveArrivalTime: String? = null,
    @SerialName("travel_picked_up_at") val travelPickedUpAt: String? = null,
)

@Serializable
data class Travel(
    val id: String? = null,
    val direction: String? = null,
    /** plane | train | car | bus | other */
    val mode: String? = null,
    @SerialName("visa_required") val visaRequired: Boolean? = null,
    @SerialName("visa_status") val visaStatus: String? = null,
    @SerialName("visa_type") val visaType: String? = null,
    @SerialName("visa_number") val visaNumber: String? = null,
    @SerialName("passport_nationality") val passportNationality: String? = null,
    @SerialName("is_unaccompanied_minor") val isUnaccompaniedMinor: Boolean = false,
    val carrier: String? = null,
    @SerialName("flight_number") val flightNumber: String? = null,
    @SerialName("train_departure_station") val trainDepartureStation: String? = null,
    @SerialName("train_arrival_station") val trainArrivalStation: String? = null,
    @SerialName("departure_station") val departureStation: String? = null,
    @SerialName("arrival_station") val arrivalStation: String? = null,
    @SerialName("departure_city") val departureCity: String? = null,
    @SerialName("arrival_city") val arrivalCity: String? = null,
    @SerialName("departure_time") val departureTime: String? = null,
    @SerialName("arrival_time") val arrivalTime: String? = null,
    @SerialName("expected_arrival_time") val expectedArrivalTime: String? = null,
    @SerialName("bus_departure_location") val busDepartureLocation: String? = null,
    @SerialName("bus_arrival_location") val busArrivalLocation: String? = null,
    @SerialName("origin_address") val originAddress: String? = null,
    @SerialName("other_details") val otherDetails: String? = null,
    val notes: String? = null,
    @SerialName("pickup_dismissed_at") val pickupDismissedAt: String? = null,
    val legs: List<TravelLeg> = emptyList(),
)

@Serializable
data class Group(val id: String, val name: String, val color: String? = null)

@Serializable
data class Address(
    @SerialName("line_1") val line1: String? = null,
    @SerialName("line_2") val line2: String? = null,
    val city: String? = null,
    val state: String? = null,
    @SerialName("postal_code") val postalCode: String? = null,
    val country: String? = null,
)

@Serializable
data class Personal(
    @SerialName("legal_first_name") val legalFirstName: String? = null,
    @SerialName("legal_last_name") val legalLastName: String? = null,
    @SerialName("preferred_name") val preferredName: String? = null,
    val age: Int? = null,
    @SerialName("tshirt_size") val tshirtSize: String? = null,
    @SerialName("engagement_preference") val engagementPreference: String? = null,
    @SerialName("engagement_notes") val engagementNotes: String? = null,
    @SerialName("date_of_birth") val dateOfBirth: String? = null,
    val address: Address? = null,
)

@Serializable
data class Accommodation(
    @SerialName("check_in_date") val checkInDate: String? = null,
    @SerialName("check_out_date") val checkOutDate: String? = null,
    @SerialName("gender_identity") val genderIdentity: String? = null,
    @SerialName("gender_identity_other") val genderIdentityOther: String? = null,
    @SerialName("assigned_room") val assignedRoom: String? = null,
    @SerialName("rooming_exempt") val roomingExempt: Boolean? = null,
    @SerialName("venue_name") val venueName: String? = null,
    @SerialName("preferred_roommate_genders") val preferredRoommateGenders: List<String>? = null,
    @SerialName("roommate_preferences") val roommatePreferences: String? = null,
    @SerialName("roommate_exclusions") val roommateExclusions: String? = null,
    @SerialName("quiet_room_preference") val quietRoomPreference: Boolean? = null,
    @SerialName("room_type_preference") val roomTypePreference: String? = null,
    @SerialName("accessibility_needs") val accessibilityNeeds: String? = null,
    val notes: String? = null,
)

@Serializable
data class Consent(
    val id: String? = null,
    @SerialName("consent_type") val consentType: String? = null,
    val status: String? = null,
    @SerialName("pending_on") val pendingOn: String? = null,
    @SerialName("sent_at") val sentAt: String? = null,
    @SerialName("participant_signed_at") val participantSignedAt: String? = null,
    @SerialName("guardian_signed_at") val guardianSignedAt: String? = null,
    @SerialName("signed_at") val signedAt: String? = null,
    @SerialName("document_url") val documentUrl: String? = null,
    @SerialName("failure_reason") val failureReason: String? = null,
)

@Serializable
data class Guardian(
    val id: String? = null,
    @SerialName("guardian_id") val guardianId: String? = null,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val relationship: String? = null,
    @SerialName("is_primary") val isPrimary: Boolean = false,
    val status: String? = null,
    @SerialName("accepted_at") val acceptedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("media_permission") val mediaPermission: Boolean? = null,
    @SerialName("photo_permission") val photoPermission: Boolean? = null,
    @SerialName("travel_permission") val travelPermission: Boolean? = null,
    @SerialName("emergency_medical_consent") val emergencyMedicalConsent: Boolean? = null,
    @SerialName("otc_medication_consent") val otcMedicationConsent: Boolean? = null,
    @SerialName("emergency_contacts") val emergencyContacts: List<EmergencyContact> = emptyList(),
)

@Serializable
data class MedicalDetail(
    @SerialName("allergy_severity") val allergySeverity: String? = null,
    @SerialName("emergency_action_plan") val emergencyActionPlan: String? = null,
    @SerialName("additional_notes") val additionalNotes: String? = null,
)

@Serializable
data class DietaryDetail(
    val intolerances: String? = null,
    val notes: String? = null,
)

@Serializable
data class Accessibility(
    @SerialName("mobility_needs") val mobilityNeeds: String? = null,
    @SerialName("uses_wheelchair") val usesWheelchair: Boolean? = null,
    @SerialName("step_free_required") val stepFreeRequired: Boolean? = null,
    @SerialName("sensory_needs") val sensoryNeeds: String? = null,
    @SerialName("light_sensitivity") val lightSensitivity: Boolean? = null,
    @SerialName("noise_sensitivity") val noiseSensitivity: Boolean? = null,
    @SerialName("strobe_sensitivity") val strobeSensitivity: Boolean? = null,
    @SerialName("communication_needs") val communicationNeeds: String? = null,
    @SerialName("needs_captioning") val needsCaptioning: Boolean? = null,
    @SerialName("needs_large_print") val needsLargePrint: Boolean? = null,
    @SerialName("needs_sign_language") val needsSignLanguage: Boolean? = null,
    @SerialName("neurodivergent_notes") val neurodivergentNotes: String? = null,
    @SerialName("has_adhd") val hasAdhd: Boolean? = null,
    @SerialName("has_autism") val hasAutism: Boolean? = null,
    @SerialName("has_dyslexia") val hasDyslexia: Boolean? = null,
    @SerialName("religious_practices") val religiousPractices: String? = null,
    @SerialName("prayer_space_required") val prayerSpaceRequired: Boolean? = null,
    @SerialName("requires_private_space") val requiresPrivateSpace: Boolean? = null,
    @SerialName("distance_limitations") val distanceLimitations: String? = null,
    @SerialName("unavailable_times") val unavailableTimes: String? = null,
    @SerialName("other_needs") val otherNeeds: String? = null,
)

@Serializable
data class SafeguardingDetail(
    @SerialName("high_support_notes") val highSupportNotes: String? = null,
    @SerialName("authorized_pickup_adults") val authorizedPickupAdults: String? = null,
    @SerialName("other_instructions") val otherInstructions: String? = null,
)

/**
 * A participant's registration for one event. `participantEventId` is the id most
 * event-scoped endpoints want; `participantId` is the person (and what ticket QR codes encode).
 * The detail-only blocks (personal, accommodation, consents, …) are null in list responses.
 */
@Serializable
data class Participant(
    @SerialName("participant_id") val participantId: String,
    @SerialName("participant_event_id") val participantEventId: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    @SerialName("slack_user_id") val slackUserId: String? = null,
    val pronouns: String? = null,
    @SerialName("headshot_url") val headshotUrl: String? = null,
    @SerialName("tshirt_size") val tshirtSize: String? = null,
    /** invited | in_progress | awaiting_guardian | complete | withdrawn | rejected */
    val status: String? = null,
    @SerialName("checked_in_at") val checkedInAt: String? = null,
    @SerialName("nfc_badge_token") val nfcBadgeToken: String? = null,
    @SerialName("nfc_badge_assigned") val nfcBadgeAssigned: Boolean = false,
    @SerialName("has_anaphylaxis_risk") val hasAnaphylaxisRisk: Boolean = false,
    @SerialName("requires_refrigeration") val requiresRefrigeration: Boolean = false,
    @SerialName("cross_contamination_risk") val crossContaminationRisk: Boolean = false,
    @SerialName("high_support_flag") val highSupportFlag: Boolean = false,
    @SerialName("can_leave_unaccompanied") val canLeaveUnaccompanied: Boolean = false,
    @SerialName("waiver_signed") val waiverSigned: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
    // sensitive (global admin / safeguarding lead only)
    val allergies: String? = null,
    @SerialName("medical_conditions") val medicalConditions: String? = null,
    val medications: String? = null,
    @SerialName("diet_type") val dietType: String? = null,
    @SerialName("life_threatening_allergies") val lifeThreateningAllergies: String? = null,
    @SerialName("freedom_waiver_granted") val freedomWaiverGranted: Boolean? = null,
    @SerialName("emergency_contacts") val emergencyContacts: List<EmergencyContact>? = null,
    @SerialName("parent_guardian_name") val parentGuardianName: String? = null,
    @SerialName("parent_guardian_phone") val parentGuardianPhone: String? = null,
    @SerialName("parent_guardian_email") val parentGuardianEmail: String? = null,
    @SerialName("travel_inbound") val travelInbound: Travel? = null,
    @SerialName("travel_outbound") val travelOutbound: Travel? = null,
    @SerialName("scans_by_context") val scansByContext: List<ContextScanSummary> = emptyList(),
    val groups: List<Group> = emptyList(),
    // detail-only
    val personal: Personal? = null,
    val accommodation: Accommodation? = null,
    val consents: List<Consent>? = null,
    val guardians: List<Guardian>? = null,
    @SerialName("medical_detail") val medicalDetail: MedicalDetail? = null,
    @SerialName("dietary_detail") val dietaryDetail: DietaryDetail? = null,
    val accessibility: Accessibility? = null,
    @SerialName("safeguarding_detail") val safeguardingDetail: SafeguardingDetail? = null,
) {
    val name: String get() = displayName?.takeIf { it.isNotBlank() } ?: fullName?.takeIf { it.isNotBlank() } ?: email ?: "Unknown"
    val isCheckedIn: Boolean get() = checkedInAt != null
    val isActive: Boolean get() = status != "withdrawn" && status != "rejected"
    val hasSafetyAlert: Boolean get() = hasAnaphylaxisRisk || highSupportFlag || requiresRefrigeration
    val shortCode: String get() = participantId.substringBefore('-').uppercase()
}

@Serializable
data class ParticipantsResponse(
    val participants: List<Participant> = emptyList(),
    @SerialName("synced_at") val syncedAt: String? = null,
)

@Serializable
data class ParticipantResponse(val participant: Participant)

@Serializable
data class SearchResponse(val results: List<Participant> = emptyList())

// ---------- Notes ----------

@Serializable
data class NoteAuthor(val id: String? = null, val name: String? = null, val email: String? = null)

@Serializable
data class Note(
    val id: String,
    val content: String,
    /** ops | safeguarding | logistical */
    @SerialName("note_type") val noteType: String = "ops",
    /** normal | restricted */
    val sensitivity: String = "normal",
    @SerialName("created_at") val createdAt: String? = null,
    val author: NoteAuthor? = null,
)

@Serializable
data class NotesResponse(val notes: List<Note> = emptyList())

@Serializable
data class NoteResponse(val note: Note)

// ---------- NFC ----------

@Serializable
data class NfcBadge(
    @SerialName("badge_token") val badgeToken: String,
    val assigned: Boolean = false,
    @SerialName("assigned_at") val assignedAt: String? = null,
    val success: Boolean = true,
)

// ---------- Travel calendar (camelCase keys) ----------

@Serializable
data class TravelEntry(
    val id: String,
    val participantId: String? = null,
    val participantEventId: String? = null,
    val participantName: String? = null,
    val participantPreferredName: String? = null,
    /** inbound | outbound */
    val direction: String? = null,
    val mode: String? = null,
    val primaryTimeAt: String? = null,
    val agendaDate: String? = null,
    val route: String? = null,
    val reference: String? = null,
    val details: String? = null,
    /** awaiting_pickup | collected | checked_in | pickup_not_needed | null */
    val pickupState: String? = null,
    val isUnaccompaniedMinor: Boolean = false,
    val groups: List<Group> = emptyList(),
) {
    val name: String get() = participantPreferredName?.takeIf { it.isNotBlank() } ?: participantName ?: "Unknown"
}

@Serializable
data class TravelCounts(
    val total: Int = 0,
    val inbound: Int = 0,
    val outbound: Int = 0,
    val scheduled: Int = 0,
    val unscheduled: Int = 0,
    val awaitingPickup: Int = 0,
    val collected: Int = 0,
    val checkedIn: Int = 0,
    val pickupNotNeeded: Int = 0,
)

@Serializable
data class TravelCalendar(
    val eventTimezone: String? = null,
    val dates: List<String> = emptyList(),
    val entries: List<TravelEntry> = emptyList(),
    val counts: TravelCounts = TravelCounts(),
)

// ---------- Slack blasts ----------

@Serializable
data class SlackBlast(
    val id: String,
    val message: String,
    /** pending | in_progress | completed | failed */
    val status: String,
    @SerialName("recipient_count") val recipientCount: Int = 0,
    @SerialName("sent_count") val sentCount: Int = 0,
    @SerialName("failed_count") val failedCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("sent_by") val sentBy: String? = null,
)

@Serializable
data class SlackBlastsResponse(@SerialName("slack_blasts") val slackBlasts: List<SlackBlast> = emptyList())

@Serializable
data class SlackBlastResponse(@SerialName("slack_blast") val slackBlast: SlackBlast)

@Serializable
data class ErrorBody(val error: String? = null, val message: String? = null)
