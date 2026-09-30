import AVFoundation
import SwiftUI
import UIKit

/// The camera viewport background: a camera is dark in both appearances, so this is fixed.
let cameraBackdrop = Color(hex: 0x0B0B0F)

enum CameraFacing: Hashable, Sendable {
    case back, front

    var position: AVCaptureDevice.Position { self == .back ? .back : .front }
}

enum CameraPermission {
    /// Hardware + permission, checked synchronously.
    static func current() -> CameraAccess {
        guard hasCamera else { return .unavailable }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return .granted
        case .notDetermined: return .notDetermined
        default: return .denied
        }
    }

    static var hasCamera: Bool {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) != nil
            || AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front) != nil
    }

    static func request() async -> CameraAccess {
        _ = await AVCaptureDevice.requestAccess(for: .video)
        return current()
    }

    @MainActor
    static func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }
}

/// Wraps a non-Sendable AVFoundation object for a hop to the main queue.
private struct Unchecked<T>: @unchecked Sendable { let value: T }

/// Owns the capture session. All session work runs on a private serial queue; decoded values are
/// delivered on the main queue. Never pauses between results: the caller gates duplicates.
final class QRCaptureController: NSObject, AVCaptureMetadataOutputObjectsDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "au.ingo.betterattend.camera")
    // Session-queue state.
    private var input: AVCaptureDeviceInput?
    private let output = AVCaptureMetadataOutput()
    private var facing: CameraFacing?
    private var wantsTorch = false
    private var wantsRunning = false

    // Main-queue state.
    @MainActor var onCodes: ([String]) -> Void = { _ in }
    @MainActor var onTorchAvailable: (Bool) -> Void = { _ in }
    @MainActor var onError: (String) -> Void = { _ in }
    @MainActor weak var previewLayer: AVCaptureVideoPreviewLayer?
    @MainActor private var rotation: AVCaptureDevice.RotationCoordinator?
    @MainActor private var rotationObservation: NSKeyValueObservation?

    /// Starts (or switches to) the given camera. Idempotent.
    func start(_ facing: CameraFacing) {
        queue.async { [self] in
            wantsRunning = true
            if self.facing != facing { configure(facing) }
            if wantsRunning, input != nil, !session.isRunning { session.startRunning() }
        }
    }

    func stop() {
        queue.async { [self] in
            wantsRunning = false
            if session.isRunning { session.stopRunning() }
        }
    }

    func setTorch(_ on: Bool) {
        queue.async { [self] in
            wantsTorch = on
            applyTorch()
        }
    }

    private func configure(_ facing: CameraFacing) {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        if let input { session.removeInput(input) }
        input = nil
        let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: facing.position)
            ?? AVCaptureDevice.default(for: .video)
        guard let device, let newInput = try? AVCaptureDeviceInput(device: device), session.canAddInput(newInput) else {
            report("Couldn't start the camera.")
            return
        }
        session.addInput(newInput)
        input = newInput
        self.facing = facing
        if !session.outputs.contains(output), session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
        }
        if output.availableMetadataObjectTypes.contains(.qr) { output.metadataObjectTypes = [.qr] }
        applyTorch()
        let hasTorch = device.hasTorch
        let box = Unchecked(value: device)
        DispatchQueue.main.async { [self] in
            MainActor.assumeIsolated {
                onTorchAvailable(hasTorch)
                followRotation(of: box.value)
            }
        }
    }

    private func applyTorch() {
        guard let device = input?.device, device.hasTorch, (try? device.lockForConfiguration()) != nil else { return }
        device.torchMode = wantsTorch ? .on : .off
        device.unlockForConfiguration()
    }

    private func report(_ message: String) {
        DispatchQueue.main.async { [self] in MainActor.assumeIsolated { onError(message) } }
    }

    /// Keeps the preview upright as the device rotates (iPad kiosks are usually landscape).
    @MainActor
    private func followRotation(of device: AVCaptureDevice) {
        guard let previewLayer else { return }
        let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: previewLayer)
        rotation = coordinator
        let apply: @MainActor (CGFloat) -> Void = { angle in
            if previewLayer.connection?.isVideoRotationAngleSupported(angle) == true {
                previewLayer.connection?.videoRotationAngle = angle
            }
        }
        apply(coordinator.videoRotationAngleForHorizonLevelPreview)
        rotationObservation = coordinator.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.new]) { _, change in
            guard let angle = change.newValue else { return }
            DispatchQueue.main.async { MainActor.assumeIsolated { apply(angle) } }
        }
    }

    // Delivered on the main queue (see `setMetadataObjectsDelegate`).
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        let values = metadataObjects.compactMap { ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }.filter { !$0.isBlank }
        guard !values.isEmpty else { return }
        MainActor.assumeIsolated { onCodes(values) }
    }
}

/// A UIView whose backing layer is the camera preview.
final class CameraPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}

/// Live camera preview with continuous QR decoding. Runs only while `isActive`, and stops when it
/// leaves the hierarchy (e.g. switching tabs).
struct QRCameraView: UIViewRepresentable {
    var facing: CameraFacing = .back
    var torchOn = false
    var isActive = true
    var onCodes: ([String]) -> Void
    var onTorchAvailable: (Bool) -> Void = { _ in }
    var onError: (String) -> Void = { _ in }

    func makeCoordinator() -> QRCaptureController { QRCaptureController() }

    func makeUIView(context: Context) -> CameraPreviewView {
        let view = CameraPreviewView()
        view.backgroundColor = UIColor(cameraBackdrop)
        view.previewLayer.session = context.coordinator.session
        view.previewLayer.videoGravity = .resizeAspectFill
        context.coordinator.previewLayer = view.previewLayer
        return view
    }

    func updateUIView(_ view: CameraPreviewView, context: Context) {
        let c = context.coordinator
        c.onCodes = onCodes
        c.onTorchAvailable = onTorchAvailable
        c.onError = onError
        if isActive { c.start(facing) } else { c.stop() }
        c.setTorch(torchOn && isActive)
    }

    static func dismantleUIView(_ view: CameraPreviewView, coordinator: QRCaptureController) {
        coordinator.setTorch(false)
        coordinator.stop()
    }
}

// MARK: - Viewfinder

/// Everything outside the target square, dimmed.
struct ViewfinderDim: Shape {
    var bias: CGFloat
    var maxSide: CGFloat = 280

    var animatableData: CGFloat {
        get { bias }
        set { bias = newValue }
    }

    func path(in rect: CGRect) -> Path {
        var p = Path(rect)
        p.addPath(Path(roundedRect: Viewfinder.target(in: rect, bias: bias, maxSide: maxSide), cornerRadius: Viewfinder.radius, style: .continuous))
        return p
    }
}

/// Rounded corner brackets around the target square.
struct ViewfinderBrackets: Shape {
    var bias: CGFloat
    var maxSide: CGFloat = 280

    var animatableData: CGFloat {
        get { bias }
        set { bias = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let t = Viewfinder.target(in: rect, bias: bias, maxSide: maxSide)
        let r = Viewfinder.radius
        let arm = max(t.width * 0.2, r + 8)
        var p = Path()
        // top-left
        p.move(to: CGPoint(x: t.minX, y: t.minY + arm))
        p.addLine(to: CGPoint(x: t.minX, y: t.minY + r))
        p.addArc(center: CGPoint(x: t.minX + r, y: t.minY + r), radius: r, startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false)
        p.addLine(to: CGPoint(x: t.minX + arm, y: t.minY))
        // top-right
        p.move(to: CGPoint(x: t.maxX - arm, y: t.minY))
        p.addLine(to: CGPoint(x: t.maxX - r, y: t.minY))
        p.addArc(center: CGPoint(x: t.maxX - r, y: t.minY + r), radius: r, startAngle: .degrees(270), endAngle: .degrees(0), clockwise: false)
        p.addLine(to: CGPoint(x: t.maxX, y: t.minY + arm))
        // bottom-right
        p.move(to: CGPoint(x: t.maxX, y: t.maxY - arm))
        p.addLine(to: CGPoint(x: t.maxX, y: t.maxY - r))
        p.addArc(center: CGPoint(x: t.maxX - r, y: t.maxY - r), radius: r, startAngle: .degrees(0), endAngle: .degrees(90), clockwise: false)
        p.addLine(to: CGPoint(x: t.maxX - arm, y: t.maxY))
        // bottom-left
        p.move(to: CGPoint(x: t.minX + arm, y: t.maxY))
        p.addLine(to: CGPoint(x: t.minX + r, y: t.maxY))
        p.addArc(center: CGPoint(x: t.minX + r, y: t.maxY - r), radius: r, startAngle: .degrees(90), endAngle: .degrees(180), clockwise: false)
        p.addLine(to: CGPoint(x: t.minX, y: t.maxY - arm))
        return p
    }
}

enum Viewfinder {
    static let radius: CGFloat = 28

    /// A square target, a little above centre so the result card doesn't cover it.
    static func target(in rect: CGRect, bias: CGFloat, maxSide: CGFloat) -> CGRect {
        let side = min(rect.width * 0.66, rect.height * 0.46, maxSide)
        let y = max(rect.minY + 16, rect.minY + rect.height * bias - side / 2)
        return CGRect(x: rect.midX - side / 2, y: y, width: side, height: side)
    }
}

/// Dims the preview outside the target and draws brackets tinted by the last outcome.
struct ViewfinderOverlay: View {
    var accent: Color = .white
    var bias: CGFloat = 0.42
    var maxSide: CGFloat = 280

    var body: some View {
        ZStack {
            ViewfinderDim(bias: bias, maxSide: maxSide)
                .fill(.black.opacity(0.42), style: FillStyle(eoFill: true))
            ViewfinderBrackets(bias: bias, maxSide: maxSide)
                .stroke(accent, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
                .shadow(color: .black.opacity(0.25), radius: 4)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
