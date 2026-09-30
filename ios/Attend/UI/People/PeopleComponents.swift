import SwiftUI
import UIKit

// Small building blocks shared by the People list and Participant detail.

// MARK: - Status

extension StatusVisual.Kind {
    var tone: Tone {
        switch self {
        case .here: .success
        case .notHere, .inactive: .neutral
        case .awaitingParent, .registering: .warning
        case .invited: .info
        }
    }
}

/// Status as a pill: "Here", "Not here", "Awaiting parent"…
struct StatusPill: View {
    let participant: Participant

    var body: some View {
        let v = StatusVisual.of(participant)
        Pill(text: v.label, tone: v.kind.tone, systemImage: v.systemImage)
    }
}

/// Tiny tinted safety icons for list rows; read as one phrase by VoiceOver.
struct SafetyIcons: View {
    let participant: Participant

    var body: some View {
        let flags = Safety.flags(participant)
        if !flags.isEmpty {
            HStack(spacing: 3) {
                ForEach(flags, id: \.label) { f in
                    let tone: Tone = f.danger ? .danger : .warning
                    Image(systemName: f.systemImage)
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(tone.onContainer)
                        .frame(width: 20, height: 20)
                        .background(tone.container, in: .rect(cornerRadius: 5))
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(flags.map(\.label).joined(separator: ", "))
        }
    }
}

// MARK: - Toast

/// A short-lived confirmation at the bottom of the screen ("Checked in at Check-in desk").
struct Toast: Equatable, Identifiable {
    var id = UUID()
    var message: String
    var systemImage: String = "checkmark.circle.fill"
    var tone: Tone = .success

    static func error(_ message: String) -> Toast { Toast(message: message, systemImage: "exclamationmark.triangle.fill", tone: .danger) }
    static func info(_ message: String, systemImage: String = "info.circle.fill") -> Toast { Toast(message: message, systemImage: systemImage, tone: .neutral) }
}

extension View {
    /// Shows `toast` as a floating capsule for a few seconds, then clears it.
    func toast(_ toast: Binding<Toast?>) -> some View {
        overlay(alignment: .bottom) {
            ZStack {
                if let t = toast.wrappedValue {
                    ToastView(toast: t)
                        .id(t.id)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .task(id: t.id) {
                            try? await Task.sleep(for: .seconds(2.8))
                            guard !Task.isCancelled, toast.wrappedValue?.id == t.id else { return }
                            withAnimation(.smooth) { toast.wrappedValue = nil }
                        }
                        .onTapGesture { withAnimation(.smooth) { toast.wrappedValue = nil } }
                }
            }
            .padding(.bottom, 12)
            .padding(.horizontal, 16)
            .animation(.spring(duration: 0.4, bounce: 0.25), value: toast.wrappedValue?.id)
        }
    }
}

private struct ToastView: View {
    let toast: Toast

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: toast.systemImage)
                .foregroundStyle(toast.tone == .neutral ? Color.secondary : toast.tone.color)
                .font(.body.weight(.semibold))
            Text(toast.message)
                .font(.subheadline.weight(.medium))
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
        .background(.regularMaterial, in: .capsule)
        .overlay(Capsule().strokeBorder(.separator.opacity(0.5), lineWidth: 0.5))
        .shadow(color: .black.opacity(0.12), radius: 16, y: 6)
        .frame(maxWidth: 520)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
        .onAppear { UIAccessibility.post(notification: .announcement, argument: toast.message) }
    }
}

// MARK: - Layout

/// Wraps children onto new lines, like a paragraph of pills.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6
    var lineSpacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let width = rows.map(\.width).max() ?? 0
        let height = rows.map(\.height).reduce(0, +) + CGFloat(max(0, rows.count - 1)) * lineSpacing
        return CGSize(width: proposal.width.map { min($0, width) } ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for i in row.indices {
                let size = subviews[i].sizeThatFits(.unspecified)
                subviews[i].place(at: CGPoint(x: x, y: y + (row.height - size.height) / 2), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + lineSpacing
        }
    }

    private struct Row { var indices: [Int] = []; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var current = Row()
        for (i, sub) in subviews.enumerated() {
            let size = sub.sizeThatFits(.unspecified)
            let needed = current.indices.isEmpty ? size.width : current.width + spacing + size.width
            if needed > width, !current.indices.isEmpty {
                rows.append(current)
                current = Row(indices: [i], width: size.width, height: size.height)
            } else {
                current.indices.append(i)
                current.width = needed
                current.height = max(current.height, size.height)
            }
        }
        if !current.indices.isEmpty { rows.append(current) }
        return rows
    }
}

extension View {
    /// Keeps list content to a comfortable reading width on iPad and in wide windows.
    func readableContentWidth(_ maxWidth: CGFloat = 720) -> some View {
        modifier(ReadableWidth(maxWidth: maxWidth))
    }
}

private struct ReadableWidth: ViewModifier {
    let maxWidth: CGFloat
    @State private var width: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .contentMargins(.horizontal, max(0, (width - maxWidth) / 2), for: .scrollContent)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
    }
}

// MARK: - Copy

@MainActor
enum Clipboard {
    static func copy(_ value: String) {
        UIPasteboard.general.string = value
        Haptics.tap()
    }
}

// MARK: - Group swatches

/// A group as a capsule with its colour swatch.
struct GroupChip: View {
    let group: ParticipantGroup

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(Color(hexString: group.color) ?? .accentColor)
                .frame(width: 10, height: 10)
            Text(group.name).font(.subheadline.weight(.medium))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Color(uiColor: .tertiarySystemFill), in: .capsule)
        .accessibilityElement(children: .combine)
    }
}
