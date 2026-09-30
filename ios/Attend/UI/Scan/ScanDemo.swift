#if DEBUG
import SwiftUI

/// Demo-mode helpers so the scanner can be exercised (and screenshotted) on the simulator, which
/// has no camera or NFC. Debug builds only, and only with `-AttendDemo YES`.
///
/// `-AttendScanDemo <scenario>` runs a scenario once the scanner is ready:
/// `scan`, `alert`, `already`, `unknown`, `notattend`, `nfc`, `badge`, `undone`, `offline`, `find`,
/// `findid`, `recent`, `pending`, `checking`. Kiosk: `kiosk-running`, `kiosk-welcome`,
/// `kiosk-already`, `kiosk-unknown`, `kiosk-exit` (PIN 1234).
@MainActor
enum ScanDemo {
    static var launchScenario: String? { UserDefaults.standard.string(forKey: "AttendScanDemo")?.nonBlank }

    enum Code {
        case newArrival, safetyAlert, alreadyHere, unregistered, notAttend, nfcBadge, unlinkedBadge
    }

    static func simulate(_ code: Code, model: ScanModel) {
        guard let e = model.event else { return }
        let roster = model.app.participants.roster(e.id)?.participants.filter(\.isActive) ?? []
        let ctx = model.selectedContextId
        func scannedHere(_ p: Participant) -> Bool { p.scansByContext.contains { $0.scanContextId == ctx } }
        func qr(_ p: Participant?) { model.onCameraCodes([p.map { "attend://checkin/\($0.participantId)" } ?? "attend://checkin/\(UUID().uuidString.lowercased())"]) }
        switch code {
        case .newArrival:
            qr(roster.filter { !scannedHere($0) && !$0.hasSafetyAlert }.randomElement())
        case .safetyAlert:
            qr(roster.first { $0.hasAnaphylaxisRisk && $0.requiresRefrigeration && !scannedHere($0) }
                ?? roster.first { $0.hasSafetyAlert && !scannedHere($0) } ?? roster.first(where: \.hasSafetyAlert))
        case .alreadyHere:
            qr(roster.filter(scannedHere).randomElement())
        case .unregistered:
            qr(nil)
        case .notAttend:
            model.onCameraCodes(["https://example.com/wifi-password"])
        case .nfcBadge:
            if let token = roster.first(where: { $0.nfcBadgeToken != nil && !scannedHere($0) })?.nfcBadgeToken {
                model.onNfc(.input(ScanInput(badgeToken: token, source: "nfc")))
            }
        case .unlinkedBadge:
            model.onNfc(.unrecognised(NdefParser.unlinkedBadge))
        }
    }

    /// Runs a launch scenario (screenshots / manual testing).
    static func run(_ scenario: String, model: ScanModel, openSheet: (ScanSheet) -> Void) async {
        guard let e = model.event else { return }
        // Wait for the roster so names show while checking.
        for _ in 0..<20 where model.app.participants.roster(e.id) == nil { try? await Task.sleep(for: .milliseconds(150)) }
        switch scenario {
        case "scan": simulate(.newArrival, model: model)
        case "alert": simulate(.safetyAlert, model: model)
        case "already": simulate(.alreadyHere, model: model)
        case "unknown": simulate(.unregistered, model: model)
        case "notattend": simulate(.notAttend, model: model)
        case "nfc": simulate(.nfcBadge, model: model)
        case "badge": simulate(.unlinkedBadge, model: model)
        case "checking":
            DemoBackend.shared.isOffline = false
            simulate(.safetyAlert, model: model)
        case "undone":
            simulate(.safetyAlert, model: model)
            for _ in 0..<20 where model.card?.kind != .scanned { try? await Task.sleep(for: .milliseconds(100)) }
            if let card = model.card { await model.undo(card) }
        case "offline":
            DemoBackend.shared.isOffline = true
            simulate(.newArrival, model: model)
            try? await Task.sleep(for: .milliseconds(400))
            model.dismiss()
            simulate(.newArrival, model: model)
            try? await Task.sleep(for: .milliseconds(400))
            model.dismiss()
            simulate(.safetyAlert, model: model)
        case "pending":
            DemoBackend.shared.isOffline = true
            for _ in 0..<3 {
                simulate(.newArrival, model: model)
                try? await Task.sleep(for: .milliseconds(300))
                model.dismiss()
            }
            openSheet(.pending)
        case "recent":
            for code in [Code.alreadyHere, .unregistered, .newArrival, .safetyAlert] {
                simulate(code, model: model)
                try? await Task.sleep(for: .milliseconds(450))
            }
            openSheet(.recent)
        case "find":
            model.setQuery("ma")
            openSheet(.find)
        case "findid":
            let id = model.app.participants.roster(e.id)?.participants.first?.participantId ?? UUID().uuidString
            model.setQuery(id)
            openSheet(.find)
        default:
            break
        }
    }

    /// "Simulate Scan" submenu for the scanner's More menu.
    struct SimulateMenu: View {
        let model: ScanModel

        var body: some View {
            Menu("Simulate Scan", systemImage: "ladybug") {
                Button("New Arrival", systemImage: "qrcode") { simulate(.newArrival, model: model) }
                Button("Safety Alerts", systemImage: "cross.case") { simulate(.safetyAlert, model: model) }
                Button("Already Scanned", systemImage: "clock.arrow.circlepath") { simulate(.alreadyHere, model: model) }
                Button("Unregistered Ticket", systemImage: "questionmark.circle") { simulate(.unregistered, model: model) }
                Button("Not an Attend Code", systemImage: "xmark.octagon") { simulate(.notAttend, model: model) }
                Button("NFC Badge", systemImage: "wave.3.right") { simulate(.nfcBadge, model: model) }
                Button("Unlinked NFC Badge", systemImage: "wave.3.right.circle") { simulate(.unlinkedBadge, model: model) }
            }
            Toggle(isOn: Binding(get: { DemoBackend.shared.isOffline }, set: { DemoBackend.shared.isOffline = $0 })) {
                Label("Simulate Offline", systemImage: "wifi.slash")
            }
        }
    }
}
#endif
