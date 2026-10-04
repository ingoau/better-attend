import SwiftUI

/// The round, colour-coded outcome badge (a spinner while checking; a muted tick inside a spinning
/// ring while confirming, so it never reads as a success before the server agrees).
struct OutcomeBadge: View {
    let kind: ResultKind
    var size: CGFloat = 52

    @State private var spin = false

    var body: some View {
        ZStack {
            if kind == .confirming {
                Circle().fill(Color.secondary.opacity(0.18)).padding(size * 0.09)
                Circle().stroke(Color.secondary.opacity(0.18), lineWidth: 3)
                Circle()
                    .trim(from: 0, to: 0.28)
                    .stroke(Color.secondary, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(spin ? 360 : 0))
                    .animation(.linear(duration: 0.9).repeatForever(autoreverses: false), value: spin)
                    .onAppear { spin = true }
                Image(systemName: kind.systemImage)
                    .font(.system(size: size * 0.38, weight: .bold))
                    .foregroundStyle(.secondary)
            } else {
                Circle().fill(kind.tone.color)
                if kind == .checking {
                    ProgressView()
                        .tint(kind.tone.onColor)
                        .controlSize(size > 80 ? .large : .regular)
                } else {
                    Image(systemName: kind.systemImage)
                        .font(.system(size: size * 0.44, weight: .bold))
                        .foregroundStyle(kind.tone.onColor)
                        .transition(.scale(scale: 0.5).combined(with: .opacity))
                }
            }
        }
        .frame(width: size, height: size)
        .animation(.bouncy(duration: 0.35), value: kind)
        .accessibilityHidden(true)
    }
}

/// How old the roster behind an offline result is. Quiet while it's fresh; a warning once it's over an
/// hour old (or missing), because the person may have withdrawn or changed since.
struct RosterNoteView: View {
    let text: String
    let stale: Bool

    var body: some View {
        if stale {
            HStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill")
                VStack(alignment: .leading, spacing: 1) {
                    Text(text).font(.subheadline.weight(.semibold))
                    Text("It may be out of date. Check their ticket and ID carefully.").font(.caption)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .foregroundStyle(Tone.warning.onContainer)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Tone.warning.container, in: .rect(cornerRadius: 16, style: .continuous))
            .accessibilityElement(children: .combine)
        } else {
            Label(text, systemImage: "clock")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }
}

/// Prominent labels for the minimum safety information staff must see at a glance.
struct SafetyAlerts: View {
    let participant: Participant

    var body: some View {
        if participant.hasSafetyAlert {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 6) { pills }
                VStack(alignment: .leading, spacing: 6) { pills }
            }
        }
    }

    @ViewBuilder private var pills: some View {
        if participant.hasAnaphylaxisRisk { AlertPill(text: "Anaphylaxis risk", systemImage: "cross.case.fill", tone: .danger) }
        if participant.requiresRefrigeration { AlertPill(text: "Refrigerated meds", systemImage: "snowflake", tone: .info) }
        if participant.highSupportFlag { AlertPill(text: "High support", systemImage: "person.2.fill", tone: .warning) }
    }
}

/// A solid, high-contrast pill (unlike `Pill`, which is tinted) for safety alerts.
struct AlertPill: View {
    let text: String
    let systemImage: String
    let tone: Tone

    var body: some View {
        Label(text, systemImage: systemImage)
            .font(.subheadline.weight(.semibold))
            .lineLimit(1)
            .fixedSize()
            .foregroundStyle(tone.onColor)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(tone.color, in: .capsule)
    }
}

/// The scanner's result card: outcome, who it was, safety alerts and actions. Swipe down or tap
/// close to dismiss. The outcome is announced to VoiceOver when it arrives.
struct ScanResultCardView: View {
    let card: ScanCard
    var onDismiss: () -> Void
    var onUndo: () -> Void
    var onRetry: () -> Void
    var onDetails: (() -> Void)?

    @State private var drag: CGFloat = 0
    @State private var armed = false
    private let threshold: CGFloat = 70

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            if let p = card.participant {
                person(p)
                    .padding(.horizontal, 8)
                    .padding(.top, 12)
            } else if let ctx = card.contextName, card.kind != .rejected {
                Label(ctx, systemImage: "mappin.and.ellipse")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 10)
                    .padding(.top, 10)
            }
            if let note = card.rosterNote {
                RosterNoteView(text: note, stale: card.rosterStale)
                    .padding(.horizontal, 8)
                    .padding(.top, 10)
            }
            actions
        }
        .padding(8)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: .rect(cornerRadius: 30, style: .continuous))
        .shadow(color: .black.opacity(0.25), radius: 18, y: 6)
        .offset(y: drag)
        .opacity(1 - 0.35 * min(max(drag, 0) / (threshold * 2.5), 1))
        .gesture(dismissGesture)
        .sensoryFeedback(.impact(weight: .light), trigger: armed) { _, new in new && Haptics.enabled }
        .animation(.smooth(duration: 0.3), value: card)
        .accessibilityAction(named: "Dismiss", onDismiss)
    }

    // MARK: Parts

    private var header: some View {
        HStack(spacing: 14) {
            OutcomeBadge(kind: card.kind)
            VStack(alignment: .leading, spacing: 2) {
                Text(card.title)
                    .font(.title3.weight(.bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                if let message = card.message {
                    Text(message)
                        .font(.subheadline)
                        .lineLimit(3)
                }
            }
            .foregroundStyle(card.kind.tone.onContainer)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(card.accessibilityText)
            .accessibilityAddTraits(.updatesFrequently)
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(card.kind.tone.onContainer.opacity(0.8))
                    .frame(width: 30, height: 30)
                    .background(card.kind.tone.onContainer.opacity(0.1), in: .circle)
                    .frame(width: 44, height: 44)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Dismiss result")
        }
        .padding(.leading, 12)
        .padding(.vertical, 12)
        .padding(.trailing, 4)
        .background(card.kind.tone.container, in: .rect(cornerRadius: 22, style: .continuous))
    }

    private func person(_ p: Participant) -> some View {
        let (title, full) = ScanLogic.headline(p)
        let sub = [full, p.pronouns, card.contextName].compactMap { $0?.nonBlank }.joined(separator: " · ")
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 14) {
                Avatar(name: p.name, url: p.headshotUrl, size: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.title3.weight(.semibold))
                        .lineLimit(2)
                    if !sub.isEmpty {
                        Text(sub)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .accessibilityElement(children: .combine)
            SafetyAlerts(participant: p)
        }
    }

    @ViewBuilder private var actions: some View {
        let details = card.participant != nil ? onDetails : nil
        if card.canUndo || card.busy || card.retryable || details != nil {
            HStack(spacing: 10) {
                if card.canUndo || card.busy {
                    Button(action: onUndo) {
                        HStack(spacing: 6) {
                            if card.busy {
                                ProgressView().controlSize(.small)
                            } else {
                                Image(systemName: "arrow.uturn.backward")
                            }
                            Text("Undo")
                        }
                    }
                    .buttonStyle(.bordered)
                    .disabled(card.busy)
                }
                if card.retryable {
                    Button("Retry", systemImage: "arrow.clockwise", action: onRetry)
                        .buttonStyle(.bordered)
                }
                Spacer(minLength: 0)
                if let details {
                    Button(action: details) {
                        HStack(spacing: 4) {
                            Text("Details")
                            Image(systemName: "chevron.right").imageScale(.small)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .buttonBorderShape(.capsule)
            .controlSize(.regular)
            .fontWeight(.semibold)
            .padding(.horizontal, 6)
            .padding(.top, 12)
            .padding(.bottom, 4)
        } else {
            Spacer().frame(height: 4)
        }
    }

    // MARK: Swipe to dismiss

    private var dismissGesture: some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { value in
                let dy = value.translation.height
                // Downwards follows the finger; upwards resists (rubber band).
                drag = dy >= 0 ? dy : -min(threshold / 3, sqrt(-dy) * 3)
                armed = drag > threshold
            }
            .onEnded { value in
                if drag > threshold || value.predictedEndTranslation.height > threshold * 3 {
                    withAnimation(.smooth(duration: 0.25)) { drag = 400 }
                    onDismiss()
                } else {
                    withAnimation(.spring(duration: 0.35, bounce: 0.3)) { drag = 0 }
                }
                armed = false
            }
    }
}
