import SwiftUI

enum SettingsLinks {
    static let attend = URL(string: "https://attend.hackclub.com")!
    static let incident = URL(string: "https://hack.club/incident")!
    static let hotline = URL(string: "tel:+18556254225")!
    static let hotlineDisplay = "+1 (855) 625-4225"
    static let source = URL(string: "https://github.com/ingoau/better-attend-mobile")!
}

/// Account, preferences, offline data and sign-out. Presented as a sheet from the account button.
struct SettingsView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss

    @State private var syncing = false
    @State private var clearing = false
    @State private var dataMessage: String?
    @State private var confirmClear = false
    @State private var confirmSignOut = false

    private var pending: Int { app.scans.pending.count }

    var body: some View {
        @Bindable var settings = app.settings
        NavigationStack {
            Form {
                SettingsAccountHeader(user: app.user)

                if app.isOrganizer, !(app.events.events ?? []).isEmpty {
                    eventSection
                }

                Section("Appearance") {
                    Picker("Theme", selection: $settings.themeMode) {
                        ForEach(ThemeMode.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                    .accessibilityLabel("Theme")
                }

                scannerSection(settings: settings)
                offlineSection
                widgetsSection
                helpSection
                aboutSection

                Section {
                    Button(role: .destructive) {
                        Haptics.tap()
                        confirmSignOut = true
                    } label: {
                        Text("Sign Out").frame(maxWidth: .infinity)
                    }
                    .confirmationDialog("Sign out?", isPresented: $confirmSignOut, titleVisibility: .visible) {
                        Button("Sign Out", role: .destructive) { signOut() }
                    } message: {
                        Text(signOutMessage)
                    }
                } footer: {
                    if app.isDemo {
                        Label("Demo mode · data resets every launch", systemImage: "theatermasks")
                            .font(.footnote)
                            .frame(maxWidth: .infinity)
                            .padding(.top, 8)
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onChange(of: settings.themeMode) { Haptics.selection() }
            .onChange(of: settings.sounds) { _, on in
                Haptics.selection()
                if on { ScanFeedbackPlayer.shared.play(.success, sound: true, haptic: false) }
            }
            .onChange(of: settings.haptics) { _, on in
                // RootView syncs this too, but the preview below must not wait for it.
                Haptics.enabled = on
                if on { Haptics.confirm() }
            }
            .onChange(of: settings.keepScreenOn) { Haptics.selection() }
        }
    }

    // MARK: Sections

    @ViewBuilder private var eventSection: some View {
        let event = app.events.selectedEvent
        Section {
            NavigationLink {
                SettingsEventList()
            } label: {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(event?.name ?? "Choose an Event")
                        if let event {
                            Text([EventLogic.roleLabel(event.role), event.locationCity?.nonBlank].compactMap { $0 }.joined(separator: " · "))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                } icon: {
                    SettingsIcon(systemImage: "calendar", color: HackClub.red)
                }
            }
        } header: {
            Text("Current Event")
        } footer: {
            Text("Home, Scan, People and Travel all follow this event.")
        }
    }

    private func scannerSection(settings: SettingsStore) -> some View {
        @Bindable var settings = settings
        return Section {
            Toggle(isOn: $settings.sounds) {
                SettingsLabel("Sounds", subtitle: "A different sound for each scan result", systemImage: "speaker.wave.2.fill", color: HackClub.red)
            }
            Toggle(isOn: $settings.haptics) {
                SettingsLabel("Haptics", subtitle: "Feel scans, taps and gestures", systemImage: "iphone.radiowaves.left.and.right", color: HackClub.purple)
            }
            Toggle(isOn: $settings.keepScreenOn) {
                SettingsLabel("Keep Screen Awake", subtitle: "While the scanner is open", systemImage: "sun.max.fill", color: HackClub.orange)
            }
        } header: {
            Text("Scanner")
        }
    }

    @ViewBuilder private var offlineSection: some View {
        let roster = app.events.selectedEvent.flatMap { app.participants.roster($0.id) }
        Section {
            HStack(spacing: 12) {
                SettingsLabel(
                    "Offline Scans",
                    subtitle: pending == 0 ? "Everything's synced" : "\(pending) scan\(pending == 1 ? "" : "s") waiting to sync",
                    subtitleTone: pending == 0 ? nil : .warning,
                    systemImage: pending == 0 ? "checkmark.icloud.fill" : "icloud.and.arrow.up.fill",
                    color: pending == 0 ? HackClub.green : HackClub.orange
                )
                .contentTransition(.numericText(value: Double(pending)))
                Spacer(minLength: 8)
                if syncing {
                    ProgressView()
                } else if pending > 0 {
                    Button("Sync Now") { Task { await syncNow() } }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .font(.subheadline.weight(.semibold))
                }
            }
            .animation(.snappy, value: pending)
            .animation(.snappy, value: syncing)

            if let lastSync = roster?.lastSyncAt, let event = app.events.selectedEvent {
                LabeledContent {
                    Text(Time.ago(lastSync) ?? "–")
                } label: {
                    SettingsLabel("Participants", subtitle: "Saved for \(event.name)", systemImage: "person.2.fill", color: HackClub.blue)
                }
            }

            Button {
                Haptics.tap()
                confirmClear = true
            } label: {
                SettingsLabel(
                    "Clear Cached Data",
                    subtitle: pending > 0 ? "Sync your offline scans first" : "Removes saved participants, travel and tickets. You stay signed in.",
                    systemImage: "trash.fill", color: .gray
                )
            }
            .tint(.primary)
            .disabled(pending > 0 || clearing)
            .confirmationDialog("Clear cached data?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Clear Cached Data", role: .destructive) { Task { await clearCache() } }
            } message: {
                Text("Saved participants, travel and tickets are removed from this device and downloaded again when needed. The first sync of a big event can take a moment.")
            }
        } header: {
            Text("Offline Data")
        } footer: {
            if let dataMessage {
                Text(dataMessage)
                    .transition(.opacity)
            }
        }
    }

    private var widgetsSection: some View {
        Section {
            SettingsLabel(
                "Widgets & Controls",
                subtitle: "Touch and hold your Home Screen or Lock Screen, tap Edit, then Add Widget and choose Attend. Controls for scanning live in Control Center.",
                systemImage: "square.grid.2x2.fill", color: HackClub.cyan
            )
            .padding(.vertical, 2)
        } header: {
            Text("Widgets")
        }
    }

    private var helpSection: some View {
        Section("Safety & Help") {
            SettingsLinkRow(title: "Report an Incident", subtitle: "hack.club/incident", systemImage: "exclamationmark.bubble.fill",
                    color: HackClub.red, url: SettingsLinks.incident)
            SettingsLinkRow(title: "24/7 Safety Hotline", subtitle: SettingsLinks.hotlineDisplay, systemImage: "phone.fill",
                    color: HackClub.green, url: SettingsLinks.hotline, external: false)
        }
    }

    private var aboutSection: some View {
        Section {
            LabeledContent {
                Text(Self.version)
            } label: {
                SettingsLabel("Better Attend", systemImage: "info.circle.fill", color: HackClub.blue)
            }
            SettingsLinkRow(title: "Hack Club Attend", subtitle: "attend.hackclub.com", systemImage: "globe",
                    color: HackClub.red, url: SettingsLinks.attend)
            SettingsLinkRow(title: "Source Code", subtitle: "github.com/ingoau/better-attend-mobile",
                    systemImage: "chevron.left.forwardslash.chevron.right", color: HackClub.dark, url: SettingsLinks.source)
        } header: {
            Text("About")
        } footer: {
            Text("No analytics, no tracking. Participant data is encrypted on this device, only fetched for events you staff, and wiped when you sign out.")
        }
    }

    // MARK: Actions

    private var signOutMessage: String {
        var s = "Participant data saved on this device will be wiped."
        if pending > 0 {
            s += " \(pending) offline scan\(pending == 1 ? " hasn't" : "s haven't") synced yet and will be lost."
        }
        return s
    }

    private func signOut() {
        Task {
            await app.auth.signOut()
            Haptics.confirm()
        }
    }

    private func syncNow() async {
        Haptics.tap()
        syncing = true
        let left = await app.scans.flush()
        syncing = false
        withAnimation {
            if left == 0 {
                Haptics.confirm()
                dataMessage = "All scans synced."
            } else {
                Haptics.reject()
                dataMessage = "\(left) scan\(left == 1 ? "" : "s") still waiting. We'll keep trying when you're back online."
            }
        }
    }

    private func clearCache() async {
        clearing = true
        // Wipes the encrypted roster / travel / ticket cache; the session and preferences stay.
        await app.participants.clear()
        app.travel.clear()
        app.tickets.clear()
        let user = app.user
        Task {
            if user?.isOrganizer == true || user?.globalAdmin == true { _ = try? await app.events.refresh() }
            if user?.isParticipant == true { _ = try? await app.tickets.refresh() }
        }
        clearing = false
        Haptics.confirm()
        withAnimation { dataMessage = "Cached data cleared. It'll download again as you use the app." }
    }

    static var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String
        return build.map { "\(short) (\($0))" } ?? short
    }
}
