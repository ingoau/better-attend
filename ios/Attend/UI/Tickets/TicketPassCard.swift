import SwiftUI

/// The pass itself: a brand header, the check-in QR on white (or what's left to do when registration
/// isn't finished), a perforation, and a stub with venue and doors.
struct TicketPassCard: View {
    let ticket: Ticket
    let zoom: Namespace.ID
    var showQR: () -> Void
    var finishRegistration: (() -> Void)?
    var qrVisibility: (Bool) -> Void = { _ in }

    @State private var perforationY: CGFloat?
    @State private var celebrate = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Passes already celebrated this session (the confetti plays once, not on every visit).
    @MainActor private static var celebrated: Set<String> = []

    private var status: TicketLogic.Status { TicketLogic.status(ticket) }
    private var event: TicketEvent { ticket.event }

    var body: some View {
        VStack(spacing: 0) {
            header
            if ticket.confirmed { qrSection } else { pendingSection }
            TicketPerforation()
                .padding(.horizontal, 24)
                .onGeometryChange(for: CGFloat.self) { $0.frame(in: .named("pass")).midY } action: { perforationY = $0 }
            stub
        }
        .coordinateSpace(.named("pass"))
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(TicketPassShape(cornerRadius: 28, notchRadius: 13, notchY: perforationY))
        .shadow(color: .black.opacity(0.08), radius: 12, y: 4)
        .overlay {
            if celebrate > 0 && !reduceMotion {
                TicketConfetti(trigger: celebrate)
                    .allowsHitTesting(false)
            }
        }
        .sensoryFeedback(.success, trigger: celebrate) { _, _ in Haptics.enabled }
        .onChange(of: ticket.checkedIn) { was, now in
            if !was && now { cheer() }
        }
        .onAppear {
            // Checked in during the event: a small cheer the first time the pass is opened.
            if ticket.checkedIn, TicketLogic.countdown(event) == .live, !Self.celebrated.contains(ticket.id) { cheer() }
        }
    }

    private func cheer() {
        Self.celebrated.insert(ticket.id)
        celebrate += 1
    }

    // MARK: Header

    private var headerColors: [Color] {
        if status.isClosed { return [Color(hex: 0x6B6B76), Color(hex: 0x4A4A55)] }
        return [HackClub.red, Color(hex: 0xC81E3A)]
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text("ATTENDEE PASS")
                    .font(.caption.weight(.bold))
                    .tracking(2)
                    .foregroundStyle(.white.opacity(0.85))
                Spacer()
                if status == .checkedIn {
                    Label("Checked in", systemImage: "checkmark.seal.fill")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .symbolEffect(.bounce, value: celebrate)
                        .transition(.scale.combined(with: .opacity))
                } else if !ticket.confirmed {
                    TicketStatusPill(status: status, onBrand: true)
                }
            }
            Text(event.name)
                .font(.title.weight(.heavy))
                .foregroundStyle(.white)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            if let name = ticket.attendeeName?.nonBlank {
                Text(name)
                    .font(.headline)
                    .foregroundStyle(.white.opacity(0.9))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24)
        .padding(.top, 20)
        .padding(.bottom, 22)
        .background(LinearGradient(colors: headerColors, startPoint: .topLeading, endPoint: .bottomTrailing))
        .animation(.smooth, value: status)
    }

    // MARK: QR

    private var qrSection: some View {
        VStack(spacing: 14) {
            Button(action: showQR) {
                VStack(spacing: 8) {
                    TicketQRCodeView(payload: ticket.qrPayload)
                        .frame(maxWidth: 250)
                    if let code = ticket.shortCode?.nonBlank {
                        Text(code)
                            .font(.system(.title3, design: .monospaced, weight: .semibold))
                            .tracking(4)
                            .foregroundStyle(.black)
                            .accessibilityLabel("Short code \(TicketPassLogic.spokenCode(code))")
                    }
                }
                .padding(.horizontal, 18)
                .padding(.top, 18)
                .padding(.bottom, 14)
                .background(.white, in: .rect(cornerRadius: 22, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .strokeBorder(status == .checkedIn ? Tone.success.color : .black.opacity(0.08),
                                      lineWidth: status == .checkedIn ? 3 : 1)
                }
                .contentShape(.rect(cornerRadius: 22))
            }
            .buttonStyle(TicketPressableStyle())
            .matchedTransitionSource(id: "qr-\(ticket.id)", in: zoom)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Check-in QR code\(ticket.shortCode.map { ", short code \(TicketPassLogic.spokenCode($0))" } ?? "")")
            .accessibilityHint("Shows the code full screen")
            .onScrollVisibilityChange(threshold: 0.3) { qrVisibility($0) }

            if status == .checkedIn {
                Label("You're checked in", systemImage: "checkmark.circle.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Tone.success.color)
                    .symbolEffect(.bounce, value: celebrate)
            }
            Text("Show this at check-in · Tap to enlarge")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24)
        .padding(.vertical, 24)
    }

    // MARK: Not confirmed

    private var pendingSection: some View {
        VStack(spacing: 12) {
            Image(systemName: status.isClosed ? "nosign" : "hourglass")
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(status.isClosed ? Color.secondary : Tone.warning.color)
                .frame(width: 76, height: 76)
                .background(status.isClosed ? Color(uiColor: .tertiarySystemFill) : Tone.warning.container, in: .circle)
                .symbolEffect(.pulse, options: .repeating, isActive: !status.isClosed && !reduceMotion)
                .accessibilityHidden(true)
            Text(status.isClosed ? status.label : "Your pass unlocks once registration is complete.")
                .font(.headline)
                .multilineTextAlignment(.center)
            if let reason = status.passReason {
                Text(reason)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            if case .incomplete = status, let finishRegistration {
                Button(action: finishRegistration) {
                    Label("Complete Registration", systemImage: "arrow.up.forward.app")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                .padding(.top, 6)
                .accessibilityHint("Opens your registration form")
            }
        }
        .frame(maxWidth: .infinity)
        .padding(24)
    }

    // MARK: Stub

    private var stub: some View {
        let (venue, _) = TicketLogic.venueLines(event)
        return HStack(alignment: .top, spacing: 16) {
            TicketStubTile(label: "VENUE", value: event.locationCity?.nonBlank ?? venue, detail: event.locationCountry?.nonBlank)
            TicketStubTile(label: "DOORS", value: Time.time(event.startsAt, tz: event.timezone) ?? "TBA", detail: Time.day(event.startsAt, tz: event.timezone))
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 18)
    }
}

private struct TicketStubTile: View {
    let label: String
    let value: String
    var detail: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption2.weight(.bold))
                .tracking(1.5)
                .foregroundStyle(HackClub.red)
            Text(value)
                .font(.headline)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            if let detail {
                Text(detail).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Confetti

/// A short, subtle burst of Hack Club–coloured confetti from the top of the pass.
struct TicketConfetti: View {
    let trigger: Int
    @State private var start = Date()

    private struct Piece {
        var x: Double, vx: Double, vy: Double, spin: Double, size: Double, color: Color, delay: Double
    }

    private static let colors = [HackClub.red, HackClub.orange, HackClub.yellow, HackClub.green, HackClub.cyan, HackClub.blue, HackClub.purple]
    private static let duration = 1.8

    @State private var pieces: [Piece] = (0..<36).map { i in
        Piece(x: Double.random(in: 0.2...0.8), vx: Double.random(in: -140...140), vy: Double.random(in: -420 ... -220),
              spin: Double.random(in: -8...8), size: Double.random(in: 5...9), color: TicketConfetti.colors[i % TicketConfetti.colors.count],
              delay: Double.random(in: 0...0.15))
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 60, paused: false)) { context in
            Canvas { ctx, size in
                let t = context.date.timeIntervalSince(start)
                guard t < Self.duration else { return }
                for p in pieces {
                    let lt = max(0, t - p.delay)
                    let x = p.x * size.width + p.vx * lt
                    let y = size.height * 0.28 + p.vy * lt + 0.5 * 700 * lt * lt
                    let alpha = max(0, 1 - lt / (Self.duration - p.delay))
                    var c = ctx
                    c.opacity = alpha
                    c.translateBy(x: x, y: y)
                    c.rotate(by: .radians(p.spin * lt))
                    c.fill(Path(roundedRect: CGRect(x: -p.size / 2, y: -p.size / 4, width: p.size, height: p.size / 2), cornerRadius: 1),
                           with: .color(p.color))
                }
            }
        }
        .onChange(of: trigger) { start = Date() }
        .accessibilityHidden(true)
    }
}

// MARK: - Full-screen QR

/// Door mode: the biggest possible QR on pure white with the name and short code as a fallback for staff
/// to type. Theme-independent on purpose; scanners need maximum contrast. Swipe down or tap Close.
struct TicketFullScreenQR: View {
    let ticket: Ticket
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        TicketQRSheet(ticket: ticket)
            .overlay(alignment: .topTrailing) {
                Button {
                    Haptics.tap()
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.body.weight(.semibold))
                        .frame(width: 30, height: 30)
                }
                .modifier(TicketCloseButtonStyle())
                .accessibilityLabel("Close")
                .padding(16)
            }
            .environment(\.colorScheme, .light)
            .boostsPassBrightness(true, id: "fullscreen-qr")
            .persistentSystemOverlays(.hidden)
    }
}

private struct TicketCloseButtonStyle: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.buttonStyle(.glass).buttonBorderShape(.circle).tint(.black)
        } else {
            content.buttonStyle(.bordered).buttonBorderShape(.circle).tint(.black)
        }
    }
}

/// Event, attendee, QR and short code on white; the full-screen view and the shared image.
struct TicketQRSheet: View {
    let ticket: Ticket
    var compact = false
    private let ink = Color(hex: 0x121217)

    var body: some View {
        ZStack {
            Color.white.ignoresSafeArea()
            VStack(spacing: compact ? 14 : 20) {
                VStack(spacing: 4) {
                    Text(ticket.event.name)
                        .font(.headline)
                        .foregroundStyle(ink.opacity(0.6))
                    Text(ticket.attendeeName?.nonBlank ?? "Attendee")
                        .font(.largeTitle.weight(.heavy))
                        .foregroundStyle(ink)
                        .minimumScaleFactor(0.6)
                        .lineLimit(2)
                }
                .multilineTextAlignment(.center)
                TicketQRCodeView(payload: ticket.qrPayload)
                    .frame(maxWidth: 420)
                    .padding(.horizontal, compact ? 24 : 8)
                if let code = ticket.shortCode?.nonBlank {
                    Text(code)
                        .font(.system(.title2, design: .monospaced, weight: .semibold))
                        .tracking(5)
                        .foregroundStyle(ink)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 8)
                        .background(Color(hex: 0xF1F1F4), in: .rect(cornerRadius: 14, style: .continuous))
                        .accessibilityLabel("Short code \(TicketPassLogic.spokenCode(code))")
                }
                if !compact {
                    Label(ticket.checkedIn ? "You're checked in" : "Brightness turned up for scanning",
                          systemImage: ticket.checkedIn ? "checkmark.circle.fill" : "sun.max.fill")
                        .font(.subheadline)
                        .foregroundStyle(ticket.checkedIn ? Color(hex: 0x0E8A63) : ink.opacity(0.55))
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, compact ? 24 : 56)
        }
    }
}
