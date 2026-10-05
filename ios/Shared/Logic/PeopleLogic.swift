import Foundation

// Pure logic for the People list and Participant detail (ported from the Android `PeopleFilters.kt`,
// `ParticipantVisuals.kt`, `ParticipantSections.kt`, `DetailDialogs.kt` and the note validation in
// `ParticipantDetailViewModel.kt`). No UI imports, so it's unit tested directly.

// MARK: - Filters

/// The chips above the list. Each is a preset that's cheap to count for every participant.
enum QuickFilter: String, CaseIterable, Hashable, Sendable, Identifiable {
    case all, here, notHere, needsAttention, notComplete, withdrawn

    var id: String { rawValue }

    var label: String {
        switch self {
        case .all: "All"
        case .here: "Here"
        case .notHere: "Not here"
        case .needsAttention: "Needs attention"
        case .notComplete: "Not complete"
        case .withdrawn: "Withdrawn"
        }
    }

    func matches(_ p: Participant) -> Bool {
        switch self {
        case .all: p.isActive
        case .here: p.isActive && p.isCheckedIn
        case .notHere: p.isActive && p.status == "complete" && !p.isCheckedIn
        case .needsAttention: p.isActive && p.hasSafetyAlert
        case .notComplete: p.isActive && p.status != "complete"
        case .withdrawn: !p.isActive
        }
    }

    /// Title for the empty list when this chip matches nobody.
    var emptyTitle: String {
        switch self {
        case .here: "No One's Checked In Yet"
        case .notHere: "Everyone Confirmed Is Here"
        case .needsAttention: "No Safety Alerts"
        case .notComplete: "Every Registration Is Complete"
        case .withdrawn: "No One Has Withdrawn"
        case .all: "No One Matches These Filters"
        }
    }
}

/// Three-way switch used by the filter sheet ("Any / Yes / No").
enum TriState: String, CaseIterable, Hashable, Sendable {
    case any, yes, no

    func test(_ value: Bool) -> Bool {
        switch self {
        case .any: true
        case .yes: value
        case .no: !value
        }
    }
}

/// List order. (Named so it doesn't clash with Foundation's `SortOrder`.)
enum PeopleSort: String, CaseIterable, Hashable, Sendable, Identifiable {
    case name, arrival, status

    var id: String { rawValue }

    var label: String {
        switch self {
        case .name: "Name"
        case .arrival: "Arrival"
        case .status: "Status"
        }
    }
}

/// Advanced filters from the sheet. Everything defaults to "don't care".
struct FilterOptions: Hashable, Sendable {
    /// Only people scanned at this context (or, with `notScannedAtContext`, people who weren't).
    var scannedAtContextId: String?
    var notScannedAtContext = false
    /// Empty = any status.
    var statuses: Set<String> = []
    var inboundTravel: TriState = .any
    var outboundTravel: TriState = .any
    /// Empty = any mode; otherwise either direction uses one of these.
    var travelModes: Set<String> = []
    var waiverSigned: TriState = .any
    var nfcAssigned: TriState = .any
    /// Empty = any diet. Only honoured when the viewer can see sensitive data.
    var dietTypes: Set<String> = []

    /// How many independent rules are on (for the "Filters · 3" badge).
    var activeCount: Int {
        [scannedAtContextId != nil, !statuses.isEmpty, inboundTravel != .any, outboundTravel != .any,
         !travelModes.isEmpty, waiverSigned != .any, nfcAssigned != .any, !dietTypes.isEmpty].count(where: { $0 })
    }

    func matches(_ p: Participant) -> Bool {
        if let scannedAtContextId {
            let scanned = p.scansByContext.contains { $0.scanContextId == scannedAtContextId && $0.scanCount > 0 }
            if scanned == notScannedAtContext { return false }
        }
        if !statuses.isEmpty && !statuses.contains(p.status ?? "unknown") { return false }
        if !inboundTravel.test(p.travelInbound != nil) { return false }
        if !outboundTravel.test(p.travelOutbound != nil) { return false }
        if !travelModes.isEmpty {
            let modes = [p.travelInbound?.mode, p.travelOutbound?.mode].compactMap { $0 }
            if !modes.contains(where: travelModes.contains) { return false }
        }
        if !waiverSigned.test(p.waiverSigned) { return false }
        if !nfcAssigned.test(p.nfcBadgeAssigned) { return false }
        if !dietTypes.isEmpty && !dietTypes.contains(p.dietType ?? "none") { return false }
        return true
    }
}

struct FilterResult: Hashable, Sendable {
    var participants: [Participant]
    /// Live counts for each chip, after search and advanced filters (but before the chip itself).
    var counts: [QuickFilter: Int]
}

/// An alphabetical section of the list ("A", "B", … "#"), or the one unlabelled section when not sorted by name.
struct PeopleSection: Hashable, Sendable, Identifiable {
    var letter: String?
    var participants: [Participant]
    var id: String { letter ?? "_all" }
}

enum PeopleFilter {
    /// Lower-cases and strips accents so "zoe" finds "Zoë".
    static func normalize(_ s: String) -> String {
        let scalars = s.decomposedStringWithCanonicalMapping.unicodeScalars.filter { $0.properties.generalCategory != .nonspacingMark }
        return String(String.UnicodeScalarView(scalars)).lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Every whitespace-separated term must appear in one of: display name, full name, email,
    /// pronouns, or the short code / ids (so a ticket code typed at the desk works).
    static func matchesQuery(_ p: Participant, _ query: String) -> Bool {
        let terms = normalize(query).split(whereSeparator: \.isWhitespace)
        if terms.isEmpty { return true }
        let haystack = [p.displayName, p.fullName, p.email, p.pronouns].compactMap { $0 }.map(normalize).joined(separator: " ")
            + " " + p.shortCode.lowercased() + " " + p.participantEventId.lowercased() + " " + p.participantId.lowercased()
        return terms.allSatisfy { haystack.contains($0) }
    }

    static func apply(
        _ participants: [Participant],
        query: String,
        quick: QuickFilter,
        options: FilterOptions,
        sort: PeopleSort,
        canViewSensitive: Bool
    ) -> FilterResult {
        var effective = options
        if !canViewSensitive { effective.dietTypes = [] }
        let base = participants.filter { matchesQuery($0, query) && effective.matches($0) }
        var counts: [QuickFilter: Int] = [:]
        for f in QuickFilter.allCases { counts[f] = base.count(where: f.matches) }
        return FilterResult(participants: sorted(base.filter(quick.matches), sort), counts: counts)
    }

    private static let statusRank = ["complete": 1, "awaiting_guardian": 2, "in_progress": 3, "invited": 4, "withdrawn": 5, "rejected": 6]

    /// Display order of raw statuses (filter sheet).
    static let statusOrder = ["complete", "awaiting_guardian", "in_progress", "invited", "withdrawn", "rejected"]

    static func sorted(_ list: [Participant], _ sort: PeopleSort) -> [Participant] {
        // Precompute keys once: normalising in the comparator would redo it n log n times.
        let keyed = list.map { p in
            (p: p, name: normalize(p.name), full: normalize(p.fullName ?? ""), at: Time.parse(p.checkedInAt),
             rank: p.isActive && p.isCheckedIn ? 0 : statusRank[p.status ?? ""] ?? 9)
        }
        func byName(_ a: (p: Participant, name: String, full: String, at: Date?, rank: Int),
                    _ b: (p: Participant, name: String, full: String, at: Date?, rank: Int)) -> Bool {
            if a.name != b.name { return a.name < b.name }
            if a.full != b.full { return a.full < b.full }
            return a.p.participantEventId < b.p.participantEventId
        }
        let ordered: [(p: Participant, name: String, full: String, at: Date?, rank: Int)] = switch sort {
        case .name:
            keyed.sorted(by: byName)
        case .arrival:
            keyed.sorted { a, b in
                let ta = a.at ?? .distantPast, tb = b.at ?? .distantPast
                return ta != tb ? ta > tb : byName(a, b)
            }
        case .status:
            keyed.sorted { a, b in a.rank != b.rank ? a.rank < b.rank : byName(a, b) }
        }
        return ordered.map(\.p)
    }

    /// Letter used for section headers: A–Z, else "#".
    static func sectionLetter(_ p: Participant) -> String {
        guard let c = normalize(p.name).first?.uppercased(), let scalar = c.unicodeScalars.first,
              c.count == 1, ("A"..."Z").contains(c), scalar.isASCII else { return "#" }
        return c
    }

    /// Alphabetical sections when sorted by name; otherwise one unlabelled section.
    static func sections(_ list: [Participant], _ sort: PeopleSort) -> [PeopleSection] {
        guard sort == .name else { return list.isEmpty ? [] : [PeopleSection(letter: nil, participants: list)] }
        var out: [PeopleSection] = []
        for p in list {
            let letter = sectionLetter(p)
            if out.last?.letter == letter { out[out.count - 1].participants.append(p) } else { out.append(PeopleSection(letter: letter, participants: [p])) }
        }
        return out
    }

    /// The check-in scan (earliest in a checks_in context), for "Checked in 9:41 · Check-in desk".
    static func checkInScan(_ p: Participant) -> ContextScanSummary? {
        p.scansByContext
            .filter { $0.checksIn && $0.firstScannedAt != nil }
            .min { (Time.parse($0.firstScannedAt) ?? .distantFuture) < (Time.parse($1.firstScannedAt) ?? .distantFuture) }
    }

    /// "Checked in 9:41 AM · Check-in desk", or nil when not checked in.
    static func checkInLine(_ p: Participant, tz: String?) -> String? {
        guard p.isCheckedIn else { return nil }
        guard let time = Time.time(p.checkedInAt, tz: tz) else { return "Checked in" }
        return (["Checked in \(time)", checkInScan(p)?.scanContextName?.nonBlank].compactMap { $0 }).joined(separator: " · ")
    }
}

// MARK: - Status & labels

/// How a participant's state reads in lists and headers: label + SF Symbol + tone, so it never relies on colour alone.
struct StatusVisual: Hashable, Sendable {
    enum Kind: Hashable, Sendable { case here, notHere, awaitingParent, invited, registering, inactive }
    var label: String
    var systemImage: String
    var kind: Kind

    static func of(_ p: Participant) -> StatusVisual {
        if p.status == "withdrawn" { return StatusVisual(label: "Withdrawn", systemImage: "nosign", kind: .inactive) }
        if p.status == "rejected" { return StatusVisual(label: "Rejected", systemImage: "nosign", kind: .inactive) }
        if p.isCheckedIn { return StatusVisual(label: "Here", systemImage: "checkmark.circle.fill", kind: .here) }
        return switch p.status {
        case "complete": StatusVisual(label: "Not here", systemImage: "circle.dashed", kind: .notHere)
        case "awaiting_guardian": StatusVisual(label: "Awaiting parent", systemImage: "figure.and.child.holdinghands", kind: .awaitingParent)
        case "invited": StatusVisual(label: "Invited", systemImage: "envelope.badge", kind: .invited)
        default: StatusVisual(label: "Registering", systemImage: "hourglass", kind: .registering)
        }
    }
}

enum PeopleText {
    /// Human label for a raw registration status.
    static func status(_ status: String?) -> String {
        switch status {
        case "invited": "Invited"
        case "in_progress": "Registering"
        case "awaiting_guardian": "Awaiting parent"
        case "complete": "Complete"
        case "withdrawn": "Withdrawn"
        case "rejected": "Rejected"
        case nil: "Unknown"
        case let s?: humanize(s) ?? s
        }
    }

    /// "custom_document" → "Custom document".
    static func humanize(_ s: String?) -> String? {
        guard let s, !s.isBlank else { return nil }
        return s.replacingOccurrences(of: "_", with: " ").capitalizedFirst
    }

    static func yesNo(_ b: Bool?) -> String? { b.map { $0 ? "Yes" : "No" } }

    static func consent(_ type: String?) -> String {
        switch type {
        case "event_consent": "Event consent"
        case "medical_release": "Medical release"
        case "code_of_conduct": "Code of conduct"
        case "media", "media_release": "Media release"
        case "waiver": "Waiver"
        case "participant_agreement": "Participant agreement"
        case "freedom_waiver": "Freedom waiver"
        case "custom_document": "Custom document"
        default: humanize(type) ?? "Document"
        }
    }

    static func travelMode(_ mode: String?) -> String {
        switch mode {
        case "plane": "Flight"
        case "train": "Train"
        case "car": "Car"
        case "bus": "Bus"
        case nil: "Travel"
        default: "Other"
        }
    }

    static func travelModeSymbol(_ mode: String?) -> String {
        switch mode {
        case "plane": "airplane"
        case "train": "tram.fill"
        case "car": "car.fill"
        case "bus": "bus.fill"
        default: "figure.walk"
        }
    }

    /// Filter-sheet options for travel mode.
    static let travelModes = ["plane", "train", "car", "bus", "other"]

    static func travelModeOption(_ mode: String) -> String {
        switch mode {
        case "plane": "Plane"
        default: travelMode(mode)
        }
    }

    /// Row title: the full name when it starts with the preferred name ("Leo" → "Leo Nguyen"), else
    /// both ("Sam (Samantha Lee)"), so people who share a first name are told apart.
    static func listTitle(_ p: Participant) -> String {
        guard let full = p.fullName?.nonBlank?.trimmingCharacters(in: .whitespaces), full != p.name else { return p.name }
        return PeopleFilter.normalize(full).hasPrefix(PeopleFilter.normalize(p.name)) ? full : "\(p.name) (\(full))"
    }

    /// "3 Oct 2026" from "2026-10-03" (falls back to the raw text).
    static func date(_ iso: String?) -> String? {
        guard let iso, !iso.isBlank else { return nil }
        guard let d = CalendarDay(iso: iso) else { return iso }
        let utc = TimeZone(identifier: "UTC")!
        return d.start(in: utc).formatted(Date.FormatStyle(date: .abbreviated, time: .omitted, timeZone: utc))
    }
}

// MARK: - Safety

/// A safety alert shown first on the detail screen.
struct SafetyAlert: Hashable, Sendable {
    var title: String
    var detail: String?
    var systemImage: String
    var danger: Bool
}

/// Compact flags for list rows.
struct SafetyFlag: Hashable, Sendable {
    var label: String
    var systemImage: String
    var danger: Bool
}

enum Safety {
    static func flags(_ p: Participant) -> [SafetyFlag] {
        var out: [SafetyFlag] = []
        if p.hasAnaphylaxisRisk { out.append(SafetyFlag(label: "Anaphylaxis risk", systemImage: "allergens", danger: true)) }
        if p.requiresRefrigeration { out.append(SafetyFlag(label: "Refrigerated medication", systemImage: "snowflake", danger: false)) }
        if p.highSupportFlag { out.append(SafetyFlag(label: "High support needs", systemImage: "hand.raised.fill", danger: false)) }
        if p.travelInbound?.isUnaccompaniedMinor == true || p.travelOutbound?.isUnaccompaniedMinor == true {
            out.append(SafetyFlag(label: "Unaccompanied minor travel", systemImage: "figure.child", danger: false))
        }
        return out
    }

    static func alerts(_ p: Participant, canViewSensitive: Bool) -> [SafetyAlert] {
        var out: [SafetyAlert] = []
        if p.hasAnaphylaxisRisk {
            var detail: String?
            if canViewSensitive {
                let lt = p.lifeThreateningAllergies?.nonBlank
                let all = p.allergies?.nonBlank
                if let lt {
                    if let all {
                        if all.localizedCaseInsensitiveContains(lt) { detail = all }
                        else if lt.localizedCaseInsensitiveContains(all) { detail = lt }
                        else { detail = "\(lt) · \(all)" }
                    } else {
                        detail = lt
                    }
                } else {
                    detail = all
                }
            }
            out.append(SafetyAlert(title: "Anaphylaxis risk", detail: detail ?? "Check their allergy plan with first aid.",
                                   systemImage: "allergens", danger: true))
        }
        if p.requiresRefrigeration {
            out.append(SafetyAlert(title: "Medication must be refrigerated", detail: canViewSensitive ? p.medications?.nonBlank : nil,
                                   systemImage: "snowflake", danger: false))
        }
        if p.highSupportFlag {
            out.append(SafetyAlert(title: "High support needs", detail: canViewSensitive ? p.safeguardingDetail?.highSupportNotes?.nonBlank : nil,
                                   systemImage: "hand.raised.fill", danger: false))
        }
        if p.crossContaminationRisk && canViewSensitive {
            out.append(SafetyAlert(title: "Cross-contamination risk", detail: "Prepare their food separately.",
                                   systemImage: "fork.knife", danger: false))
        }
        let minor = p.personal?.age.map { $0 < 18 } ?? !(p.guardians ?? []).isEmpty
        if !p.canLeaveUnaccompanied && minor {
            out.append(SafetyAlert(title: "Can't leave unaccompanied",
                                   detail: p.safeguardingDetail?.authorizedPickupAdults?.nonBlank.map { "Pickup: \($0)" },
                                   systemImage: "figure.walk.departure", danger: false))
        }
        let um = [p.travelInbound?.isUnaccompaniedMinor == true ? "arriving" : nil,
                  p.travelOutbound?.isUnaccompaniedMinor == true ? "departing" : nil].compactMap { $0 }
        if !um.isEmpty {
            out.append(SafetyAlert(title: "Unaccompanied minor", detail: "Travelling alone when \(um.joined(separator: " and ")). Meet them at the gate.",
                                   systemImage: "figure.child", danger: false))
        }
        return out
    }
}

// MARK: - Detail

enum ParticipantDetailLogic {
    /// PATCH participants is limited to direct edit roles (safeguarding leads and series-only members get 403).
    static func canChangeStatus(_ event: Event?) -> Bool { EventPermissions.canEditParticipant(event) }

    /// Default selection for undo: the only context, else the check-in context, else everything (nil).
    static func defaultUndoSelection(_ scans: [ContextScanSummary]) -> String? {
        if scans.count == 1 { return scans[0].scanContextId }
        return scans.first(where: \.checksIn)?.scanContextId
    }

    /// One row per scan point: every known context, plus any scanned context that's since been removed.
    struct ScanPoint: Hashable, Sendable, Identifiable {
        var id: String
        var name: String
        var checksIn: Bool
        var isTravelPickup: Bool
        var scan: ContextScanSummary?
    }

    static func scanPoints(_ p: Participant, contexts: [ScanContext]) -> [ScanPoint] {
        var points = contexts.sorted { $0.position < $1.position }.map { c in
            ScanPoint(id: c.id, name: c.name, checksIn: c.checksIn, isTravelPickup: c.isTravelPickup || c.isAirport,
                      scan: p.scansByContext.first { $0.scanContextId == c.id && $0.scanCount > 0 })
        }
        let known = Set(contexts.map(\.id))
        for s in p.scansByContext where !known.contains(s.scanContextId) {
            points.append(ScanPoint(id: s.scanContextId, name: s.scanContextName ?? "Scan point", checksIn: s.checksIn,
                                    isTravelPickup: s.isTravelPickup, scan: s))
        }
        return points
    }

    /// The live roster copy, with detail-only fields filled in from the last detail fetch (a delta sync
    /// replaces the roster copy with list data, which leaves those out).
    static func overlay(live: Participant?, detail: Participant?) -> Participant? {
        guard var m = live else { return detail }
        guard let d = detail, d.participantEventId == m.participantEventId else { return m }
        m = Roster.mergeKeepingDetail(d, m)
        m.lifeThreateningAllergies = m.lifeThreateningAllergies ?? d.lifeThreateningAllergies
        m.allergies = m.allergies ?? d.allergies
        m.medicalConditions = m.medicalConditions ?? d.medicalConditions
        m.medications = m.medications ?? d.medications
        m.dietType = m.dietType ?? d.dietType
        m.freedomWaiverGranted = m.freedomWaiverGranted ?? d.freedomWaiverGranted
        m.emergencyContacts = m.emergencyContacts ?? d.emergencyContacts
        m.parentGuardianName = m.parentGuardianName ?? d.parentGuardianName
        m.parentGuardianPhone = m.parentGuardianPhone ?? d.parentGuardianPhone
        m.parentGuardianEmail = m.parentGuardianEmail ?? d.parentGuardianEmail
        m.travelInbound = m.travelInbound ?? d.travelInbound
        m.travelOutbound = m.travelOutbound ?? d.travelOutbound
        m.phone = m.phone ?? d.phone
        m.slackUserId = m.slackUserId ?? d.slackUserId
        return m
    }

    /// The page's copy after a profile edit, for when the full profile can't be fetched again: the
    /// PATCH answer (roster shape) over the old detail, then every edited field set from the edit
    /// itself, so a field that was just cleared (a phone number, say) isn't brought back from the old
    /// copy, and legal names, birthday and size show the edit.
    static func applying(_ edit: ParticipantEdit, live: Participant, to detail: Participant?, now: Date = Date()) -> Participant {
        var p = overlay(live: live, detail: detail) ?? live
        func value(_ s: String) -> String? { s.trimmingCharacters(in: .whitespacesAndNewlines).nonBlank }
        if let v = edit.phone { p.phone = value(v) }
        if let v = edit.pronouns { p.pronouns = value(v) }
        if let v = edit.email, let email = value(v) { p.email = email }
        if let v = edit.tshirtSize { p.tshirtSize = value(v) }
        let touchesPersonal = [edit.legalFirstName, edit.legalLastName, edit.preferredName, edit.tshirtSize, edit.dateOfBirth]
            .contains { $0 != nil }
        guard touchesPersonal else { return p }
        var personal = p.personal ?? Personal()
        if let v = edit.legalFirstName { personal.legalFirstName = value(v) }
        if let v = edit.legalLastName { personal.legalLastName = value(v) }
        if let v = edit.preferredName { personal.preferredName = value(v) }
        if let v = edit.tshirtSize { personal.tshirtSize = value(v) }
        if let v = edit.dateOfBirth {
            let born = CalendarDay(iso: value(v))
            personal.dateOfBirth = born?.description
            personal.age = born.map { born in
                let today = CalendarDay(now, in: .current)
                let hadBirthday = today.month > born.month || (today.month == born.month && today.day >= born.day)
                return today.year - born.year - (hadBirthday ? 0 : 1)
            }
        }
        p.personal = personal
        return p
    }

    /// Attend web admin page for a participant.
    static func webURL(event: Event?, participantEventId: String) -> URL? {
        guard let slug = event?.slug else { return nil }
        return URL(string: "https://attend.hackclub.com/admin/events/\(slug)/participants/\(participantEventId)")
    }
}

// MARK: - Browsing (previous / next person)

/// Paging through the People list from the detail screen. The list passes the ids as displayed
/// (`Route.participant(…, browseIds:)`); opened from anywhere else there's just the one person.
enum BrowseOrder {
    /// The ids to page through: `browseIds` without duplicates when it contains `current`, else just `current`.
    static func pages(_ browseIds: [String], current: String) -> [String] {
        var seen = Set<String>()
        let ids = browseIds.filter { seen.insert($0).inserted }
        return ids.contains(current) ? ids : [current]
    }

    static func previous(_ pages: [String], current: String) -> String? {
        guard let i = pages.firstIndex(of: current), i > 0 else { return nil }
        return pages[i - 1]
    }

    static func next(_ pages: [String], current: String) -> String? {
        guard let i = pages.firstIndex(of: current), i + 1 < pages.count else { return nil }
        return pages[i + 1]
    }

    /// "3 of 42", or nil for a single person.
    static func position(_ pages: [String], current: String) -> String? {
        guard pages.count > 1, let i = pages.firstIndex(of: current) else { return nil }
        return "\(i + 1) of \(pages.count)"
    }
}

// MARK: - Notes

enum NoteRules {
    static let maxLength = 1000
    static let types = ["ops", "safeguarding", "logistical"]
    static let sensitivities = ["normal", "restricted"]

    /// Client-side validation (the API 500s on bad enums). Returns an error message or nil.
    static func validate(_ content: String, type: String, sensitivity: String) -> String? {
        if content.isBlank { return "Write something first." }
        if content.trimmingCharacters(in: .whitespacesAndNewlines).count > maxLength { return "Notes can be up to \(maxLength) characters." }
        if !types.contains(type) { return "Pick a note type." }
        if !sensitivities.contains(sensitivity) { return "Pick a sensitivity." }
        return nil
    }

    static func typeLabel(_ t: String) -> String {
        switch t {
        case "safeguarding": "Safeguarding"
        case "logistical": "Logistics"
        default: "Ops"
        }
    }

    static func typeSymbol(_ t: String) -> String {
        switch t {
        case "safeguarding": "shield.lefthalf.filled"
        case "logistical": "shippingbox"
        default: "wrench.and.screwdriver"
        }
    }
}

// MARK: - Contact links

enum ContactLinks {
    /// Keeps a leading "+" and digits: "+61 400 000 000" → "+61400000000".
    static func dialable(_ phone: String) -> String {
        var out = ""
        for (i, c) in phone.trimmingCharacters(in: .whitespaces).enumerated() where c.isASCII && (c.isNumber || (c == "+" && i == 0)) {
            out.append(c)
        }
        return out
    }

    static func call(_ phone: String) -> URL? { nonEmpty(dialable(phone)).flatMap { URL(string: "tel:\($0)") } }
    static func sms(_ phone: String) -> URL? { nonEmpty(dialable(phone)).flatMap { URL(string: "sms:\($0)") } }
    static func faceTime(_ phone: String) -> URL? { nonEmpty(dialable(phone)).flatMap { URL(string: "facetime:\($0)") } }
    static func faceTimeAudio(_ phone: String) -> URL? { nonEmpty(dialable(phone)).flatMap { URL(string: "facetime-audio:\($0)") } }

    /// wa.me wants digits only, with the country code.
    static func whatsApp(_ phone: String) -> URL? {
        nonEmpty(phone.filter { $0.isASCII && $0.isNumber }).flatMap { URL(string: "https://wa.me/\($0)") }
    }

    static func email(_ address: String) -> URL? {
        let a = address.trimmingCharacters(in: .whitespaces)
        guard a.contains("@"), let enc = a.addingPercentEncoding(withAllowedCharacters: .urlUserAllowed.union(["@"])) else { return nil }
        return URL(string: "mailto:\(enc)")
    }

    /// Hack Club's Slack, where Attend's Slack IDs live.
    static let slackTeamId = "T0266FRGM"

    /// Slack's deep link: opens a DM with the user in the app.
    static func slack(_ userId: String) -> URL? {
        slackId(userId).flatMap { URL(string: "slack://user?team=\(slackTeamId)&id=\($0)") }
    }

    /// Their profile on the web, for when the Slack app isn't installed.
    static func slackWeb(_ userId: String) -> URL? {
        slackId(userId).flatMap { URL(string: "https://hackclub.slack.com/team/\($0)") }
    }

    private static func slackId(_ userId: String) -> String? {
        nonEmpty(userId.trimmingCharacters(in: .whitespaces).addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "")
    }

    private static func nonEmpty(_ s: String) -> String? { s.isEmpty || s == "+" ? nil : s }
}
