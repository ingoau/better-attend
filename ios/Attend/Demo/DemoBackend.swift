import Foundation

/// A stateful fake of the Attend API for demo mode and UI tests. Scans check people in, undo
/// removes them, notes and announcements are stored, and announcements "deliver" over a few seconds.
///
/// Launch with `-AttendDemo YES` to use it; add `-AttendDemoOffline YES` to simulate no network.
final class DemoBackend: @unchecked Sendable {
    static let shared = DemoBackend()

    /// Launch arguments land in UserDefaults' argument domain.
    static var isEnabled: Bool { UserDefaults.standard.bool(forKey: "AttendDemo") }

    private let lock = NSLock()
    private var data: DemoData = {
        var d = DemoData()
        // `-AttendDemoParticipant YES`: signed in as a participant only, so Tickets is the only tab.
        if UserDefaults.standard.bool(forKey: "AttendDemoParticipant") { d.user.isOrganizer = false }
        return d
    }()
    private var seenClientScanIds: Set<String> = []
    private var offline = UserDefaults.standard.bool(forKey: "AttendDemoOffline")
    private var blastCreated: [String: Date] = [:]
    /// Event staff per event id (only the main event has any).
    private lazy var staff: [String: [StaffMember]] = [DemoData.mainEventId: DemoData.demoStaff(user: data.user)]
    /// Profiles edited through PATCH participants, keyed by participant event id. The generated detail
    /// is derived from the roster copy, so edited legal names and birthdays are kept here.
    private var profiles: [String: Personal] = [:]

    static func makeSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [DemoURLProtocol.self]
        return URLSession(configuration: config)
    }

    @MainActor
    func signIn(_ auth: AuthStore) {
        let user = lock.withLock { data.user }
        auth.signIn(with: SessionResponse(token: "demo-token", expiresAt: Time.iso(Date().addingTimeInterval(14 * 86_400)), user: user))
    }

    var isOffline: Bool {
        get { lock.withLock { offline } }
        set { lock.withLock { offline = newValue } }
    }

    // MARK: Routing

    struct Reply {
        var status: Int
        var body: Data
        var delay: TimeInterval = 0.25
    }

    func handle(method: String, url: URL, body: Data?) -> Result<Reply, URLError> {
        lock.lock()
        defer { lock.unlock() }
        if offline { return .failure(URLError(.notConnectedToInternet)) }
        let path = url.path.replacingOccurrences(of: "/api/v1", with: "")
        let parts = path.split(separator: "/").map(String.init)
        let query = Dictionary(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.map { ($0.name, $0.value ?? "") } ?? [],
                               uniquingKeysWith: { a, _ in a })
        let json = body.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: String] } ?? [:]

        /// Matches "events/*/scans"-style patterns, returning the wildcard segments.
        func match(_ verb: String, _ pattern: String) -> [String]? {
            let pp = pattern.split(separator: "/").map(String.init)
            guard verb == method, pp.count == parts.count else { return nil }
            var captures: [String] = []
            for (p, actual) in zip(pp, parts) {
                if p == "*" { captures.append(actual) } else if p != actual { return nil }
            }
            return captures
        }

        if match("GET", "me") != nil { return ok(data.user) }
        if match("POST", "session/refresh") != nil {
            return ok(SessionResponse(token: "demo-token", expiresAt: Time.iso(Date().addingTimeInterval(14 * 86_400)), user: data.user))
        }
        if match("DELETE", "session") != nil { return .success(Reply(status: 204, body: Data())) }
        if match("GET", "events") != nil { return ok(EventsResponse(events: data.events)) }
        if match("GET", "tickets") != nil { return ok(TicketsResponse(tickets: currentTickets())) }
        // Wallet passes must be signed with an Apple certificate, which demo mode doesn't have.
        if match("GET", "tickets/*/wallet") != nil { return error(422, "Apple Wallet passes aren't available in demo mode.") }
        if let c = match("GET", "tickets/*") {
            guard let t = currentTickets().first(where: { $0.id == c[0] }) else { return notFound() }
            return ok(TicketResponse(ticket: t))
        }
        if let c = match("GET", "events/*/scan_contexts") { return ok(ScanContextsResponse(scanContexts: data.contexts[c[0]] ?? [])) }
        if let c = match("GET", "events/*/participants") {
            let e = c[0]
            guard canView(e) else { return forbidden() }
            let all = data.participants[e] ?? []
            let since = query["updated_since"].flatMap(Time.parse)
            let list = since.map { s in all.filter { (Time.parse($0.updatedAt) ?? .distantPast) > s } } ?? all
            return ok(ParticipantsResponse(participants: list, syncedAt: Time.nowISO()), delay: 0.45)
        }
        if let c = match("GET", "events/*/participants/search") {
            guard canView(c[0]) else { return forbidden() }
            return ok(SearchResponse(results: RosterSearch.filter(data.participants[c[0]] ?? [], query: query["q"] ?? "", limit: 20)))
        }
        if let c = match("GET", "events/*/participants/*") {
            guard let p = participant(c[0], c[1]) else { return notFound() }
            return ok(ParticipantResponse(participant: detailed(p)))
        }
        if let c = match("PATCH", "events/*/participants/*") {
            guard var p = participant(c[0], c[1]) else { return notFound() }
            guard ["global_admin", "event_admin", "ops", "limited"].contains(role(c[0])) else { return forbidden() }
            let object = body.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
            let fields = object?["participant"] as? [String: String] ?? [:]
            let status = json["status"]
            if fields.isEmpty && status == nil { return error(422, "Nothing to update. Send a participant object and/or a status.") }
            if !fields.isEmpty, let problem = applyEdit(c[0], &p, fields) { return error(422, problem) }
            if let status { p.status = status }
            save(c[0], p)
            let saved = participant(c[0], c[1]) ?? p
            // Like upstream, a profile edit answers with the roster (short) shape.
            return ok(ParticipantResponse(participant: fields.isEmpty ? detailed(saved) : saved))
        }
        if let c = match("POST", "events/*/participants") { return invite(c[0], json) }
        if let c = match("DELETE", "events/*/participants/*") {
            guard ["global_admin", "event_admin"].contains(role(c[0])) else { return forbidden() }
            guard participant(c[0], c[1]) != nil else { return notFound() }
            data.participants[c[0]]?.removeAll { $0.participantEventId == c[1] }
            profiles[c[1]] = nil
            data.notes[c[1]] = nil
            return .success(Reply(status: 204, body: Data()))
        }
        if let c = match("GET", "events/*/staff") {
            guard canManageStaff(c[0]) else { return forbidden() }
            return ok(StaffResponse(staff: staff[c[0]] ?? [], roles: DemoData.staffRoles))
        }
        if let c = match("POST", "events/*/staff") { return addStaff(c[0], json) }
        if let c = match("PATCH", "events/*/staff/*") { return updateStaff(c[0], c[1], json) }
        if let c = match("DELETE", "events/*/staff/*") { return removeStaff(c[0], c[1]) }
        if let c = match("GET", "events/*/participants/*/notes") {
            guard participant(c[0], c[1]) != nil else { return notFound() }
            return ok(NotesResponse(notes: data.notes[c[1]] ?? defaultNotes(c[1])))
        }
        if let c = match("POST", "events/*/participants/*/notes") {
            let pe = c[1]
            guard participant(c[0], pe) != nil, let content = json["content"] else { return notFound() }
            let note = Note(id: UUID().uuidString.lowercased(), content: content, noteType: json["note_type"] ?? "ops",
                            sensitivity: json["sensitivity"] ?? "normal", createdAt: Time.nowISO(),
                            author: NoteAuthor(id: data.user.id, name: data.user.name, email: data.user.email))
            data.notes[pe] = [note] + (data.notes[pe] ?? defaultNotes(pe))
            return ok(NoteResponse(note: note), status: 201)
        }
        if let c = match("POST", "events/*/scans") { return scan(c[0], json) }
        if let c = match("GET", "events/*/scans") { return ok(ScansResponse(scans: feed(c[0]), hasMore: false, syncedAt: Time.nowISO())) }
        if let c = match("DELETE", "events/*/scans/*") { return undo(c[0], c[1], context: query["scan_context_id"]) }
        if let c = match("POST", "events/*/participant_events/*/nfc_badge/*") {
            let e = c[0]
            guard var p = participant(e, c[1]) else { return notFound() }
            switch c[2] {
            case "ensure":
                if p.nfcBadgeToken == nil { p.nfcBadgeToken = "b\(UUID().uuidString.prefix(8).lowercased())-badge"; save(e, p) }
            case "confirm": p.nfcBadgeAssigned = true; save(e, p)
            case "reset": p.nfcBadgeToken = "b\(UUID().uuidString.prefix(8).lowercased())-badge"; p.nfcBadgeAssigned = false; save(e, p)
            default: return notFound()
            }
            return ok(NfcBadge(badgeToken: p.nfcBadgeToken!, assigned: p.nfcBadgeAssigned, assignedAt: p.nfcBadgeAssigned ? Time.nowISO() : nil))
        }
        if let c = match("GET", "events/*/travel") {
            guard c[0] == DemoData.mainEventId else { return error(422, "Travel isn't enabled for this event") }
            return ok(currentTravel())
        }
        if match("GET", "events/*/slack_blasts") != nil { return ok(SlackBlastsResponse(slackBlasts: data.blasts.map(progressed))) }
        if let c = match("GET", "events/*/slack_blasts/*") {
            guard let b = data.blasts.first(where: { $0.id == c[1] }) else { return notFound() }
            return ok(SlackBlastResponse(slackBlast: progressed(b)))
        }
        if let c = match("POST", "events/*/slack_blasts") {
            guard let message = json["message"], !message.isBlank else { return error(422, "Message can't be blank") }
            let recipients = (data.participants[c[0]] ?? []).count(where: { $0.status == "complete" && $0.slackUserId != nil })
            let b = SlackBlast(id: UUID().uuidString.lowercased(), message: message, status: "pending", recipientCount: recipients,
                               createdAt: Time.nowISO(), sentBy: data.user.name)
            blastCreated[b.id] = Date()
            data.blasts.insert(b, at: 0)
            return ok(SlackBlastResponse(slackBlast: b), status: 201)
        }
        return notFound()
    }

    // MARK: Participant edits & invites

    /// The generated detail, with any edited profile fields on top.
    private func detailed(_ p: Participant) -> Participant {
        var d = data.detail(of: p)
        if let profile = profiles[p.participantEventId] { d.personal = profile }
        return d
    }

    /// Applies `participant` fields from a PATCH. Returns a validation message, like upstream's 422s.
    private func applyEdit(_ eventId: String, _ p: inout Participant, _ fields: [String: String]) -> String? {
        if let raw = fields["email"] {
            let email = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard ParticipantEditLogic.looksLikeEmail(email) else { return "Email is invalid" }
            let id = p.participantEventId
            let taken = (data.participants[eventId] ?? []).contains { $0.participantEventId != id && $0.email?.lowercased() == email }
            if taken { return "Email has already been taken" }
            p.email = email
        }
        var profile = profiles[p.participantEventId] ?? data.detail(of: p).personal ?? Personal()
        if let v = fields["legal_first_name"] {
            if v.isBlank { return "Legal first name can't be blank" }
            profile.legalFirstName = v
        }
        if let v = fields["legal_last_name"] { profile.legalLastName = v.nonBlank }
        if let v = fields["preferred_name"] { profile.preferredName = v.nonBlank }
        if let v = fields["tshirt_size"] {
            profile.tshirtSize = v.nonBlank
            p.tshirtSize = v.nonBlank
        }
        if let v = fields["date_of_birth"] {
            if v.isBlank {
                profile.dateOfBirth = nil
                profile.age = nil
            } else {
                guard let day = CalendarDay(iso: v) else { return "Date of birth is invalid" }
                let today = CalendarDay(Date(), in: Time.zone(DemoData.timezone))
                let birthdayPassed = today.month > day.month || (today.month == day.month && today.day >= day.day)
                profile.dateOfBirth = day.description
                profile.age = today.year - day.year - (birthdayPassed ? 0 : 1)
            }
        }
        if let v = fields["phone"] { p.phone = v.nonBlank }
        if let v = fields["pronouns"] { p.pronouns = v.nonBlank }
        profiles[p.participantEventId] = profile
        let first = profile.legalFirstName?.nonBlank
        p.fullName = [first, profile.legalLastName?.nonBlank].compactMap { $0 }.joined(separator: " ").nonBlank ?? p.fullName
        p.displayName = profile.preferredName?.nonBlank ?? first ?? p.displayName
        return nil
    }

    /// POST participants, with upstream's checks and messages.
    private func invite(_ eventId: String, _ body: [String: String]) -> Result<Reply, URLError> {
        guard let event = data.events.first(where: { $0.id == eventId }) else { return notFound() }
        guard event.canViewParticipants, ["global_admin", "series_member", "event_admin"].contains(event.role ?? "") else { return forbidden() }
        let email = (body["email"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if email.isEmpty { return error(422, "Email is required") }
        guard ParticipantEditLogic.looksLikeEmail(email) else { return error(422, "Invalid email format") }
        if email.hasSuffix("@banned.example") { return error(422, "This email is banned from events") }
        let list = data.participants[eventId] ?? []
        if let existing = list.first(where: { $0.email?.lowercased() == email }) {
            return error(409, existing.status == "invited" ? "An invitation has already been sent to this email"
                                                           : "This email is already registered for this event")
        }
        let first = body["first_name"]?.trimmingCharacters(in: .whitespacesAndNewlines).nonBlank
        let last = body["last_name"]?.trimmingCharacters(in: .whitespacesAndNewlines).nonBlank
        let p = Participant(
            participantId: UUID().uuidString.lowercased(),
            participantEventId: UUID().uuidString.lowercased(),
            displayName: first,
            fullName: [first, last].compactMap { $0 }.joined(separator: " ").nonBlank,
            email: email,
            status: "invited",
            updatedAt: Time.nowISO()
        )
        data.participants[eventId] = Roster.sortedByName(list + [p])
        if first != nil || last != nil {
            profiles[p.participantEventId] = Personal(legalFirstName: first, legalLastName: last)
        }
        // The upcoming event holds onboarding invitations, like an event that hasn't opened yet.
        let held = eventId == DemoData.upcomingEventId
        let result = InviteResult(success: true, held: held, message: held ? "Invitation held for \(email)" : "Invitation sent to \(email)",
                                  event: event.name, participantId: p.participantId, participantEventId: p.participantEventId,
                                  status: p.status)
        return ok(result, status: 201)
    }

    // MARK: Staff

    private func addStaff(_ eventId: String, _ body: [String: String]) -> Result<Reply, URLError> {
        guard canManageStaff(eventId) else { return forbidden() }
        let email = (body["email"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard ParticipantEditLogic.looksLikeEmail(email) else { return error(422, "A valid email address is required") }
        let role = body["role"] ?? ""
        guard DemoData.staffRoles.contains(where: { $0.role == role }) else { return error(422, invalidRole(role)) }
        if (staff[eventId] ?? []).contains(where: { $0.user.email.lowercased() == email }) {
            return error(422, "User is already on the staff of this event")
        }
        let known = staff.values.joined().first { $0.user.email.lowercased() == email }?.user
        let localPart = String(email.split(separator: "@").first ?? "")
        let user = known ?? StaffUser(id: UUID().uuidString.lowercased(), email: email,
                                      name: localPart.replacingOccurrences(of: ".", with: " ").capitalized)
        let member = StaffMember(id: UUID().uuidString.lowercased(), role: role, roleLabel: DemoData.staffRoleLabel(role),
                                 createdAt: Time.nowISO(), user: user)
        staff[eventId, default: []].append(member)
        return ok(StaffMemberResponse(staffMember: member, accountCreated: known == nil), status: 201)
    }

    private func updateStaff(_ eventId: String, _ id: String, _ body: [String: String]) -> Result<Reply, URLError> {
        guard canManageStaff(eventId) else { return forbidden() }
        guard var list = staff[eventId], let i = list.firstIndex(where: { $0.id == id }) else { return notFound() }
        let role = body["role"] ?? ""
        guard DemoData.staffRoles.contains(where: { $0.role == role }) else { return error(422, invalidRole(role)) }
        list[i].role = role
        list[i].roleLabel = DemoData.staffRoleLabel(role)
        staff[eventId] = list
        let member = list[i]
        // Your own role on the event follows your assignment (global admins keep theirs).
        if member.user.email.lowercased() == data.user.email.lowercased(),
           let e = data.events.firstIndex(where: { $0.id == eventId }), data.events[e].role != "global_admin" {
            data.events[e].role = role
        }
        return ok(StaffMemberResponse(staffMember: member))
    }

    private func removeStaff(_ eventId: String, _ id: String) -> Result<Reply, URLError> {
        guard canManageStaff(eventId) else { return forbidden() }
        guard let member = staff[eventId]?.first(where: { $0.id == id }) else { return notFound() }
        if member.inheritedFromSeries {
            let seriesRole = member.seriesRole ?? "member"
            return error(409, "This person is a series \(seriesRole) — their access is inherited from the series, "
                + "so it can't be removed here. Manage them from the series members page.")
        }
        staff[eventId]?.removeAll { $0.id == id }
        // Removing yourself takes the event off your list, like the real server.
        if member.user.email.lowercased() == data.user.email.lowercased() {
            data.events.removeAll { $0.id == eventId }
        }
        return .success(Reply(status: 204, body: Data()))
    }

    private func invalidRole(_ role: String) -> String {
        let valid = DemoData.staffRoles.map(\.role).joined(separator: ", ")
        return "\(role.isEmpty ? "role" : role) is not a valid role. Valid roles: \(valid)"
    }

    // MARK: Scans

    private func scan(_ eventId: String, _ body: [String: String]) -> Result<Reply, URLError> {
        let contexts = data.contexts[eventId] ?? []
        guard let contextId = body["scan_context_id"] ?? (contexts.count == 1 ? contexts.first?.id : nil),
              let context = contexts.first(where: { $0.id == contextId }) else {
            return error(422, "scan_context_id is required for this event")
        }
        var list = data.participants[eventId] ?? []
        let raw = (body["participant_id"] ?? "")
            .replacingOccurrences(of: "attend://checkin/", with: "")
            .replacingOccurrences(of: "attend:P:", with: "")
            .lowercased()
        guard let i = list.firstIndex(where: { p in
            if let token = body["badge_token"] { return p.nfcBadgeToken?.lowercased() == token.lowercased() }
            return p.participantId.lowercased() == raw || p.participantEventId.lowercased() == raw
        }) else {
            return error(404, "Participant not found")
        }
        let deduplicated = body["client_scan_id"].map { !seenClientScanIds.insert($0).inserted } ?? false
        let now = Time.nowISO()
        var p = list[i]
        let ref = ScanContextRef(id: context.id, name: context.name, checksIn: context.checksIn, isTravelPickup: context.isTravelPickup)
        if let s = p.scansByContext.firstIndex(where: { $0.scanContextId == context.id && $0.scanCount > 0 }) {
            let first = p.scansByContext[s].firstScannedAt
            if !deduplicated {
                p.scansByContext[s].scanCount += 1
                p.scansByContext[s].lastScannedAt = now
                p.updatedAt = now
                list[i] = p
                data.participants[eventId] = list
            }
            return ok(ScanResult(outcome: deduplicated ? "scanned" : "already_scanned", firstScanInContext: deduplicated, firstScannedAt: first,
                                 deduplicated: deduplicated, scan: Scan(id: UUID().uuidString, participantId: p.participantId,
                                                                        participantEventId: p.participantEventId, scannedAt: now, scanContext: ref),
                                 scanContext: ref, participant: p))
        }
        p.scansByContext.append(ContextScanSummary(scanContextId: context.id, scanContextName: context.name, checksIn: context.checksIn,
                                                   isTravelPickup: context.isTravelPickup, scanCount: 1, firstScannedAt: now, lastScannedAt: now))
        if context.checksIn && p.checkedInAt == nil { p.checkedInAt = now }
        p.updatedAt = now
        list[i] = p
        data.participants[eventId] = list
        return ok(ScanResult(outcome: "scanned", firstScanInContext: true, firstScannedAt: now,
                             scan: Scan(id: UUID().uuidString, participantId: p.participantId, participantEventId: p.participantEventId,
                                        scannedAt: now, clientScanId: body["client_scan_id"], source: body["source"], scanContext: ref),
                             scanContext: ref, participant: p))
    }

    private func undo(_ eventId: String, _ pe: String, context: String?) -> Result<Reply, URLError> {
        guard var p = participant(eventId, pe) else { return notFound() }
        let before = p.scansByContext.reduce(0) { $0 + $1.scanCount }
        p.scansByContext = context.map { c in p.scansByContext.filter { $0.scanContextId != c } } ?? []
        let deleted = before - p.scansByContext.reduce(0) { $0 + $1.scanCount }
        p.checkedInAt = p.scansByContext.filter(\.checksIn).compactMap(\.firstScannedAt).min()
        p.updatedAt = Time.nowISO()
        save(eventId, p)
        return ok(UndoResult(deletedScans: deleted, participantEventId: pe, scanContextId: context))
    }

    private func feed(_ eventId: String) -> [Scan] {
        let scans = (data.participants[eventId] ?? []).flatMap { p in
            p.scansByContext.compactMap { s in
                s.firstScannedAt.map {
                    Scan(id: "\(p.participantEventId)-\(s.scanContextId)", participantId: p.participantId, participantEventId: p.participantEventId,
                         scannedAt: $0, scannedBy: "Heidi",
                         scanContext: ScanContextRef(id: s.scanContextId, name: s.scanContextName ?? "Scan point", checksIn: s.checksIn))
                }
            }
        }
        return Array(scans.sorted { $0.scannedAt > $1.scannedAt }.prefix(100))
    }

    // MARK: Helpers

    private func role(_ eventId: String) -> String {
        data.events.first { $0.id == eventId }?.role ?? ""
    }

    private func canManageStaff(_ eventId: String) -> Bool {
        ["global_admin", "series_member", "event_admin"].contains(role(eventId))
    }

    private func canView(_ eventId: String) -> Bool {
        data.events.first { $0.id == eventId }?.canViewParticipants ?? false
    }

    private func participant(_ eventId: String, _ pe: String) -> Participant? {
        data.participants[eventId]?.first { $0.participantEventId == pe }
    }

    private func save(_ eventId: String, _ p: Participant) {
        var p = p
        p.updatedAt = Time.nowISO()
        guard let i = data.participants[eventId]?.firstIndex(where: { $0.participantEventId == p.participantEventId }) else { return }
        data.participants[eventId]?[i] = p
    }

    /// Tickets reflect the organizer's own check-in at the main event.
    private func currentTickets() -> [Ticket] {
        let me = participant(DemoData.mainEventId, "3b1f9a2c-8d7e-4f60-9a1b-2c3d4e5f6a7b")
        return data.tickets.map { t in
            var t = t
            if t.event.id == DemoData.mainEventId { t.checkedIn = me?.isCheckedIn ?? false }
            return t
        }
    }

    /// Pickup states follow airport-pickup and check-in scans, like the real server.
    private func currentTravel() -> TravelCalendar {
        var cal = data.travel
        let people = Dictionary((data.participants[DemoData.mainEventId] ?? []).map { ($0.participantEventId, $0) }, uniquingKeysWith: { a, _ in a })
        cal.entries = cal.entries.map { e in
            guard e.direction == "inbound", e.pickupState == "awaiting_pickup", let p = e.participantEventId.flatMap({ people[$0] }) else { return e }
            var e = e
            if p.scansByContext.contains(where: { $0.isTravelPickup }) { e.pickupState = "collected" }
            return e
        }
        cal.counts = DemoData.counts(cal.entries)
        return cal
    }

    private func progressed(_ b: SlackBlast) -> SlackBlast {
        guard let created = blastCreated[b.id] else { return b }
        var b = b
        let elapsed = Date().timeIntervalSince(created)
        if elapsed < 1.5 { return b }
        let done = min(b.recipientCount, Int((elapsed - 1.5) * 18))
        b.failedCount = done >= b.recipientCount && b.recipientCount > 3 ? 1 : 0
        b.sentCount = done - b.failedCount
        b.status = done >= b.recipientCount ? "completed" : "in_progress"
        return b
    }

    private func defaultNotes(_ pe: String) -> [Note] {
        let seed = pe.utf8.reduce(0) { ($0 &* 31 &+ Int($1)) & 0x7fff_ffff }
        guard seed % 3 == 0 else { return [] }
        return [
            Note(id: "n1-\(pe)", content: "Arrived with parent, EpiPen handed to first aid.", noteType: "ops",
                 createdAt: Time.iso(Date().addingTimeInterval(-1800)), author: NoteAuthor(name: "Orpheus Dino")),
            Note(id: "n2-\(pe)", content: "Prefers the quiet room at night.", noteType: "logistical", sensitivity: "restricted",
                 createdAt: Time.iso(Date().addingTimeInterval(-18_000)), author: NoteAuthor(name: "Heidi")),
        ]
    }

    private func ok<T: Encodable>(_ value: T, status: Int = 200, delay: TimeInterval = 0.25) -> Result<Reply, URLError> {
        .success(Reply(status: status, body: (try? AttendJSON.encoder().encode(value)) ?? Data(), delay: delay))
    }

    private func error(_ status: Int, _ message: String) -> Result<Reply, URLError> {
        .success(Reply(status: status, body: Data("{\"error\":\"\(message)\"}".utf8)))
    }

    private func notFound() -> Result<Reply, URLError> { error(404, "Not found") }
    private func forbidden() -> Result<Reply, URLError> { error(403, "Forbidden") }
}

/// Routes every request of a demo `URLSession` to `DemoBackend`.
final class DemoURLProtocol: URLProtocol, @unchecked Sendable {
    private var cancelled = false

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        let body = request.httpBody ?? request.httpBodyStream.map(Self.read)
        let result = DemoBackend.shared.handle(method: request.httpMethod ?? "GET", url: url, body: body)
        let delay: TimeInterval = (try? result.get().delay) ?? 0.4
        // URLProtocol must call its client on the thread (run loop) that started loading;
        // answering from another queue can stall requests under load.
        let timer = Timer(timeInterval: delay, repeats: false) { [self] _ in
            guard !cancelled else { return }
            switch result {
            case .failure(let error):
                client?.urlProtocol(self, didFailWithError: error)
            case .success(let reply):
                let res = HTTPURLResponse(url: url, statusCode: reply.status, httpVersion: "HTTP/1.1",
                                          headerFields: ["Content-Type": "application/json"])!
                client?.urlProtocol(self, didReceive: res, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: reply.body)
                client?.urlProtocolDidFinishLoading(self)
            }
        }
        RunLoop.current.add(timer, forMode: .common)
    }

    override func stopLoading() { cancelled = true }

    private static func read(_ stream: InputStream) -> Data {
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let n = stream.read(&buffer, maxLength: buffer.count)
            if n <= 0 { break }
            data.append(buffer, count: n)
        }
        return data
    }
}
