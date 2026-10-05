import SwiftUI

/// Someone's photo on black, edge to edge. Pinch or double-tap to zoom and drag to pan; tap, swipe
/// down or ✕ to close.
struct PhotoViewer: View {
    let url: URL
    let name: String
    @Environment(\.dismiss) private var dismiss

    @State private var scale: CGFloat = 1
    @State private var settledScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var settledOffset: CGSize = .zero
    /// True for the whole of a pinch, so its finger movement isn't read as a swipe to close.
    @GestureState private var pinching = false

    var body: some View {
        GeometryReader { geo in
            AsyncImage(url: url) { phase in
                switch phase {
                case let .success(image):
                    image.resizable().scaledToFit()
                case .failure:
                    ContentUnavailableView("Couldn't Load Photo", systemImage: "photo")
                        .foregroundStyle(.white)
                default:
                    ProgressView().tint(.white)
                }
            }
            .scaleEffect(scale)
            .offset(offset)
            .frame(width: geo.size.width, height: geo.size.height)
            .contentShape(.rect)
            .gesture(zoom(in: geo.size).simultaneously(with: pan(in: geo.size)))
            // Explicit, so the first tap of a double tap never closes the viewer.
            .gesture(
                TapGesture(count: 2).onEnded {
                    withAnimation(.snappy) {
                        if scale > 1 { reset() } else { scale = 2.5; settledScale = 2.5 }
                    }
                }
                .exclusively(before: TapGesture().onEnded { dismiss() })
            )
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Photo of \(name)")
            .accessibilityAddTraits(.isImage)
        }
        .background(Color.black.ignoresSafeArea())
        .overlay(alignment: .topLeading) {
            Button { dismiss() } label: {
                Image(systemName: "xmark")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .background(.black.opacity(0.5), in: .circle)
            }
            .padding()
            .accessibilityLabel("Close")
        }
        .overlay(alignment: .bottom) {
            Text(name)
                .font(.headline)
                .foregroundStyle(.white)
                .padding()
                .allowsHitTesting(false)
        }
    }

    private func zoom(in size: CGSize) -> some Gesture {
        MagnifyGesture()
            .updating($pinching) { _, state, _ in state = true }
            .onChanged { value in
                scale = min(max(settledScale * value.magnification, 1), 5)
                offset = clamped(offset, scale: scale, in: size)
            }
            .onEnded { _ in
                settledScale = scale
                settledOffset = offset
                if scale == 1 { withAnimation(.snappy) { reset() } }
            }
    }

    /// Zoomed in it pans (never past the photo's edges); at full size a downward swipe closes, like Photos.
    private func pan(in size: CGSize) -> some Gesture {
        DragGesture()
            .onChanged { value in
                if settledScale > 1 {
                    let moved = CGSize(width: settledOffset.width + value.translation.width, height: settledOffset.height + value.translation.height)
                    offset = clamped(moved, scale: scale, in: size)
                } else if !pinching {
                    offset = CGSize(width: 0, height: max(value.translation.height, 0))
                }
            }
            .onEnded { value in
                if settledScale > 1 {
                    settledOffset = offset
                } else if !pinching && scale == 1 && value.translation.height > 120 {
                    dismiss()
                } else if scale == 1 {
                    withAnimation(.snappy) { offset = .zero }
                }
            }
    }

    /// Keeps the zoomed photo covering the screen: it can move at most the amount it overhangs.
    private func clamped(_ offset: CGSize, scale: CGFloat, in size: CGSize) -> CGSize {
        let maxX = size.width * (scale - 1) / 2
        let maxY = size.height * (scale - 1) / 2
        return CGSize(width: min(max(offset.width, -maxX), maxX), height: min(max(offset.height, -maxY), maxY))
    }

    private func reset() {
        scale = 1
        settledScale = 1
        offset = .zero
        settledOffset = .zero
    }
}
