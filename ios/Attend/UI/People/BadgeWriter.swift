import CoreNFC
import Foundation

/// Writes an Attend badge with Core NFC: connect → check the tag → write the `NfcBadgeFormat`
/// records → read them back and verify the token → let the caller confirm with Attend → done.
/// The system NFC sheet shows progress; the caller is told how it ended.
///
/// Every Core NFC callback arrives on `queue`, and all mutable state is only touched there, so the
/// class is safe to hand to the session despite being `@unchecked Sendable`.
final class BadgeWriter: NSObject, NFCNDEFReaderSessionDelegate, @unchecked Sendable {
    enum Outcome: Sendable, Equatable {
        case written
        case cancelled
        /// `retryable` is false for problems another attempt with the same tag won't fix.
        case failed(String, retryable: Bool)
    }

    /// False on the simulator, iPads and older iPhones: hide the write button there.
    static var isAvailable: Bool { NFCNDEFReaderSession.readingAvailable }

    private let queue = DispatchQueue(label: "au.ingo.betterattend.badge-writer")
    private let token: String
    private let name: String
    private let message: NFCNDEFMessage
    private let confirm: @MainActor @Sendable () async -> String?
    private let finish: @MainActor @Sendable (Outcome) -> Void

    // Only touched on `queue`.
    private var session: NFCNDEFReaderSession?
    private var tag: (any NFCNDEFTag)?
    private var finished = false
    private var working = false

    /// `confirm` runs after the badge verifies and returns an error message, or nil on success.
    init(token: String, slackUserId: String?, name: String,
         confirm: @escaping @MainActor @Sendable () async -> String?,
         finish: @escaping @MainActor @Sendable (Outcome) -> Void) {
        self.token = token.trimmingCharacters(in: .whitespaces)
        self.name = name
        self.confirm = confirm
        self.finish = finish
        let records = NfcBadgeFormat.badgeRecords(slackUserId: slackUserId, token: token).map {
            NFCNDEFPayload(format: NFCTypeNameFormat(rawValue: $0.tnf) ?? .unknown, type: $0.type, identifier: Data(), payload: $0.payload)
        }
        message = NFCNDEFMessage(records: records)
        super.init()
    }

    func begin() {
        queue.async { [self] in
            let s = NFCNDEFReaderSession(delegate: self, queue: queue, invalidateAfterFirstRead: false)
            s.alertMessage = "Hold \(name)'s badge near the top of your iPhone."
            session = s
            s.begin()
        }
    }

    func cancel() {
        queue.async { [self] in session?.invalidate() }
    }

    // MARK: NFCNDEFReaderSessionDelegate

    func readerSession(_ session: NFCNDEFReaderSession, didInvalidateWithError error: any Error) {
        self.session = nil
        tag = nil
        switch (error as? NFCReaderError)?.code {
        case .readerSessionInvalidationErrorUserCanceled?, .readerSessionInvalidationErrorSessionTimeout?,
             .readerSessionInvalidationErrorFirstNDEFTagRead?:
            end(.cancelled)
        default:
            end(.failed(error.localizedDescription, retryable: true))
        }
    }

    func readerSession(_ session: NFCNDEFReaderSession, didDetectNDEFs messages: [NFCNDEFMessage]) {
        // Not used: the tag-level callback below is what lets us write.
    }

    func readerSession(_ session: NFCNDEFReaderSession, didDetect tags: [any NFCNDEFTag]) {
        guard !working else { return }
        guard tags.count == 1, let found = tags.first else {
            session.alertMessage = "More than one tag found. Hold just one badge near your iPhone."
            queue.asyncAfter(deadline: .now() + 0.6) { [self] in self.session?.restartPolling() }
            return
        }
        working = true
        tag = found
        session.connect(to: found) { [self] error in
            queue.async { [self] in error == nil ? checkTag() : retry("Lost contact with the badge. Hold it still and try again.") }
        }
    }

    // MARK: Steps

    private func checkTag() {
        guard let tag else { return }
        tag.queryNDEFStatus { [self] status, capacity, error in
            queue.async { [self] in
                if error != nil { return retry("Couldn't read the badge. Hold it still and try again.") }
                switch status {
                case .notSupported: return fail("This tag can't store badge data. Try a different badge.", retryable: false)
                case .readOnly: return fail("This badge is locked (read-only). Use a blank badge.", retryable: false)
                default: break
                }
                if capacity < message.length {
                    return fail("This tag is too small for an Attend badge (\(capacity) bytes).", retryable: false)
                }
                session?.alertMessage = "Writing badge…"
                write()
            }
        }
    }

    private func write() {
        guard let tag else { return }
        tag.writeNDEF(message) { [self] error in
            queue.async { [self] in error == nil ? verify() : retry("The badge moved away too soon. Hold it still and try again.") }
        }
    }

    private func verify() {
        guard let tag else { return }
        tag.readNDEF { [self] read, _ in
            let records = (read?.records ?? []).map { RawNdefRecord(tnf: $0.typeNameFormat.rawValue, type: $0.type, payload: $0.payload) }
            queue.async { [self] in
                guard let found = NfcBadgeFormat.readToken(records), found.caseInsensitiveCompare(token) == .orderedSame else {
                    return retry("The badge didn't save correctly. Hold it still and try again.")
                }
                session?.alertMessage = "Saving to Attend…"
                confirmWithAttend()
            }
        }
    }

    private func confirmWithAttend() {
        let confirm = confirm
        Task { [self] in
            let error = await confirm()
            queue.async { [self] in
                guard let session else {
                    return end(error.map { .failed("Badge written, but Attend didn't confirm it: \($0)", retryable: true) } ?? .written)
                }
                if let error {
                    finished = true
                    session.invalidate(errorMessage: "Attend didn't confirm the badge.")
                    report(.failed("Badge written, but Attend didn't confirm it: \(error)", retryable: true))
                } else {
                    session.alertMessage = "Badge ready. \(name) can tap in at any Attend scanner."
                    finished = true
                    session.invalidate()
                    report(.written)
                }
            }
        }
    }

    /// Recoverable problem: say so on the system sheet and wait for the badge again.
    private func retry(_ message: String) {
        working = false
        tag = nil
        session?.alertMessage = message
        queue.asyncAfter(deadline: .now() + 1.2) { [self] in session?.restartPolling() }
    }

    private func fail(_ message: String, retryable: Bool) {
        finished = true
        session?.invalidate(errorMessage: message)
        report(.failed(message, retryable: retryable))
    }

    private func end(_ outcome: Outcome) {
        guard !finished else { return }
        finished = true
        report(outcome)
    }

    private func report(_ outcome: Outcome) {
        let finish = finish
        Task { @MainActor in finish(outcome) }
    }
}
