import SwiftUI

/// The Scan tab: continuous QR scanning, NFC badges, find-person, and the offline queue.
struct ScanView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        ScanScreen(app: app)
    }
}

private struct ScanScreen: View {
    @State private var model: ScanModel
    @State private var nfc = NFCBadgeReader()
    @State private var sheet: ScanSheet?
    @State private var camera: CameraAccess = CameraPermission.current()
    @State private var torchOn = false
    @State private var torchAvailable = false
    #if DEBUG
    @State private var ranDemo = false
    #endif

    @Environment(Router.self) private var router
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var colorScheme

    init(app: AppModel) {
        _model = State(initialValue: ScanModel(app: app))
    }

    private var app: AppModel { model.app }

    var body: some View {
        Group {
            if let event = model.event {
                scanner(event)
            } else if model.eventsLoading {
                ProgressView("Loading your events…").controlSize(.large)
            } else {
                ContentUnavailableView {
                    Label("Pick an Event to Start Scanning", systemImage: "calendar.badge.checkmark")
                } description: {
                    Text("Choose the event you're working at. Scans, check-ins and the offline queue are all per event.")
                } actions: {
                    Button("Choose Event") { router.sheet = .eventPicker }
                        .buttonStyle(.borderedProminent)
                        .buttonBorderShape(.capsule)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(uiColor: .systemGroupedBackground))
        .toolbar {
            if model.event != nil {
                ToolbarItem(placement: .topBarTrailing) { moreMenu }
            }
        }
        .eventToolbar(fallbackTitle: "Scan")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: model.event?.id) { await model.start() }
        .keepsScreenAwake(app.settings.keepScreenOn)
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            camera = CameraPermission.current()
            if model.pendingCount > 0 { model.syncNow(manual: false) }
        }
        .onChange(of: model.card?.kind) { _, kind in
            guard let card = model.card, kind?.isFinal == true else { return }
            AccessibilityNotification.Announcement(card.accessibilityText).post()
        }
        .sheet(item: $sheet) { sheet in
            switch sheet {
            case .find:
                FindPersonSheet(model: model) { p in
                    Haptics.tap()
                    self.sheet = nil
                    model.checkInManually(participantEventId: p.participantEventId)
                } onSubmitDirect: { input in
                    Haptics.tap()
                    self.sheet = nil
                    model.submitDirect(input)
                }
            case .pending:
                PendingScansSheet(model: model)
            case .recent:
                RecentScansSheet(model: model, onOpen: model.canOpenDetails ? { p in openDetails(p) } : nil)
            }
        }
        #if DEBUG
        .task(id: model.ready) {
            guard model.ready, !ranDemo, let scenario = ScanDemo.launchScenario, app.isDemo else { return }
            ranDemo = true
            await ScanDemo.run(scenario, model: model) { sheet = $0 }
        }
        #endif
    }

    // MARK: Scanner

    private func scanner(_ event: Event) -> some View {
        VStack(spacing: 10) {
            ContextPicker(
                contexts: model.contexts,
                selectedId: model.selectedContextId,
                timezone: event.timezone,
                error: model.contextsError,
                onSelect: model.selectContext,
                onRetry: { Task { await model.refreshContexts() } }
            )
            if model.pendingCount > 0 {
                pendingBanner
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
            viewport
        }
        .padding(.horizontal, 16)
        .padding(.top, 6)
        .padding(.bottom, 12)
        .frame(maxWidth: 720)
        .frame(maxWidth: .infinity)
        .animation(.smooth, value: model.pendingCount > 0)
    }

    private var pendingBanner: some View {
        HStack(spacing: 12) {
            Image(systemName: "icloud.and.arrow.up")
                .font(.body.weight(.semibold))
                .foregroundStyle(Tone.info.color)
            VStack(alignment: .leading, spacing: 1) {
                Text("\(model.pendingCount) waiting to sync")
                    .font(.subheadline.weight(.semibold))
                    .contentTransition(.numericText(value: Double(model.pendingCount)))
                Text(app.isOnline ? "Saved on this device" : "You're offline. They'll send when you reconnect.")
                    .font(.caption)
                    .opacity(0.8)
            }
            .foregroundStyle(Tone.info.onContainer)
            .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                model.syncNow()
            } label: {
                if model.syncing {
                    ProgressView().controlSize(.small).frame(width: 64)
                } else {
                    Text("Sync Now").fontWeight(.semibold)
                }
            }
            .buttonStyle(.bordered)
            .buttonBorderShape(.capsule)
            .controlSize(.small)
            .tint(Tone.info.color)
            .disabled(model.syncing)
        }
        .padding(.leading, 14)
        .padding(.trailing, 10)
        .padding(.vertical, 10)
        .background(Tone.info.container, in: .rect(cornerRadius: 18, style: .continuous))
        .contentShape(.rect)
        .onTapGesture { Haptics.tap(); sheet = .pending }
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: "Show waiting scans") { sheet = .pending }
    }

    private var accent: Color {
        guard let card = model.card, card.kind != .checking, card.kind != .undone else { return .white }
        return card.kind.tone.color
    }

    private var viewport: some View {
        ZStack {
            cameraBackdrop
            if camera == .granted {
                QRCameraView(
                    facing: .back,
                    torchOn: torchOn,
                    isActive: scenePhase != .background,
                    onCodes: { codes in if sheet == nil { model.onCameraCodes(codes) } },
                    onTorchAvailable: { available in
                        torchAvailable = available
                        if !available { torchOn = false }
                    },
                    onError: { model.toast = ScanToast(text: $0) }
                )
                ViewfinderOverlay(accent: accent, bias: model.card == nil ? 0.42 : 0.27)
                    .environment(\.colorScheme, .dark)
                    .animation(.smooth(duration: 0.35), value: accent)
                    .animation(.spring(duration: 0.45, bounce: 0.2), value: model.card == nil)
            } else if model.card == nil {
                CameraPermissionPanel(access: camera, onRequest: requestCamera, onFindPerson: openFind)
                    .padding(.bottom, 80)
                    .transition(.opacity)
            }
        }
        .animation(.smooth(duration: 0.25), value: model.card == nil)
        .overlay(alignment: .top) { topChips }
        .overlay(alignment: .bottom) { bottomStack }
        .clipShape(.rect(cornerRadius: 32, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 32, style: .continuous)
                .strokeBorder(Color.white.opacity(colorScheme == .dark ? 0.1 : 0), lineWidth: 1)
                .allowsHitTesting(false)
        }
    }

    private var topChips: some View {
        let status = ScanLogic.status(inFlight: model.inFlight, ready: model.ready, camera: camera)
        return VStack(spacing: 8) {
            HStack(spacing: 8) {
                CameraChip(text: status.label, dot: status.tone == .neutral ? .gray : status.tone.color)
                    .environment(\.colorScheme, .dark)
                    .accessibilityLabel("Scanner status: \(status.label)")
                if nfc.isReading {
                    CameraChip(text: "NFC on", systemImage: "wave.3.right")
                        .transition(.scale.combined(with: .opacity))
                }
                Spacer(minLength: 0)
            }
            if let toast = model.toast {
                Text(toast.text)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .floatingGlass(in: Capsule())
                    .environment(\.colorScheme, .dark)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .id(toast.id)
                    .task(id: toast.id) {
                        try? await Task.sleep(for: .seconds(3.5))
                        withAnimation { if model.toast?.id == toast.id { model.toast = nil } }
                    }
                    .onTapGesture { withAnimation { model.toast = nil } }
            }
        }
        .padding(12)
        .animation(.smooth, value: model.toast)
        .animation(.smooth, value: nfc.isReading)
    }

    private var bottomStack: some View {
        VStack(spacing: 12) {
            if let card = model.card {
                ScanResultCardView(
                    card: card,
                    onDismiss: { withAnimation(.smooth(duration: 0.25)) { model.dismiss() } },
                    onUndo: { Task { await model.undo(card) } },
                    onRetry: { Haptics.tap(); model.retry(card) },
                    onDetails: model.canOpenDetails ? { card.participant.map(openDetails) } : nil
                )
                .frame(maxWidth: 560)
                .id(card.key)
                .transition(.asymmetric(
                    insertion: .move(edge: .bottom).combined(with: .opacity),
                    removal: .opacity.combined(with: .scale(scale: 0.96))
                ))
            }
            controls
        }
        .padding(10)
        .animation(.spring(duration: 0.4, bounce: 0.18), value: model.card?.key)
    }

    private var controls: some View {
        HStack(spacing: 10) {
            if camera == .granted {
                CameraIconButton(title: torchOn ? "Turn torch off" : "Turn torch on", systemImage: torchOn ? "flashlight.on.fill" : "flashlight.off.fill",
                                 isOn: torchOn, isEnabled: torchAvailable) {
                    torchOn.toggle()
                    Haptics.impact(.light)
                }
            }
            CameraIconButton(title: "Recent scans", systemImage: "clock.arrow.circlepath") {
                Haptics.tap()
                sheet = .recent
            }
            if NFCBadgeReader.isAvailable {
                CameraIconButton(title: "Scan NFC badge", systemImage: "wave.3.right", isOn: nfc.isReading, action: startNfc)
            }
            Spacer(minLength: 0)
            Button(action: openFind) {
                Label("Find Person", systemImage: "person.crop.circle.badge.magnifyingglass")
                    .font(.headline)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    .frame(height: 52)
                    .floatingGlass(in: Capsule(), interactive: true, tint: .accentColor)
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
        }
        .frame(maxWidth: 560)
    }

    // MARK: Menu

    private var moreMenu: some View {
        @Bindable var settings = app.settings
        return Menu {
            Section {
                Button("Kiosk Mode", systemImage: "person.crop.rectangle") {
                    guard let e = model.event else { return }
                    Haptics.tap()
                    router.kiosk = KioskConfig(eventId: e.id, scanContextId: model.selectedContextId)
                }
                Button("Recent Scans", systemImage: "clock.arrow.circlepath") { sheet = .recent }
                if model.pendingCount > 0 {
                    Button("Waiting to Sync (\(model.pendingCount))", systemImage: "icloud.and.arrow.up") { sheet = .pending }
                }
                if NFCBadgeReader.isAvailable {
                    Button("Scan NFC Badge", systemImage: "wave.3.right", action: startNfc)
                }
            }
            Section {
                Toggle(isOn: $settings.sounds) { Label("Scan Sounds", systemImage: "speaker.wave.2") }
                Toggle(isOn: Binding(get: { settings.haptics }, set: { on in
                    settings.haptics = on
                    // A sample of the scan buzz (the global switch updates a moment later).
                    if on { UIImpactFeedbackGenerator(style: .soft).impactOccurred() }
                })) { Label("Vibration", systemImage: "iphone.radiowaves.left.and.right") }
                Toggle(isOn: $settings.keepScreenOn) { Label("Keep Screen On", systemImage: "sun.max") }
            }
            #if DEBUG
            if app.isDemo {
                Section("Demo") {
                    ScanDemo.SimulateMenu(model: model)
                }
            }
            #endif
        } label: {
            Label("More", systemImage: "ellipsis")
        }
    }

    // MARK: Actions

    private func openFind() {
        Haptics.tap()
        model.setQuery("")
        sheet = .find
    }

    private func openDetails(_ p: Participant) {
        guard let e = model.event else { return }
        Haptics.tap()
        sheet = nil
        router.openParticipant(eventId: e.id, participantEventId: p.participantEventId)
    }

    private func startNfc() {
        Haptics.tap()
        nfc.begin { result in
            model.onNfc(result)
        } onError: { message in
            model.toast = ScanToast(text: message)
        }
    }

    private func requestCamera() {
        Task { camera = await CameraPermission.request() }
    }
}

/// Explains why there's no camera preview and what to do about it.
struct CameraPermissionPanel: View {
    let access: CameraAccess
    var alternatives = true
    var onRequest: () -> Void
    var onFindPerson: (() -> Void)?

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: symbol)
        } description: {
            Text(message)
        } actions: {
            switch access {
            case .notDetermined:
                Button("Allow Camera", action: onRequest)
                    .buttonStyle(.borderedProminent)
            case .denied:
                Button("Open Settings") { CameraPermission.openSettings() }
                    .buttonStyle(.borderedProminent)
            case .unavailable, .granted:
                if let onFindPerson, alternatives {
                    Button("Find Person", action: onFindPerson)
                        .buttonStyle(.bordered)
                }
            }
        }
        .buttonBorderShape(.capsule)
        .environment(\.colorScheme, .dark)
    }

    private var title: String {
        switch access {
        case .notDetermined: "Scan Tickets with the Camera"
        case .denied: "Camera Access Is Off"
        case .unavailable, .granted: "Camera Unavailable"
        }
    }

    private var symbol: String {
        switch access {
        case .notDetermined: "qrcode.viewfinder"
        case .denied: "video.slash"
        case .unavailable, .granted: "camera.metering.unknown"
        }
    }

    private var message: String {
        switch access {
        case .notDetermined:
            "BetterAttend uses the camera to read ticket QR codes. Nothing is recorded."
        case .denied:
            alternatives ? "Turn on Camera for BetterAttend in Settings to scan tickets. You can still find people by name."
                : "Turn on Camera for BetterAttend in Settings, then start kiosk mode again."
        case .unavailable, .granted:
            alternatives ? "This device doesn't have a camera. Find people by name, paste a ticket ID, or scan NFC badges."
                : "This device doesn't have a camera, so it can't be used as a kiosk."
        }
    }
}
