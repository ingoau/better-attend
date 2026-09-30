import CoreNFC
import Foundation

/// Reads Hack Club NFC badges with CoreNFC. One session stays open so staff can tap several badges
/// in a row; iOS shows its own sheet and ends the session after about a minute.
@MainActor
@Observable
final class NFCBadgeReader {
    /// False on devices without NFC (iPad, the simulator).
    static var isAvailable: Bool { NFCNDEFReaderSession.readingAvailable }

    private(set) var isReading = false
    @ObservationIgnored private var session: NFCNDEFReaderSession?
    @ObservationIgnored private var delegate: Delegate?
    @ObservationIgnored private var reads = 0

    /// Starts a session. `onResult` is called on the main actor for every badge read.
    func begin(onResult: @escaping @MainActor (NfcParseResult) -> Void, onError: @escaping @MainActor (String) -> Void = { _ in }) {
        guard Self.isAvailable, session == nil else { return }
        reads = 0
        let delegate = Delegate { [weak self] result in
            guard let self else { return }
            reads += 1
            session?.alertMessage = reads == 1 ? "Got it. Tap the next badge, or tap Done." : "\(reads) badges read. Tap the next one, or tap Done."
            onResult(result)
        } onEnd: { [weak self] message in
            self?.session = nil
            self?.delegate = nil
            self?.isReading = false
            if let message { onError(message) }
        }
        let session = NFCNDEFReaderSession(delegate: delegate, queue: nil, invalidateAfterFirstRead: false)
        session.alertMessage = "Hold an Attend badge near the top of your iPhone."
        self.delegate = delegate
        self.session = session
        isReading = true
        session.begin()
    }

    func end() {
        session?.invalidate()
    }

    /// CoreNFC delegate; callbacks arrive on a background queue and hop to the main actor.
    private final class Delegate: NSObject, NFCNDEFReaderSessionDelegate, @unchecked Sendable {
        let onRead: @MainActor (NfcParseResult) -> Void
        let onEnd: @MainActor (String?) -> Void

        init(onRead: @escaping @MainActor (NfcParseResult) -> Void, onEnd: @escaping @MainActor (String?) -> Void) {
            self.onRead = onRead
            self.onEnd = onEnd
        }

        func readerSession(_ session: NFCNDEFReaderSession, didDetectNDEFs messages: [NFCNDEFMessage]) {
            let records = messages.flatMap(\.records).map {
                RawNdefRecord(tnf: UInt8($0.typeNameFormat.rawValue), type: $0.type, payload: $0.payload)
            }
            let result = NdefParser.parse(records)
            Task { @MainActor [onRead] in onRead(result) }
        }

        func readerSession(_ session: NFCNDEFReaderSession, didInvalidateWithError error: any Error) {
            let code = (error as? NFCReaderError)?.code
            let quiet: Set<NFCReaderError.Code> = [.readerSessionInvalidationErrorUserCanceled, .readerSessionInvalidationErrorFirstNDEFTagRead,
                                                   .readerSessionInvalidationErrorSessionTimeout]
            let message = code.map { quiet.contains($0) } == true ? nil : error.localizedDescription
            Task { @MainActor [onEnd] in onEnd(message) }
        }

        func readerSessionDidBecomeActive(_ session: NFCNDEFReaderSession) {}
    }
}
