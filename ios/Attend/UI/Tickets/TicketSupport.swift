import CoreImage
import CoreImage.CIFilterBuiltins
import PassKit
import SafariServices
import SwiftUI

// MARK: - QR

/// Renders QR codes with CoreImage: one pixel per module (plus the generator's own quiet zone), so
/// scaling up with nearest-neighbour interpolation stays razor sharp at any size.
@MainActor
enum TicketQRRenderer {
    private static let context = CIContext(options: [.useSoftwareRenderer: false])
    private static var cache: [String: CGImage] = [:]

    static func image(for payload: String) -> CGImage? {
        if let hit = cache[payload] { return hit }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage,
              let cg = context.createCGImage(output, from: output.extent) else { return nil }
        if cache.count > 16 { cache.removeAll() }
        cache[payload] = cg
        return cg
    }
}

/// A QR code, always black on white whatever the theme (scanners need contrast).
struct TicketQRCodeView: View {
    let payload: String
    var label = "Check-in QR code"

    var body: some View {
        Group {
            if let cg = TicketQRRenderer.image(for: payload) {
                Image(decorative: cg, scale: 1)
                    .interpolation(.none)
                    .resizable()
                    .aspectRatio(1, contentMode: .fit)
            } else {
                Image(systemName: "qrcode")
                    .resizable()
                    .scaledToFit()
                    .foregroundStyle(.black.opacity(0.2))
                    .padding(40)
            }
        }
        .background(.white)
        .accessibilityElement()
        .accessibilityLabel(label)
        .accessibilityAddTraits(.isImage)
    }
}

// MARK: - Ticket shape

/// A ticket stub: a rounded rectangle with semicircular notches cut into both sides at `notchY`.
struct TicketPassShape: Shape {
    var cornerRadius: CGFloat = 24
    var notchRadius: CGFloat = 11
    var notchY: CGFloat?

    func path(in rect: CGRect) -> Path {
        let base = Path(roundedRect: rect, cornerRadius: cornerRadius, style: .continuous)
        guard let notchY else { return base }
        let y = min(max(notchY, cornerRadius + notchRadius), rect.height - cornerRadius - notchRadius)
        var notches = Path()
        notches.addEllipse(in: CGRect(x: rect.minX - notchRadius, y: rect.minY + y - notchRadius, width: notchRadius * 2, height: notchRadius * 2))
        notches.addEllipse(in: CGRect(x: rect.maxX - notchRadius, y: rect.minY + y - notchRadius, width: notchRadius * 2, height: notchRadius * 2))
        return base.subtracting(notches)
    }
}

/// The dashed perforation between a ticket's body and its stub.
struct TicketPerforation: View {
    var color: Color = Color(uiColor: .separator)

    var body: some View {
        Line()
            .stroke(color, style: StrokeStyle(lineWidth: 1.5, lineCap: .round, dash: [5, 6]))
            .frame(height: 1.5)
            .accessibilityHidden(true)
    }

    private struct Line: Shape {
        func path(in rect: CGRect) -> Path {
            var p = Path()
            p.move(to: CGPoint(x: rect.minX, y: rect.midY))
            p.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
            return p
        }
    }
}

// MARK: - Status

/// How a ticket status looks: label, tone and symbol.
extension TicketLogic.Status {
    var passTone: Tone {
        switch self {
        case .ready, .checkedIn: .success
        case .incomplete: .warning
        case .closed: .neutral
        }
    }

    var passSymbol: String {
        switch self {
        case .ready: "qrcode"
        case .checkedIn: "checkmark"
        case .incomplete: "hourglass"
        case .closed: "nosign"
        }
    }

    var passReason: String? {
        switch self {
        case .incomplete(_, let reason), .closed(_, let reason): reason
        default: nil
        }
    }
}

/// A status capsule. `onBrand` draws it for a red (brand-filled) card.
struct TicketStatusPill: View {
    let status: TicketLogic.Status
    var onBrand = false

    var body: some View {
        if onBrand {
            HStack(spacing: 4) {
                Image(systemName: status.passSymbol).imageScale(.small)
                Text(status.label).lineLimit(1)
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(status == .checkedIn ? HackClub.red : .white)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(status == .checkedIn ? AnyShapeStyle(.white) : AnyShapeStyle(.white.opacity(0.22)), in: .capsule)
        } else if status == .checkedIn {
            HStack(spacing: 4) {
                Image(systemName: status.passSymbol).imageScale(.small)
                Text(status.label).lineLimit(1)
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(Tone.success.onColor)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Tone.success.color, in: .capsule)
        } else {
            Pill(text: status.label, tone: status.passTone, systemImage: status.passSymbol)
        }
    }
}

/// "Happening now" / "Tomorrow · 9:00 AM" capsule; live gets a pulsing dot.
struct TicketWhenPill: View {
    let label: String
    let live: Bool
    var onBrand = false

    var body: some View {
        HStack(spacing: 5) {
            if live {
                TicketLiveDot(color: onBrand ? .white : Tone.success.color)
            } else {
                Image(systemName: "clock").imageScale(.small)
            }
            Text(label).lineLimit(1)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(onBrand ? AnyShapeStyle(.white) : live ? AnyShapeStyle(Tone.success.onContainer) : AnyShapeStyle(.primary))
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(onBrand ? AnyShapeStyle(.white.opacity(0.22)) : live ? AnyShapeStyle(Tone.success.container) : AnyShapeStyle(Color(uiColor: .tertiarySystemFill)),
                    in: .capsule)
    }
}

/// A small dot that gently pulses (static with Reduce Motion).
struct TicketLiveDot: View {
    var color: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pulse = false

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 7, height: 7)
            .background(Circle().fill(color.opacity(0.35)).scaleEffect(pulse ? 2.2 : 1).opacity(pulse ? 0 : 1))
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.easeOut(duration: 1.4).repeatForever(autoreverses: false)) { pulse = true }
            }
            .accessibilityHidden(true)
    }
}

// MARK: - Brightness

/// Pushes the screen to full brightness (and keeps it awake) while any holder wants it, so the QR
/// scans first time at a dim door. Restores the user's brightness when the last holder lets go and
/// whenever the app leaves the foreground; re-applies when it comes back.
@MainActor
final class PassBrightness {
    static let shared = PassBrightness()

    private var holders: Set<String> = []
    private var saved: CGFloat?
    private var ramp: Task<Void, Never>?
    private var observers: [NSObjectProtocol] = []

    private init() {
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { PassBrightness.shared.restore(animated: false) }
        })
        observers.append(nc.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { PassBrightness.shared.update() }
        })
    }

    func hold(_ id: String) {
        holders.insert(id)
        update()
    }

    func release(_ id: String) {
        holders.remove(id)
        update()
    }

    private var screen: UIScreen? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return (scenes.first { $0.activationState == .foregroundActive } ?? scenes.first)?.screen
    }

    private func update() {
        let active = UIApplication.shared.applicationState == .active
        if holders.isEmpty || !active {
            restore(animated: active)
        } else {
            boost()
        }
    }

    private func boost() {
        guard let screen else { return }
        if saved == nil { saved = screen.brightness }
        UIApplication.shared.isIdleTimerDisabled = true
        animate(screen, to: 1)
    }

    private func restore(animated: Bool) {
        guard let value = saved else { return }
        saved = nil
        UIApplication.shared.isIdleTimerDisabled = false
        guard let screen else { return }
        if animated {
            animate(screen, to: value)
        } else {
            ramp?.cancel()
            screen.brightness = value
        }
    }

    /// Eases brightness over ~0.3 s rather than snapping.
    private func animate(_ screen: UIScreen, to target: CGFloat) {
        ramp?.cancel()
        let start = screen.brightness
        guard abs(start - target) > 0.01 else { screen.brightness = target; return }
        ramp = Task { @MainActor in
            let steps = 12
            for i in 1...steps {
                try? await Task.sleep(for: .milliseconds(25))
                if Task.isCancelled { return }
                let t = Double(i) / Double(steps)
                let eased = 1 - pow(1 - t, 3)
                screen.brightness = start + (target - start) * eased
            }
        }
    }
}

extension View {
    /// Holds full screen brightness while this view is on screen and `enabled`.
    func boostsPassBrightness(_ enabled: Bool, id: String) -> some View {
        modifier(PassBrightnessModifier(enabled: enabled, id: id))
    }
}

private struct PassBrightnessModifier: ViewModifier {
    let enabled: Bool
    let id: String
    @State private var onScreen = false

    func body(content: Content) -> some View {
        content
            .onAppear { onScreen = true; apply() }
            .onDisappear { onScreen = false; PassBrightness.shared.release(id) }
            .onChange(of: enabled) { apply() }
    }

    private func apply() {
        if enabled && onScreen { PassBrightness.shared.hold(id) } else { PassBrightness.shared.release(id) }
    }
}

// MARK: - Safari

/// A URL to open in an in-app Safari sheet.
struct TicketWebPage: Identifiable, Hashable {
    let url: URL
    var id: String { url.absoluteString }
}

/// `SFSafariViewController` for registration forms and the incident report.
struct TicketSafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        let vc = SFSafariViewController(url: url)
        vc.preferredControlTintColor = UIColor(HackClub.red)
        vc.dismissButtonStyle = .done
        return vc
    }

    func updateUIViewController(_ vc: SFSafariViewController, context: Context) {}
}

extension View {
    /// Presents `page` in an in-app Safari sheet.
    func ticketSafariSheet(_ page: Binding<TicketWebPage?>) -> some View {
        fullScreenCover(item: page) { page in
            TicketSafariView(url: page.url).ignoresSafeArea()
        }
    }
}

// MARK: - Apple Wallet

/// The official "Add to Apple Wallet" button.
struct TicketAddPassButton: UIViewRepresentable {
    var action: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    func makeUIView(context: Context) -> PKAddPassButton {
        let button = PKAddPassButton(addPassButtonStyle: colorScheme == .dark ? .blackOutline : .black)
        button.addTarget(context.coordinator, action: #selector(Coordinator.tapped), for: .touchUpInside)
        button.setContentHuggingPriority(.defaultLow, for: .horizontal)
        return button
    }

    func updateUIView(_ button: PKAddPassButton, context: Context) {
        context.coordinator.action = action
        button.addPassButtonStyle = colorScheme == .dark ? .blackOutline : .black
    }

    func makeCoordinator() -> Coordinator { Coordinator(action: action) }

    @MainActor
    final class Coordinator: NSObject {
        var action: () -> Void
        init(action: @escaping () -> Void) { self.action = action }
        @objc func tapped() { action() }
    }
}

/// A downloaded pass ready to present.
struct TicketPendingPass: Identifiable {
    let pass: PKPass
    var id: String { pass.serialNumber }
}

/// Apple's "Add to Wallet" sheet.
struct TicketAddPassesSheet: UIViewControllerRepresentable {
    let pass: PKPass
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIViewController {
        guard let vc = PKAddPassesViewController(pass: pass) else { return UIViewController() }
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: UIViewController, context: Context) {
        context.coordinator.dismiss = { dismiss() }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    @MainActor
    final class Coordinator: NSObject, @preconcurrency PKAddPassesViewControllerDelegate {
        var dismiss: () -> Void = {}
        func addPassesViewControllerDidFinish(_ controller: PKAddPassesViewController) {
            dismiss()
        }
    }
}
