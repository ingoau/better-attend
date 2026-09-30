import SwiftUI

extension View {
    /// Liquid Glass on iOS 26, a thin material before that. For controls floating over the camera.
    @ViewBuilder
    func floatingGlass<S: Shape>(in shape: S, interactive: Bool = false, tint: Color? = nil) -> some View {
        if #available(iOS 26.0, *) {
            let glass: Glass = tint.map { Glass.regular.tint($0) } ?? .regular
            self.glassEffect(interactive ? glass.interactive() : glass, in: shape)
        } else {
            self.background {
                ZStack {
                    shape.fill(.ultraThinMaterial)
                    if let tint { shape.fill(tint.opacity(0.85)) }
                }
            }
        }
    }

    /// `navigationSubtitle` where available (iOS 26); nothing on older systems.
    @ViewBuilder
    func scanSubtitle(_ text: String?) -> some View {
        if #available(iOS 26.0, *), let text {
            self.navigationSubtitle(text)
        } else {
            self
        }
    }

    /// Keeps the screen awake while this view is visible (restored when it disappears).
    func keepsScreenAwake(_ on: Bool) -> some View {
        modifier(IdleTimerModifier(on: on))
    }
}

private struct IdleTimerModifier: ViewModifier {
    let on: Bool
    @State private var visible = false

    func body(content: Content) -> some View {
        content
            .onAppear {
                visible = true
                UIApplication.shared.isIdleTimerDisabled = on
            }
            .onDisappear {
                visible = false
                UIApplication.shared.isIdleTimerDisabled = false
            }
            .onChange(of: on) { _, on in
                if visible { UIApplication.shared.isIdleTimerDisabled = on }
            }
    }
}

/// A round icon button floating over the camera (torch, history, NFC…).
struct CameraIconButton: View {
    let title: String
    let systemImage: String
    var isOn = false
    var isEnabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.title3.weight(.semibold))
                .symbolVariant(isOn ? .fill : .none)
                .foregroundStyle(isOn ? Color.black : Color.white)
                .contentTransition(.symbolEffect(.replace))
                .frame(width: 52, height: 52)
                .floatingGlass(in: Circle(), interactive: true, tint: isOn ? .white : nil)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .opacity(isEnabled ? 1 : 0.45)
        .accessibilityLabel(title)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}

/// A small status capsule over the camera: a coloured dot and a label.
struct CameraChip: View {
    let text: String
    var dot: Color?
    var systemImage: String?

    var body: some View {
        HStack(spacing: 7) {
            if let dot {
                Circle().fill(dot).frame(width: 8, height: 8)
            }
            if let systemImage { Image(systemName: systemImage).imageScale(.small) }
            Text(text).lineLimit(1)
                .contentTransition(.opacity)
        }
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(.white)
        .padding(.horizontal, 12)
        .frame(minHeight: 34)
        .floatingGlass(in: Capsule())
        .environment(\.colorScheme, .dark)
    }
}
