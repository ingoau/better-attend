import Foundation

/// Where the session token lives (Keychain in the app, memory in tests).
@MainActor
protocol TokenStore: AnyObject {
    var token: String? { get }
    func update(token: String?, expiresAt: String?)
}

/// Client for https://attend.hackclub.com/api/v1. MainActor-isolated: requests suspend rather than
/// block, and the token bookkeeping stays race-free.
@MainActor
final class AttendAPI {
    static let productionBaseURL = URL(string: "https://attend.hackclub.com")!

    var baseURL: URL
    let session: URLSession
    private let tokens: TokenStore
    /// Called when a request made with the current token comes back 401.
    var onSessionExpired: () -> Void = {}

    /// The in-flight rotation, so parallel callers share it instead of rotating twice.
    private var refreshTask: Task<SessionResponse?, Never>?
    /// Token a background rotation has been started for.
    private var rotatingFor: String?

    private let userAgent: String = {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        return "BetterAttend-iOS/\(version)"
    }()

    init(tokens: TokenStore, baseURL: URL = AttendAPI.productionBaseURL, session: URLSession? = nil) {
        self.tokens = tokens
        self.baseURL = baseURL
        self.session = session ?? {
            let config = URLSessionConfiguration.default
            config.timeoutIntervalForRequest = 30
            config.timeoutIntervalForResource = 45
            config.waitsForConnectivity = false
            return URLSession(configuration: config)
        }()
    }

    // MARK: Low level

    private func request(_ method: String, _ path: String, query: [String: String?], body: [String: String]?, jsonBody: Data? = nil, token: String?) -> URLRequest {
        var components = URLComponents(url: baseURL.appending(path: "api/v1" + path), resolvingAgainstBaseURL: false)!
        let items = query.compactMap { k, v in v.map { URLQueryItem(name: k, value: $0) } }.sorted { $0.name < $1.name }
        if !items.isEmpty { components.queryItems = items }
        var req = URLRequest(url: components.url!)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        if let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let jsonBody {
            req.httpBody = jsonBody
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        } else if let body {
            req.httpBody = try? JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        } else if ["POST", "PUT", "PATCH"].contains(method) {
            req.httpBody = Data("{}".utf8)
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        return req
    }

    private func send(_ req: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            let (data, response) = try await session.data(for: req)
            guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
            return (data, http)
        } catch let e as URLError where e.code == .cancelled {
            throw CancellationError()
        }
    }

    /// Performs a request, returning the raw body. A 401 caused by a concurrent token rotation is
    /// retried once with the new token instead of signing the user out.
    func raw(
        _ method: String,
        _ path: String,
        query: [String: String?] = [:],
        body: [String: String]? = nil,
        jsonBody: Data? = nil,
        authenticated: Bool = true
    ) async throws -> Data {
        let usedToken = authenticated ? tokens.token : nil
        var sentToken = usedToken
        var (data, res) = try await send(request(method, path, query: query, body: body, jsonBody: jsonBody, token: usedToken))
        if res.statusCode == 401, authenticated, let usedToken {
            // Let any in-flight rotation finish, then retry if the token changed underneath us.
            _ = await refreshTask?.value
            if let current = tokens.token, current != usedToken {
                sentToken = current
                (data, res) = try await send(request(method, path, query: query, body: body, jsonBody: jsonBody, token: current))
            }
        }
        guard (200..<300).contains(res.statusCode) else {
            // Only expire the session if the token that failed is still the current one.
            if res.statusCode == 401, authenticated, sentToken != nil, sentToken == tokens.token {
                onSessionExpired()
            }
            throw APIError(status: res.statusCode, message: Self.parseError(res.statusCode, data))
        }
        // The hint is about the token this request carried; ignore it if that's already been rotated.
        if authenticated, let sentToken, sentToken == tokens.token,
           res.value(forHTTPHeaderField: "X-Token-Refresh-Recommended") == "true", rotatingFor != sentToken {
            rotatingFor = sentToken
            Task { _ = await refreshSession(expected: sentToken) }
        }
        return data
    }

    static func parseError(_ code: Int, _ data: Data) -> String {
        if let body = try? JSONDecoder().decode(ErrorBody.self, from: data), let msg = body.error ?? body.message {
            return msg
        }
        let text = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        if code == 429 { return "Too many requests" }
        if !text.isEmpty, text.count < 160, !text.hasPrefix("<") { return text }
        return "Server error (\(code))"
    }

    private func get<T: Decodable>(_ path: String, query: [String: String?] = [:]) async throws -> T {
        try AttendJSON.decoder().decode(T.self, from: await raw("GET", path, query: query))
    }

    private func send<T: Decodable>(_ method: String, _ path: String, body: [String: String]? = nil, query: [String: String?] = [:]) async throws -> T {
        try AttendJSON.decoder().decode(T.self, from: await raw(method, path, query: query, body: body))
    }

    /// Sends an Encodable body (for nested JSON the `[String: String]` body can't express).
    private func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, encoding body: B) async throws -> T {
        let data = try AttendJSON.encoder().encode(body)
        return try AttendJSON.decoder().decode(T.self, from: await raw(method, path, jsonBody: data))
    }

    // MARK: Session

    func createSession(code: String, redirectURI: String, codeVerifier: String, deviceName: String) async throws -> SessionResponse {
        let data = try await raw("POST", "/session", body: [
            "code": code, "redirect_uri": redirectURI, "code_verifier": codeVerifier, "device_name": deviceName,
        ], authenticated: false)
        return try AttendJSON.decoder().decode(SessionResponse.self, from: data)
    }

    /// Rotates the token. Serialized, and skipped if `expected` has already been rotated away:
    /// the server revokes the old token immediately, so rotating twice would sign us out.
    @discardableResult
    func refreshSession(expected: String? = nil) async -> SessionResponse? {
        if let running = refreshTask { return await running.value }
        guard let current = tokens.token else { return nil }
        if let expected, expected != current { return nil }
        let task = Task<SessionResponse?, Never> {
            guard let (data, res) = try? await send(request("POST", "/session/refresh", query: [:], body: nil, token: current)),
                  (200..<300).contains(res.statusCode),
                  let session = try? AttendJSON.decoder().decode(SessionResponse.self, from: data) else { return nil }
            tokens.update(token: session.token, expiresAt: session.expiresAt)
            return session
        }
        refreshTask = task
        let result = await task.value
        refreshTask = nil
        return result
    }

    func deleteSession() async { _ = try? await raw("DELETE", "/session") }

    func me() async throws -> User { try await get("/me") }

    // MARK: Events

    func events() async throws -> [Event] { try await (get("/events") as EventsResponse).events }

    func scanContexts(eventId: String) async throws -> [ScanContext] {
        try await (get("/events/\(eventId)/scan_contexts") as ScanContextsResponse).scanContexts.sorted { $0.position < $1.position }
    }

    // MARK: Scans

    func createScan(
        eventId: String,
        participantId: String? = nil,
        badgeToken: String? = nil,
        scanContextId: String? = nil,
        source: String? = nil,
        clientScanId: String,
        scannedAt: String
    ) async throws -> ScanResult {
        var body = ["client_scan_id": clientScanId, "scanned_at": scannedAt]
        body["participant_id"] = participantId
        body["badge_token"] = badgeToken
        body["scan_context_id"] = scanContextId
        body["source"] = source
        return try await send("POST", "/events/\(eventId)/scans", body: body)
    }

    func scans(eventId: String, since: String? = nil, scanContextId: String? = nil) async throws -> ScansResponse {
        try await get("/events/\(eventId)/scans", query: ["since": since, "scan_context_id": scanContextId])
    }

    /// Undo: deletes this participant's scans in one context, or in every context when `scanContextId` is nil.
    func undoScans(eventId: String, participantEventId: String, scanContextId: String? = nil) async throws -> UndoResult {
        try await send("DELETE", "/events/\(eventId)/scans/\(participantEventId)", query: ["scan_context_id": scanContextId])
    }

    // MARK: Participants

    func participants(eventId: String, updatedSince: String? = nil) async throws -> ParticipantsResponse {
        try await get("/events/\(eventId)/participants", query: ["updated_since": updatedSince])
    }

    func participant(eventId: String, participantEventId: String) async throws -> Participant {
        try await (get("/events/\(eventId)/participants/\(participantEventId)") as ParticipantResponse).participant
    }

    func searchParticipants(eventId: String, query: String) async throws -> [Participant] {
        try await (get("/events/\(eventId)/participants/search", query: ["q": query]) as SearchResponse).results
    }

    func updateParticipantStatus(eventId: String, participantEventId: String, status: String) async throws -> Participant {
        try await (send("PATCH", "/events/\(eventId)/participants/\(participantEventId)", body: ["status": status]) as ParticipantResponse).participant
    }

    private struct ParticipantEditBody: Encodable { var participant: ParticipantEdit }

    /// Edits the person's profile. Only `edit`'s non-nil fields are sent.
    func updateParticipant(eventId: String, participantEventId: String, edit: ParticipantEdit) async throws -> Participant {
        try await (send("PATCH", "/events/\(eventId)/participants/\(participantEventId)", encoding: ParticipantEditBody(participant: edit)) as ParticipantResponse).participant
    }

    /// Adds someone to the roster as `invited` and emails their invitation (event admins only).
    func inviteParticipant(eventId: String, email: String, firstName: String?, lastName: String?) async throws -> InviteResult {
        var body = ["email": email]
        body["first_name"] = firstName
        body["last_name"] = lastName
        return try await send("POST", "/events/\(eventId)/participants", body: body)
    }

    /// Removes this registration (and its travel, consents and scans) from the event (event admins only).
    func deleteParticipant(eventId: String, participantEventId: String) async throws {
        _ = try await raw("DELETE", "/events/\(eventId)/participants/\(participantEventId)")
    }

    // MARK: Staff

    func staff(eventId: String) async throws -> StaffResponse { try await get("/events/\(eventId)/staff") }

    func addStaff(eventId: String, email: String, role: String) async throws -> StaffMemberResponse {
        try await send("POST", "/events/\(eventId)/staff", body: ["email": email, "role": role])
    }

    func updateStaffRole(eventId: String, assignmentId: String, role: String) async throws -> StaffMember {
        try await (send("PATCH", "/events/\(eventId)/staff/\(assignmentId)", body: ["role": role]) as StaffMemberResponse).staffMember
    }

    func removeStaff(eventId: String, assignmentId: String) async throws {
        _ = try await raw("DELETE", "/events/\(eventId)/staff/\(assignmentId)")
    }

    // MARK: Notes

    func notes(eventId: String, participantEventId: String) async throws -> [Note] {
        try await (get("/events/\(eventId)/participants/\(participantEventId)/notes") as NotesResponse).notes
    }

    func createNote(eventId: String, participantEventId: String, content: String, noteType: String, sensitivity: String) async throws -> Note {
        try await (send("POST", "/events/\(eventId)/participants/\(participantEventId)/notes", body: [
            "content": content, "note_type": noteType, "sensitivity": sensitivity,
        ]) as NoteResponse).note
    }

    // MARK: NFC badges

    func nfcEnsure(eventId: String, participantEventId: String) async throws -> NfcBadge {
        try await send("POST", "/events/\(eventId)/participant_events/\(participantEventId)/nfc_badge/ensure")
    }

    func nfcConfirm(eventId: String, participantEventId: String, badgeToken: String) async throws -> NfcBadge {
        try await send("POST", "/events/\(eventId)/participant_events/\(participantEventId)/nfc_badge/confirm", body: ["badge_token": badgeToken])
    }

    func nfcReset(eventId: String, participantEventId: String) async throws -> NfcBadge {
        try await send("POST", "/events/\(eventId)/participant_events/\(participantEventId)/nfc_badge/reset")
    }

    // MARK: Travel

    func travel(eventId: String) async throws -> TravelCalendar { try await get("/events/\(eventId)/travel") }

    // MARK: Slack blasts

    func slackBlasts(eventId: String) async throws -> [SlackBlast] {
        try await (get("/events/\(eventId)/slack_blasts") as SlackBlastsResponse).slackBlasts
    }

    func slackBlast(eventId: String, id: String) async throws -> SlackBlast {
        try await (get("/events/\(eventId)/slack_blasts/\(id)") as SlackBlastResponse).slackBlast
    }

    func sendSlackBlast(eventId: String, message: String) async throws -> SlackBlast {
        try await (send("POST", "/events/\(eventId)/slack_blasts", body: ["message": message]) as SlackBlastResponse).slackBlast
    }

    // MARK: Tickets

    func tickets() async throws -> [Ticket] { try await (get("/tickets") as TicketsResponse).tickets }

    func ticket(id: String) async throws -> Ticket { try await (get("/tickets/\(id)") as TicketResponse).ticket }

    /// Downloads the Apple Wallet pass for a ticket. `appleWalletUrl` may be absolute or API-relative.
    func walletPass(from urlString: String) async throws -> Data {
        guard let url = URL(string: urlString, relativeTo: baseURL)?.absoluteURL else { throw URLError(.badURL) }
        var req = URLRequest(url: url)
        req.setValue("application/vnd.apple.pkpass", forHTTPHeaderField: "Accept")
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        // Only send the token to Attend itself, never to a third-party pass host.
        if url.host == baseURL.host, let token = tokens.token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, res) = try await send(req)
        guard (200..<300).contains(res.statusCode) else {
            throw APIError(status: res.statusCode, message: Self.parseError(res.statusCode, data))
        }
        return data
    }
}
