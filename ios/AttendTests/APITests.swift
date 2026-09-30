import Foundation
import Testing
@testable import Attend

/// Serves canned responses to an `AttendAPI` under test.
final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    typealias Handler = @Sendable (URLRequest) -> (Int, [String: String], String)
    nonisolated(unsafe) static var handler: Handler = { _ in (404, [:], "{}") }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        let (status, headers, body) = Self.handler(request)
        let res = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: res, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    static func session() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: config)
    }
}

/// Thread-safe counter for handler bookkeeping.
final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var n = 0
    var value: Int { lock.withLock { n } }
    func increment() { lock.withLock { n += 1 } }
}

@MainActor
@Suite(.serialized) struct AttendAPITests {
    nonisolated static let me = #"{"id":"u1","email":"a@b.c"}"#

    @Test func parallelRefreshHintsRotateOnlyOnceAndNobodyIsSignedOut() async throws {
        let refreshes = Counter()
        StubURLProtocol.handler = { req in
            let auth = req.value(forHTTPHeaderField: "Authorization")
            switch req.url!.path {
            case "/api/v1/session/refresh":
                refreshes.increment()
                return auth == "Bearer old"
                    ? (200, [:], #"{"token":"new","expires_at":"2026-12-01T00:00:00Z","user":\#(Self.me)}"#)
                    : (401, [:], #"{"error":"Unauthorized"}"#)
            case "/api/v1/me":
                if auth == "Bearer new" { return (200, [:], Self.me) }
                // Old token: still valid until rotated; ask the client to refresh.
                if auth == "Bearer old", refreshes.value == 0 { return (200, ["X-Token-Refresh-Recommended": "true"], Self.me) }
                return (401, [:], #"{"error":"Unauthorized"}"#)
            default:
                return (404, [:], #"{"error":"Not found"}"#)
            }
        }
        let tokens = MemoryTokenStore(token: "old")
        let api = AttendAPI(tokens: tokens, baseURL: URL(string: "https://attend.test")!, session: StubURLProtocol.session())
        var expired = false
        api.onSessionExpired = { expired = true }

        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<8 { group.addTask { _ = try? await api.me() } }
        }
        for _ in 0..<50 where tokens.token != "new" { try await Task.sleep(for: .milliseconds(20)) }
        #expect(tokens.token == "new")
        #expect(refreshes.value == 1)
        #expect(try await api.me().id == "u1")
        #expect(!expired)
    }

    @Test func genuine401ExpiresSession() async {
        StubURLProtocol.handler = { _ in (401, [:], #"{"error":"Unauthorized"}"#) }
        let api = AttendAPI(tokens: MemoryTokenStore(token: "revoked"), baseURL: URL(string: "https://attend.test")!, session: StubURLProtocol.session())
        var expired = false
        api.onSessionExpired = { expired = true }
        await #expect(throws: APIError.self) { try await api.me() }
        #expect(expired)
    }

    @Test func errorsAreFriendlyAndClassified() async {
        StubURLProtocol.handler = { req in
            req.url!.path.hasSuffix("/events") ? (429, [:], "") : (500, [:], "<html>oops</html>")
        }
        let api = AttendAPI(tokens: MemoryTokenStore(token: "t"), baseURL: URL(string: "https://attend.test")!, session: StubURLProtocol.session())
        do {
            _ = try await api.events()
            Issue.record("expected an error")
        } catch {
            #expect(error.isTransient)
            #expect(error.friendlyMessage.contains("rate limiting"))
        }
        do {
            _ = try await api.tickets()
            Issue.record("expected an error")
        } catch {
            #expect((error as? APIError)?.message == "Server error (500)")
            #expect(error.isTransient)
        }
        #expect(!APIError(status: 404, message: "Not found").isTransient)
        #expect(URLError(.notConnectedToInternet).friendlyMessage == "You're offline. Check your connection.")
    }

    @Test func requestsCarryQueryAndBody() async throws {
        let seen = LockedBox<[String]>([])
        StubURLProtocol.handler = { req in
            let body = req.httpBodyStream.map { s -> String in
                s.open(); defer { s.close() }
                var d = Data(); var buf = [UInt8](repeating: 0, count: 1024)
                while s.hasBytesAvailable { let n = s.read(&buf, maxLength: 1024); if n <= 0 { break }; d.append(buf, count: n) }
                return String(decoding: d, as: UTF8.self)
            } ?? ""
            seen.mutate { $0.append("\(req.httpMethod!) \(req.url!.path)?\(req.url!.query ?? "") \(body)") }
            return (200, [:], #"{"outcome":"scanned","participant":{"participant_id":"p","participant_event_id":"pe"}}"#)
        }
        let api = AttendAPI(tokens: MemoryTokenStore(token: "t"), baseURL: URL(string: "https://attend.test")!, session: StubURLProtocol.session())
        let r = try await api.createScan(eventId: "e1", participantId: "p", scanContextId: "c1", clientScanId: "cid", scannedAt: "2026-10-03T01:30:00Z")
        #expect(r.participant?.participantEventId == "pe")
        _ = try? await api.undoScans(eventId: "e1", participantEventId: "pe", scanContextId: "c1")
        let lines = seen.value
        #expect(lines[0] == #"POST /api/v1/events/e1/scans? {"client_scan_id":"cid","participant_id":"p","scan_context_id":"c1","scanned_at":"2026-10-03T01:30:00Z"}"#)
        #expect(lines[1].hasPrefix("DELETE /api/v1/events/e1/scans/pe?scan_context_id=c1"))
    }
}

final class LockedBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var v: T
    init(_ v: T) { self.v = v }
    var value: T { lock.withLock { v } }
    func mutate(_ f: (inout T) -> Void) { lock.withLock { f(&v) } }
}
