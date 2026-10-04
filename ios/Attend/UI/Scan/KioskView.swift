import SwiftUI

/// Self check-in on a spare iPhone or iPad: attendees scan their own tickets with the front
/// camera. Staff choose a PIN on the way in and need it to get out.
struct KioskView: View {
    let config: KioskConfig
    @Environment(AppModel.self) private var app

    var body: some View {
        KioskScreen(app: app, config: config)
    }
}

private struct KioskScreen: View {
    let config: KioskConfig
    @State private var model: ScanModel
    @State private var lock: KioskPinLock?
    @State private var showExit = false
    @State private var facing: CameraFacing = .front
    @State private var camera: CameraAccess = CameraPermission.current()
    @Environment(Router.self) private var router
    @Environment(\.scenePhase) private var scenePhase
    #if DEBUG
    @State private var ranDemo = false
    #endif

    init(app: AppModel, config: KioskConfig) {
        self.config = config
        _model = State(initialValue: ScanModel(app: app, fixedEventId: config.eventId, lockedContextId: config.scanContextId))
    }

    var body: some View {
        ZStack {
            if lock == nil {
                KioskSetupView(model: model, onCancel: close) { hash in
                    withAnimation(.smooth(duration: 0.4)) { lock = KioskPinLock(hash: hash) }
                }
                .transition(.opacity)
            } else {
                running
                    .transition(.opacity.combined(with: .scale(scale: 1.04)))
            }
        }
        .task(id: model.event?.id) { await model.start() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { camera = CameraPermission.current() }
        }
        #if DEBUG
        .task { await runDemo() }
        #endif
    }

    private func close() {
        UIApplication.shared.isIdleTimerDisabled = false
        router.kiosk = nil
    }

    // MARK: Running

    /// The camera, or (in demo builds on the simulator) a stand-in so the flow can be tried.
    private var cameraUsable: Bool {
        #if DEBUG
        if model.app.isDemo, camera == .unavailable { return true }
        #endif
        return camera == .granted
    }

    private var accent: Color {
        guard let card = model.card, card.kind.isFinal, card.kind != .undone else { return .white }
        return card.kind.tone.color
    }

    private var running: some View {
        ZStack {
            cameraBackdrop.ignoresSafeArea()
            if cameraUsable {
                if camera == .granted {
                    QRCameraView(facing: facing, isActive: !showExit && scenePhase != .background,
                                 onCodes: { codes in if !showExit { model.onCameraCodes(codes) } })
                        .ignoresSafeArea()
                }
                ViewfinderOverlay(accent: accent, bias: 0.4, maxSide: 340)
                    .environment(\.colorScheme, .dark)
                    .ignoresSafeArea()
                    .animation(.smooth(duration: 0.35), value: accent)
            } else {
                CameraPermissionPanel(access: camera, alternatives: false) {
                    Task { camera = await CameraPermission.request() }
                }
            }
            VStack(spacing: 0) {
                header
                Spacer()
                if cameraUsable {
                    ZStack {
                        if let card = model.card {
                            KioskResultCard(card: card)
                                .id("\(card.key)-\(card.kind)")
                                .transition(.scale(scale: 0.9).combined(with: .opacity))
                        } else {
                            KioskPrompt(ready: model.ready, eventName: model.event?.name)
                                .transition(.scale(scale: 0.96).combined(with: .opacity))
                        }
                    }
                    .frame(maxWidth: 560)
                    .animation(.spring(duration: 0.45, bounce: 0.25), value: model.card.map { "\($0.key)-\($0.kind)" })
                }
            }
            .padding(16)
        }
        .environment(\.colorScheme, .dark)
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        // Privacy: results hide on their own. The gate isn't released, so a ticket left in front
        // of the camera isn't scanned again until it has been taken away.
        .task(id: model.card.map { "\($0.key)-\($0.kind)" }) {
            guard let card = model.card, card.kind.isFinal else { return }
            try? await Task.sleep(for: .seconds(KioskLogic.resultSeconds))
            guard !Task.isCancelled else { return }
            withAnimation(.smooth) { model.hide(card.key) }
        }
        .onChange(of: model.card?.kind) { _, kind in
            guard let card = model.card, kind?.isFinal == true else { return }
            let msg = KioskLogic.message(for: card)
            AccessibilityNotification.Announcement("\(msg.title). \(msg.body)").post()
        }
        .sheet(isPresented: $showExit) {
            if let lock {
                KioskExitSheet(lock: lock) { updated in
                    self.lock = updated
                } onUnlock: {
                    showExit = false
                    close()
                }
            }
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            CameraIconButton(title: "Exit kiosk mode (staff PIN required)", systemImage: "lock.fill") {
                Haptics.tap()
                showExit = true
            }
            .opacity(0.6)
            Spacer(minLength: 0)
            HStack(spacing: 10) {
                Image(systemName: model.selectedContext?.systemImage ?? "qrcode.viewfinder")
                    .font(.body.weight(.semibold))
                VStack(alignment: .leading, spacing: 0) {
                    Text(model.selectedContext?.name ?? model.event?.name ?? "Check-in")
                        .font(.subheadline.weight(.semibold))
                    if model.selectedContext != nil, let name = model.event?.name {
                        Text(name).font(.caption).opacity(0.75)
                    }
                }
                .lineLimit(1)
            }
            .foregroundStyle(.white)
            .padding(.leading, 14)
            .padding(.trailing, 18)
            .frame(minHeight: 48)
            .floatingGlass(in: Capsule())
            .accessibilityElement(children: .combine)
            Spacer(minLength: 0)
            CameraIconButton(title: facing == .front ? "Switch to rear camera" : "Switch to front camera",
                             systemImage: "arrow.triangle.2.circlepath.camera", isEnabled: camera == .granted) {
                Haptics.tap()
                facing = facing == .front ? .back : .front
            }
        }
    }

    #if DEBUG
    private func runDemo() async {
        guard !ranDemo, model.app.isDemo, let scenario = ScanDemo.launchScenario, scenario.hasPrefix("kiosk") else { return }
        ranDemo = true
        guard scenario != "kiosk" else { return }
        lock = KioskPinLock(hash: KioskLogic.hashPin("1234"))
        for _ in 0..<40 where !model.ready || model.event.map({ model.app.participants.roster($0.id) }) == nil {
            try? await Task.sleep(for: .milliseconds(150))
        }
        switch scenario {
        case "kiosk-welcome": ScanDemo.simulate(.newArrival, model: model)
        case "kiosk-already": ScanDemo.simulate(.alreadyHere, model: model)
        case "kiosk-unknown": ScanDemo.simulate(.unregistered, model: model)
        case "kiosk-exit": showExit = true
        default: break
        }
    }
    #endif
}

// MARK: - Prompt and result

private struct KioskPrompt: View {
    let ready: Bool
    let eventName: String?

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "qrcode.viewfinder")
                .font(.system(size: 44, weight: .medium))
                .foregroundStyle(.tint)
                .symbolEffect(.pulse, options: .repeating, isActive: ready)
            Text(ready ? "Scan your ticket" : "Getting ready…")
                .font(.system(.largeTitle, design: .rounded, weight: .bold))
                .multilineTextAlignment(.center)
                .contentTransition(.opacity)
            Text("Hold the QR code on your ticket up to the camera.")
                .font(.title3)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 28)
        .frame(maxWidth: .infinity)
        .floatingGlass(in: .rect(cornerRadius: 36, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

private struct KioskResultCard: View {
    let card: ScanCard

    var body: some View {
        let msg = KioskLogic.message(for: card)
        VStack(spacing: 18) {
            OutcomeBadge(kind: card.kind, size: 112)
            VStack(spacing: 8) {
                Text(msg.title)
                    .font(.system(.largeTitle, design: .rounded, weight: .bold))
                    .multilineTextAlignment(.center)
                    .minimumScaleFactor(0.7)
                Text(msg.body)
                    .font(.title3)
                    .multilineTextAlignment(.center)
                    .opacity(0.85)
            }
            .foregroundStyle(card.kind.tone.onContainer)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 32)
        .frame(maxWidth: .infinity)
        .background(card.kind.tone.container, in: .rect(cornerRadius: 40, style: .continuous))
        .shadow(color: .black.opacity(0.3), radius: 24, y: 8)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(msg.title). \(msg.body)")
    }
}

// MARK: - Setup

private struct KioskSetupView: View {
    let model: ScanModel
    let onCancel: () -> Void
    let onStart: (String) -> Void

    @State private var setup = KioskPinSetup()
    @State private var pin = ""
    @State private var errors = 0

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    VStack(spacing: 10) {
                        Image(systemName: "lock.rectangle.stack.fill")
                            .font(.system(size: 40, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 84, height: 84)
                            .background(Color.accentColor.gradient, in: .rect(cornerRadius: 22, style: .continuous))
                            .padding(.bottom, 6)
                        Text("Kiosk Mode")
                            .font(.largeTitle.weight(.bold))
                        Text("Attendees scan their own tickets with the front camera. Staff need this PIN to leave.")
                            .font(.body)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                        Label(checkpoint, systemImage: model.selectedContext?.systemImage ?? "qrcode.viewfinder")
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(Tone.brand.container, in: .capsule)
                            .foregroundStyle(Tone.brand.onContainer)
                            .padding(.top, 4)
                    }

                    VStack(spacing: 18) {
                        Text(setup.prompt)
                            .font(.headline)
                            .foregroundStyle(setup.error != nil ? Tone.danger.color : .primary)
                            .contentTransition(.opacity)
                            .animation(.smooth, value: setup.prompt)
                            .accessibilityAddTraits(.updatesFrequently)
                        PinField(pin: $pin, label: setup.prompt, shakes: errors, submitTitle: setup.confirming ? "Start Kiosk" : "Next") { entered in
                            if let hash = setup.submit(entered) {
                                Haptics.confirm()
                                onStart(hash)
                            } else if setup.error != nil {
                                Haptics.reject()
                                errors += 1
                            } else {
                                Haptics.selection()
                            }
                        }
                    }
                    .padding(.vertical, 22)
                    .frame(maxWidth: .infinity)
                    .background(Color(uiColor: .secondarySystemGroupedBackground), in: .rect(cornerRadius: 24, style: .continuous))

                    GuidedAccessTip()
                }
                .padding(20)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
                if setup.confirming {
                    ToolbarItem(placement: .primaryAction) {
                        Button("Start Over") {
                            setup.restart()
                            pin = ""
                        }
                    }
                }
            }
        }
    }

    private var checkpoint: String {
        [model.event?.name, model.selectedContext?.name ?? "No checkpoint"].compactMap { $0 }.joined(separator: " · ")
    }
}

/// How to pin the device to Attend with Guided Access.
private struct GuidedAccessTip: View {
    @State private var enabled = UIAccessibility.isGuidedAccessEnabled

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: enabled ? "checkmark.shield.fill" : "hand.raised.fill")
                .font(.title3)
                .foregroundStyle(enabled ? Tone.success.color : Tone.info.color)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 4) {
                Text(enabled ? "Guided Access is on" : "Lock this device to BetterAttend")
                    .font(.subheadline.weight(.semibold))
                Text(enabled
                    ? "This device stays in BetterAttend until Guided Access is ended."
                    : "After starting, triple-click the side button (or Home button) to turn on Guided Access, so attendees can't leave the app. Set it up in Settings › Accessibility › Guided Access.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: .rect(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .combine)
        .onReceive(NotificationCenter.default.publisher(for: UIAccessibility.guidedAccessStatusDidChangeNotification)) { _ in
            enabled = UIAccessibility.isGuidedAccessEnabled
        }
    }
}

// MARK: - Exit

private struct KioskExitSheet: View {
    let lock: KioskPinLock
    let onUpdate: (KioskPinLock) -> Void
    let onUnlock: () -> Void

    @State private var pin = ""
    @State private var last: KioskPinLock.Attempt?
    @State private var shakes = 0
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let locked = lock.isLocked(now: context.date)
                let prompt = lock.prompt(after: last, now: context.date)
                VStack(spacing: 20) {
                    Image(systemName: locked ? "lock.trianglebadge.exclamationmark.fill" : "lock.fill")
                        .font(.system(size: 36, weight: .semibold))
                        .foregroundStyle(locked ? Tone.danger.color : Color.accentColor)
                        .contentTransition(.symbolEffect(.replace))
                    VStack(spacing: 6) {
                        Text("Exit Kiosk Mode").font(.title2.weight(.bold))
                        Text(prompt)
                            .font(.body)
                            .foregroundStyle(locked || last != nil && last != .unlocked ? Tone.danger.color : .secondary)
                            .multilineTextAlignment(.center)
                            .contentTransition(.numericText())
                    }
                    PinField(pin: $pin, label: prompt, isEnabled: !locked, shakes: shakes, submitTitle: "Unlock") { entered in
                        let result = updated(entered, now: context.date)
                        last = result
                        switch result {
                        case .unlocked:
                            Haptics.confirm()
                            onUnlock()
                        case .wrong, .lockedOut:
                            Haptics.reject()
                            shakes += 1
                        }
                    }
                }
                .padding(24)
                .frame(maxWidth: .infinity)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Keep Running") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
    }

    private func updated(_ entered: String, now: Date) -> KioskPinLock.Attempt {
        var copy = lock
        let result = copy.attempt(entered, now: now)
        onUpdate(copy)
        return result
    }
}

// MARK: - PIN field

/// A dot per digit over a hidden number-pad field, like the system passcode prompts. PINs can be
/// any length, so the entry is submitted with a button; calls `onComplete` with it and clears itself.
struct PinField: View {
    @Binding var pin: String
    var label: String
    var isEnabled = true
    var shakes = 0
    var submitTitle = "Done"
    let onComplete: (String) -> Void

    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 18) {
            ZStack {
                TextField("", text: $pin)
                    .keyboardType(.numberPad)
                    .focused($focused)
                    .disabled(!isEnabled)
                    .onSubmit(submit)
                    .toolbar {
                        // The number pad has no return key, and on small iPhones it can cover the button below.
                        ToolbarItemGroup(placement: .keyboard) {
                            Spacer()
                            Button(submitTitle, action: submit)
                                .bold()
                                .disabled(!isEnabled || pin.isEmpty)
                        }
                    }
                    .frame(width: 1, height: 1)
                    .opacity(0.02)
                    .accessibilityLabel(label)
                    .accessibilityValue("\(pin.count) \(pin.count == 1 ? "digit" : "digits") entered")
                Group {
                    if pin.count > KioskLogic.maxPinDots {
                        Text("\(pin.count) digits")
                            .font(.title3.weight(.semibold).monospacedDigit())
                            .contentTransition(.numericText())
                    } else {
                        FlowLayout(spacing: 20, lineSpacing: 14) {
                            ForEach(0..<pin.count, id: \.self) { _ in
                                Circle()
                                    .fill(Color.primary.opacity(isEnabled ? 1 : 0.4))
                                    .frame(width: 16, height: 16)
                                    .transition(.scale.combined(with: .opacity))
                            }
                        }
                    }
                }
                .animation(.bouncy(duration: 0.25), value: pin.count)
                .frame(maxWidth: 280, minHeight: 16)
                .frame(minWidth: 160)
                .padding(.vertical, 12)
                .padding(.horizontal, 24)
                .contentShape(.rect)
                .onTapGesture { focused = true }
                .accessibilityHidden(true)
            }
            Button(action: submit) {
                Text(submitTitle).frame(minWidth: 140)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(!isEnabled || pin.isEmpty)
        }
        .keyframeAnimator(initialValue: CGFloat(0), trigger: shakes) { content, x in
            content.offset(x: x)
        } keyframes: { _ in
            KeyframeTrack {
                LinearKeyframe(-14, duration: 0.06)
                LinearKeyframe(12, duration: 0.08)
                LinearKeyframe(-8, duration: 0.08)
                LinearKeyframe(5, duration: 0.07)
                LinearKeyframe(0, duration: 0.06)
            }
        }
        .task {
            // Focus once the presentation has settled, so the number pad comes up reliably.
            try? await Task.sleep(for: .milliseconds(350))
            focused = isEnabled
        }
        .onChange(of: isEnabled) { _, on in if on { focused = true } }
        .onChange(of: pin) { _, value in
            let clean = KioskLogic.sanitizePin(value)
            if clean != value { pin = clean }
        }
    }

    private func submit() {
        guard isEnabled, !pin.isEmpty else { return }
        let entered = pin
        pin = ""
        onComplete(entered)
        // Return unfocuses the field after this runs; take focus back on the next turn.
        Task { focused = true }
    }
}
