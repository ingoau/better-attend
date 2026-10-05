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

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
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
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(.rect)
            .gesture(zoom.simultaneously(with: pan))
            .onTapGesture(count: 2) {
                withAnimation(.snappy) {
                    if scale > 1 { reset() } else { scale = 2.5; settledScale = 2.5 }
                }
            }
            .onTapGesture { dismiss() }
            .accessibilityElement()
            .accessibilityLabel("Photo of \(name)")
            .accessibilityAddTraits(.isImage)
        }
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

    private var zoom: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                scale = min(max(settledScale * value.magnification, 1), 5)
            }
            .onEnded { _ in
                settledScale = scale
                if scale == 1 { withAnimation(.snappy) { reset() } }
            }
    }

    /// Zoomed in it pans; at full size a downward swipe closes, like Photos.
    private var pan: some Gesture {
        DragGesture()
            .onChanged { value in
                if scale > 1 {
                    offset = CGSize(width: settledOffset.width + value.translation.width, height: settledOffset.height + value.translation.height)
                } else {
                    offset = CGSize(width: 0, height: max(value.translation.height, 0))
                }
            }
            .onEnded { value in
                if scale > 1 {
                    settledOffset = offset
                } else if value.translation.height > 120 {
                    dismiss()
                } else {
                    withAnimation(.snappy) { offset = .zero }
                }
            }
    }

    private func reset() {
        scale = 1
        settledScale = 1
        offset = .zero
        settledOffset = .zero
    }
}
