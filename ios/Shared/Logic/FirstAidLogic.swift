import Foundation

// Pure logic for the first-aid sheet: who has medical or safety needs, in what order, and the
// printable HTML. Medical text is only ever used when the viewer may see sensitive data.

/// "Here now" (checked in) or "Everyone".
enum FirstAidScope: String, CaseIterable, Hashable, Sendable, Identifiable {
    case everyone, hereNow

    var id: String { rawValue }

    var label: String {
        switch self {
        case .everyone: "Everyone"
        case .hereNow: "Here now"
        }
    }
}

/// A contact to call for someone: an emergency contact or their primary guardian.
struct FirstAidContact: Hashable, Sendable {
    var name: String
    /// "Parent", "Guardian", "Aunt"…
    var relationship: String?
    var phone: String?
    var email: String?
}

/// A labelled line of medical text on a card ("Allergies: Peanuts").
struct FirstAidLine: Hashable, Sendable, Identifiable {
    var label: String
    var value: String?
    var urgent = false
    var id: String { label }
    var text: String { value ?? "" }
}

/// One person on the sheet, with everything the viewer is allowed to see.
struct FirstAidEntry: Hashable, Sendable, Identifiable {
    var participant: Participant
    var flags: [SafetyFlag]
    /// Sensitive fields: nil/empty when the viewer can't see sensitive data.
    var lifeThreateningAllergies: String?
    var allergies: String?
    var medicalConditions: String?
    var medications: String?
    var dietType: String?
    var contacts: [FirstAidContact]

    var id: String { participant.participantEventId }
    var isHere: Bool { participant.isCheckedIn }

    /// The labelled medical lines, in display order.
    var medicalLines: [FirstAidLine] {
        [FirstAidLine(label: "Life-threatening allergies", value: lifeThreateningAllergies, urgent: true),
         FirstAidLine(label: "Allergies", value: allergies),
         FirstAidLine(label: "Conditions", value: medicalConditions),
         FirstAidLine(label: "Medications", value: medications),
         FirstAidLine(label: "Diet", value: dietType)]
            .filter { $0.value?.nonBlank != nil }
    }
}

enum FirstAidLogic {
    /// Whether `p` belongs on the sheet. Medical text only counts when the viewer may see it.
    static func includes(_ p: Participant, canViewSensitive: Bool) -> Bool {
        guard p.isActive else { return false }
        if p.hasAnaphylaxisRisk || p.requiresRefrigeration || p.crossContaminationRisk || p.highSupportFlag { return true }
        guard canViewSensitive else { return false }
        return [p.lifeThreateningAllergies, p.allergies, p.medicalConditions, p.medications].contains { $0?.nonBlank != nil }
    }

    /// 0 anaphylaxis / life-threatening, 1 refrigerated medication, 2 high support, 3 everything else.
    static func rank(_ p: Participant, canViewSensitive: Bool) -> Int {
        if p.hasAnaphylaxisRisk || (canViewSensitive && p.lifeThreateningAllergies?.nonBlank != nil) { return 0 }
        if p.requiresRefrigeration { return 1 }
        if p.highSupportFlag { return 2 }
        return 3
    }

    /// Pills for the card: the list-row safety flags plus cross-contamination.
    static func flags(_ p: Participant) -> [SafetyFlag] {
        var out = Safety.flags(p)
        if p.crossContaminationRisk {
            out.append(SafetyFlag(label: "Cross-contamination risk", systemImage: "fork.knife", danger: false))
        }
        return out
    }

    /// Emergency contacts by priority, then the primary guardian (unless they're already listed).
    static func contacts(_ p: Participant) -> [FirstAidContact] {
        var out: [FirstAidContact] = []
        let sorted = (p.emergencyContacts ?? []).enumerated().sorted { a, b in
            let pa = a.element.priority ?? Int.max, pb = b.element.priority ?? Int.max
            return pa != pb ? pa < pb : a.offset < b.offset
        }.map(\.element)
        for c in sorted {
            guard let name = c.name?.nonBlank ?? c.phone?.nonBlank else { continue }
            out.append(FirstAidContact(name: name, relationship: c.relationship?.nonBlank, phone: c.phone?.nonBlank, email: c.email?.nonBlank))
        }
        if let name = p.parentGuardianName?.nonBlank {
            let phone = p.parentGuardianPhone?.nonBlank
            let duplicate = out.contains { c in
                PeopleFilter.normalize(c.name) == PeopleFilter.normalize(name)
                    && (phone == nil || c.phone.map(ContactLinks.dialable) == phone.map(ContactLinks.dialable))
            }
            if !duplicate {
                out.append(FirstAidContact(name: name, relationship: "Guardian", phone: phone, email: p.parentGuardianEmail?.nonBlank))
            }
        }
        return out
    }

    /// "gluten_free" → "Gluten free"; nil for no restriction.
    static func dietLabel(_ diet: String?) -> String? {
        guard let diet = diet?.nonBlank, !["none", "omnivore", "no_restrictions"].contains(diet.lowercased()) else { return nil }
        return PeopleText.humanize(diet)
    }

    static func entry(_ p: Participant, canViewSensitive: Bool) -> FirstAidEntry {
        FirstAidEntry(
            participant: p,
            flags: flags(p),
            lifeThreateningAllergies: canViewSensitive ? p.lifeThreateningAllergies?.nonBlank : nil,
            allergies: canViewSensitive ? p.allergies?.nonBlank : nil,
            medicalConditions: canViewSensitive ? p.medicalConditions?.nonBlank : nil,
            medications: canViewSensitive ? p.medications?.nonBlank : nil,
            dietType: canViewSensitive ? dietLabel(p.dietType) : nil,
            contacts: canViewSensitive ? contacts(p) : []
        )
    }

    /// Everyone on the sheet, most urgent first, then by name. `scope` and `query` narrow it.
    static func entries(_ participants: [Participant], canViewSensitive: Bool, scope: FirstAidScope = .everyone, query: String = "") -> [FirstAidEntry] {
        let included = participants.filter {
            includes($0, canViewSensitive: canViewSensitive)
                && (scope == .everyone || $0.isCheckedIn)
                && PeopleFilter.matchesQuery($0, query)
        }
        let keyed = included.map { (p: $0, rank: rank($0, canViewSensitive: canViewSensitive), name: PeopleFilter.normalize($0.name)) }
        return keyed.sorted { a, b in
            if a.rank != b.rank { return a.rank < b.rank }
            if a.name != b.name { return a.name < b.name }
            return a.p.participantEventId < b.p.participantEventId
        }
        .map { entry($0.p, canViewSensitive: canViewSensitive) }
    }

    /// How many on the sheet are here / in total, for the scope picker.
    static func scopeCounts(_ participants: [Participant], canViewSensitive: Bool) -> [FirstAidScope: Int] {
        let included = participants.filter { includes($0, canViewSensitive: canViewSensitive) }
        return [.everyone: included.count, .hereNow: included.count(where: \.isCheckedIn)]
    }

    // MARK: HTML

    static let sensitiveFooter = "Contains sensitive medical information. Handle and dispose of securely."
    static let restrictedNotice = "Medical details are visible to safeguarding leads and admins."

    /// Escapes text for HTML element content and attribute values.
    static func escape(_ s: String) -> String {
        var out = ""
        out.reserveCapacity(s.count)
        for c in s {
            switch c {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            case "'": out += "&#39;"
            default: out.append(c)
            }
        }
        return out
    }

    /// "<event> — First-aid sheet".
    static func title(eventName: String) -> String { "\(eventName) — First-aid sheet" }

    /// A simple printable page for the entries as filtered on screen.
    static func html(eventName: String, entries: [FirstAidEntry], canViewSensitive: Bool, scope: FirstAidScope,
                     tz: String?, rosterAt: String?, now: Date = Date()) -> String {
        let zone = Time.zone(tz)
        let generated = "\(Time.day(now, zone: zone, now: now)), \(Time.time(now, zone: zone))"
        var meta = ["Generated \(generated)", scope == .hereNow ? "People here now" : "Everyone registered",
                    "\(entries.count) \(entries.count == 1 ? "person" : "people")"]
        if let rosterAt, let ago = Time.ago(rosterAt, now: now) { meta.append("Roster synced \(ago)") }

        var body = ""
        if !canViewSensitive {
            body += "<p class=\"notice\">\(escape(restrictedNotice))</p>\n"
        }
        if entries.isEmpty {
            body += "<p>No one has medical or safety flags.</p>\n"
        }
        for e in entries {
            let p = e.participant
            var head = "<h2>\(escape(PeopleText.listTitle(p)))"
            if let pronouns = p.pronouns?.nonBlank { head += " <span class=\"muted\">(\(escape(pronouns)))</span>" }
            head += " <span class=\"status\">\(e.isHere ? "Here" : "Not here")</span></h2>"
            var card = "<div class=\"card\">\n\(head)\n"
            if !e.flags.isEmpty {
                let pills = e.flags.map { f in
                    let cls = f.danger ? "flag danger" : "flag"
                    return "<span class=\"\(cls)\">\(escape(f.label))</span>"
                }
                card += "<p class=\"flags\">" + pills.joined(separator: " ") + "</p>\n"
            }
            if canViewSensitive {
                let lines = e.medicalLines
                if !lines.isEmpty {
                    card += "<table>\n" + lines.map { "<tr><th>\(escape($0.label))</th><td>\(escape($0.text))</td></tr>" }
                        .joined(separator: "\n") + "\n</table>\n"
                }
                if !e.contacts.isEmpty {
                    card += "<p class=\"contacts\"><b>Contacts:</b> " + e.contacts.map { c in
                        var s = escape(c.name)
                        if let r = c.relationship { s += " (\(escape(r)))" }
                        if let phone = c.phone { s += " \(escape(phone))" }
                        if let email = c.email { s += " \(escape(email))" }
                        return s
                    }.joined(separator: "; ") + "</p>\n"
                }
            }
            card += "</div>\n"
            body += card
        }

        return """
        <!DOCTYPE html>
        <html><head><meta charset="utf-8"><title>\(escape(title(eventName: eventName)))</title>
        <style>
        body { font-family: -apple-system, Helvetica, Arial, sans-serif; font-size: 11pt; color: #111; }
        h1 { font-size: 18pt; margin: 0 0 4pt; }
        h2 { font-size: 13pt; margin: 0 0 4pt; }
        .meta, .muted { color: #555; }
        .meta { margin: 0 0 12pt; font-size: 10pt; }
        .card { border: 1px solid #bbb; border-radius: 6pt; padding: 8pt 10pt; margin: 0 0 8pt; page-break-inside: avoid; }
        .status { font-size: 10pt; font-weight: normal; color: #555; }
        .flag { display: inline-block; border: 1px solid #b85a00; color: #703700; border-radius: 8pt; padding: 0 6pt; font-size: 9pt; }
        .flag.danger { border-color: #c8102e; color: #93001f; font-weight: bold; }
        table { border-collapse: collapse; margin: 4pt 0; }
        th { text-align: left; padding: 1pt 10pt 1pt 0; vertical-align: top; white-space: nowrap; color: #333; }
        td { padding: 1pt 0; }
        .notice { border: 1px solid #bbb; padding: 6pt; }
        .footer { margin-top: 14pt; font-size: 9pt; color: #555; border-top: 1px solid #bbb; padding-top: 6pt; }
        </style></head><body>
        <h1>\(escape(title(eventName: eventName)))</h1>
        <p class="meta">\(meta.map(escape).joined(separator: " · "))</p>
        \(body)<p class="footer">\(escape(sensitiveFooter))</p>
        </body></html>
        """
    }
}
