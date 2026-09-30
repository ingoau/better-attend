import AVFoundation
import UIKit

/// App-wide haptics, all gated on the Haptics setting (kept in sync by RootView).
/// For SwiftUI's `.sensoryFeedback`, pass `condition: { _, _ in Haptics.enabled }`.
@MainActor
enum Haptics {
    static var enabled = true

    /// A light tap for buttons, chips and toggles.
    static func tap() { impact(.light) }

    /// A selection change (segmented controls, pickers, paging).
    static func selection() {
        guard enabled else { return }
        UISelectionFeedbackGenerator().selectionChanged()
    }

    static func impact(_ style: UIImpactFeedbackGenerator.FeedbackStyle = .medium, intensity: CGFloat = 1) {
        guard enabled else { return }
        UIImpactFeedbackGenerator(style: style).impactOccurred(intensity: intensity)
    }

    /// An action worked / didn't.
    static func confirm() { notify(.success) }
    static func reject() { notify(.error) }
    static func warn() { notify(.warning) }

    static func notify(_ type: UINotificationFeedbackGenerator.FeedbackType) {
        guard enabled else { return }
        UINotificationFeedbackGenerator().notificationOccurred(type)
    }
}

/// Plays a distinct sound and haptic per scan outcome. Sounds are synthesised once (ToneSynth) and
/// preloaded for low latency; they mix with other audio and respect the silent switch. Failures are
/// swallowed: feedback must never break scanning.
@MainActor
final class ScanFeedbackPlayer {
    static let shared = ScanFeedbackPlayer()

    private var players: [FeedbackKind: AVAudioPlayer] = [:]

    private init() {
        try? AVAudioSession.sharedInstance().setCategory(.ambient, options: [.mixWithOthers])
        for kind in FeedbackKind.allCases {
            if let p = try? AVAudioPlayer(data: ToneSynth.wav(for: kind)) {
                p.prepareToPlay()
                players[kind] = p
            }
        }
    }

    func play(_ kind: FeedbackKind, sound: Bool, haptic: Bool) {
        if sound, let p = players[kind] {
            p.currentTime = 0
            p.play()
        }
        guard haptic, Haptics.enabled else { return }
        switch kind {
        case .success: UINotificationFeedbackGenerator().notificationOccurred(.success)
        case .info: UIImpactFeedbackGenerator(style: .soft).impactOccurred()
        case .warning: UINotificationFeedbackGenerator().notificationOccurred(.warning)
        case .reject: UINotificationFeedbackGenerator().notificationOccurred(.error)
        }
    }
}
