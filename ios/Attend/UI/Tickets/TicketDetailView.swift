import PassKit
import SwiftUI
import UniformTypeIdentifiers

/// A participant's pass, like a boarding pass in Wallet. With several confirmed tickets they can swipe
/// sideways between them (same order as the tickets list). Each pass refreshes the first time it's shown;
/// brightness goes to max while a QR code is on screen, and everything works from the cache offline.
struct TicketDetailView: View {
    let ticketId: String

    @Environment(AppModel.self) private var app
    @Environment(\.openURL) private var openURL
    @Namespace private var qrZoom

    @State private var visibleId: String?
    @State private var pages: [String: PageState] = [:]
    @State private var refreshed: Set<String> = []
    @State private var qrOnScreen: [String: Bool] = [:]
    @State private var fullScreenQR = false
    @State private var web: TicketWebPage?
    @State private var pendingPass: TicketPendingPass?
    @State private var problem: Problem?
    @State private var toast: String?

    /// Per-pass screen state, so swiping doesn't mix up spinners and errors.
    struct PageState: Equatable {
        var loading = true
        var error: String?
        var walletLoading = false
    }

    struct Problem: Identifiable {
        var title: String
        var message: String
        var id: String { title + message }
    }

    private var pageIds: [String] { TicketLogic.pagerIds(app.tickets.tickets, openedId: ticketId) }
    private var currentId: String { visibleId.flatMap { pageIds.contains($0) ? $0 : nil } ?? ticketId }
    private var visible: Ticket? { app.tickets.ticket(currentId) }

    var body: some View {
        let ids = pageIds
        TabView(selection: Binding(get: { currentId }, set: { visibleId = $0 })) {
            ForEach(ids, id: \.self) { id in
                page(id)
                    .tag(id)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(visible?.confirmed == false ? "Registration" : "Your Pass")
        .navigationBarTitleDisplayMode(.inline)
        .modifier(TicketSubtitleModifier(subtitle: ids.count > 1 ? "\((ids.firstIndex(of: currentId) ?? 0) + 1) of \(ids.count)" : nil))
        .toolbar { toolbar }
        .task(id: currentId) {
            guard refreshed.insert(currentId).inserted else { return }
            await refresh(currentId)
        }
        // While the event is on and they're not checked in yet, pick up the check-in as it happens.
        .poll(every: .seconds(60), id: currentId, runImmediately: false) {
            guard let t = visible, t.confirmed, !t.checkedIn, TicketLogic.countdown(t.event) == .live else { return }
            await refresh(t.id, quiet: true)
        }
        .onChange(of: currentId) { Haptics.selection() }
        .boostsPassBrightness(visible?.confirmed == true && qrOnScreen[currentId, default: true], id: "ticket-pass")
        .fullScreenCover(isPresented: $fullScreenQR) {
            if let t = visible {
                TicketFullScreenQR(ticket: t)
                    .navigationTransition(.zoom(sourceID: "qr-\(t.id)", in: qrZoom))
            }
        }
        .ticketSafariSheet($web)
        .sheet(item: $pendingPass) { p in
            TicketAddPassesSheet(pass: p.pass).ignoresSafeArea()
        }
        .alert(problem?.title ?? "", isPresented: Binding(get: { problem != nil }, set: { if !$0 { problem = nil } }), presenting: problem) { _ in
            Button("OK", role: .cancel) {}
        } message: { p in
            Text(p.message)
        }
        .overlay(alignment: .bottom) {
            if let toast {
                Label(toast, systemImage: "checkmark.circle.fill")
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.regularMaterial, in: .capsule)
                    .shadow(color: .black.opacity(0.12), radius: 10, y: 4)
                    .padding(.bottom, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .accessibilityAddTraits(.isStaticText)
            }
        }
        .animation(.snappy, value: toast)
    }

    // MARK: Pages

    @ViewBuilder
    private func page(_ id: String) -> some View {
        let state = pages[id] ?? PageState()
        if let ticket = app.tickets.ticket(id) {
            ScrollView {
                VStack(spacing: 16) {
                    TicketPassCard(
                        ticket: ticket,
                        zoom: qrZoom,
                        showQR: { showQR() },
                        finishRegistration: ticket.onboardingUrl.flatMap(URL.init(string:)).map { url in { open(url) } },
                        qrVisibility: { qrOnScreen[id] = $0 }
                    )
                    if let error = state.error {
                        NoticeBanner(message: "\(error) Showing your saved pass — it still works offline.") {
                            Task { await refresh(id) }
                        }
                        .transition(.opacity)
                    }
                    if ticket.confirmed, ticket.appleWalletUrl?.nonBlank != nil, PKAddPassesViewController.canAddPasses() {
                        TicketAddPassButton { Task { await addToWallet(ticket) } }
                            .frame(height: 50)
                            .frame(maxWidth: 360)
                            .opacity(state.walletLoading ? 0.4 : 1)
                            .overlay { if state.walletLoading { ProgressView() } }
                            .disabled(state.walletLoading)
                            .accessibilityLabel(state.walletLoading ? "Preparing your pass" : "Add to Apple Wallet")
                    }
                    TicketCountdownCard(event: ticket.event)
                    TicketVenueCard(event: ticket.event, fallback: { url in openURL(url) })
                    if let travel = ticket.travelInbound {
                        TicketTravelCard(travel: travel, timezone: ticket.event.timezone)
                    }
                    if !ticket.messages.isEmpty {
                        TicketMessagesCard(messages: ticket.messages)
                    }
                    TicketSafetyCard(report: { open(TicketLogic.incidentURL) }, call: callHotline)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 32)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .animation(.smooth, value: state)
            }
            .refreshable { await refresh(id) }
        } else if state.loading {
            ProgressView("Loading your ticket…")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ContentUnavailableView {
                Label("Couldn't Load This Ticket", systemImage: "ticket")
            } description: {
                Text(state.error ?? "It may have been removed.")
            } actions: {
                Button("Try Again") { Task { await refresh(id) } }
                    .buttonStyle(.bordered)
            }
        }
    }

    // MARK: Toolbar

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if let t = visible {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    if t.confirmed {
                        Button("Show QR Code", systemImage: "qrcode", action: showQR)
                        ShareLink(item: TicketPassShareItem(ticket: t),
                                  preview: SharePreview("\(t.event.name) pass", image: Image(systemName: "qrcode"))) {
                            Label("Share Pass…", systemImage: "square.and.arrow.up")
                        }
                        if let code = t.shortCode?.nonBlank {
                            Button("Copy Code", systemImage: "doc.on.doc") { copy(code) }
                        }
                    }
                    if case .incomplete = TicketLogic.status(t), let url = t.onboardingUrl.flatMap(URL.init(string:)) {
                        Button("Finish Registration", systemImage: "safari") { open(url) }
                    }
                    Divider()
                    Button("Refresh", systemImage: "arrow.clockwise") {
                        Task { await refresh(t.id) }
                    }
                } label: {
                    Label("More", systemImage: "ellipsis")
                }
            }
        }
    }

    // MARK: Actions

    private func showQR() {
        guard visible?.confirmed == true else { return }
        Haptics.tap()
        fullScreenQR = true
    }

    private func open(_ url: URL) {
        Haptics.tap()
        web = TicketWebPage(url: url)
    }

    private func copy(_ code: String) {
        UIPasteboard.general.string = code
        Haptics.confirm()
        toast = "Code Copied"
        Task {
            try? await Task.sleep(for: .seconds(1.6))
            toast = nil
        }
    }

    private func callHotline() {
        Haptics.tap()
        openURL(TicketLogic.hotlineURL) { accepted in
            if !accepted {
                Haptics.reject()
                problem = Problem(title: "Can't Call From This Device", message: "Call \(TicketLogic.hotlineDisplay) from any phone. It's free and open 24/7.")
            }
        }
    }

    private func refresh(_ id: String, quiet: Bool = false) async {
        if !quiet { pages[id, default: PageState()].loading = true }
        do {
            try await app.tickets.refreshTicket(id)
            pages[id, default: PageState()].loading = false
            pages[id]?.error = nil
        } catch where error.isCancellation {
            pages[id, default: PageState()].loading = false
            refreshed.remove(id)
        } catch {
            pages[id, default: PageState()].loading = false
            if !quiet { pages[id]?.error = error.friendlyMessage }
        }
    }

    private func addToWallet(_ t: Ticket) async {
        guard pages[t.id]?.walletLoading != true else { return }
        Haptics.tap()
        pages[t.id, default: PageState(loading: false)].walletLoading = true
        defer { pages[t.id]?.walletLoading = false }
        do {
            let data = try await app.tickets.walletPass(for: t)
            let pass: PKPass
            do {
                pass = try PKPass(data: data)
            } catch {
                throw WalletError.unreadable
            }
            if PKPassLibrary().containsPass(pass), let url = pass.passURL {
                // Already added: show it in Wallet instead.
                openURL(url)
            } else {
                pendingPass = TicketPendingPass(pass: pass)
            }
        } catch where error.isCancellation {
            return
        } catch {
            Haptics.reject()
            let message: String
            if let api = error as? APIError, api.isUnprocessable {
                message = "Apple Wallet couldn't create your pass right now. Try again later — your QR code here works either way."
            } else if error is WalletError {
                message = "The pass couldn't be opened. Your QR code here works either way."
            } else {
                message = error.friendlyMessage
            }
            problem = Problem(title: "Couldn't Add to Wallet", message: message)
        }
    }

    private enum WalletError: Error { case unreadable }
}

// MARK: - Sharing

/// The pass as a PNG (QR, name and code on white), rendered only when actually shared.
struct TicketPassShareItem: Transferable {
    let ticket: Ticket

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .png) { item in
            try await MainActor.run {
                guard let data = item.render() else { throw CocoaError(.fileWriteUnknown) }
                return data
            }
        }
        .suggestedFileName { "\($0.ticket.event.name) pass.png" }
    }

    @MainActor
    private func render() -> Data? {
        let renderer = ImageRenderer(content: TicketQRSheet(ticket: ticket, compact: true).frame(width: 390, height: 600))
        renderer.scale = 3
        return renderer.uiImage?.pngData()
    }
}
