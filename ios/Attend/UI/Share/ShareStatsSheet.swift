import Photos
import SwiftUI

/// What a long-press on a Home number asks to share.
struct ShareRequest: Identifiable {
    var id: String { startId }
    let startId: String
}

/// The share screen a long-press on a Home number opens: a live preview of the card, the numbers on it
/// (starting with `startId`) and a few ways to style it, then the system share sheet with the card as a PNG.
struct ShareStatsSheet: View {
    let event: Event
    let available: [ShareStat]
    @Binding var options: ShareCardOptions
    let now: Date

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var appScheme
    @State private var selected: [String]
    @State private var size: CGSize = .zero
    @State private var cardHeight: CGFloat = 0
    @State private var saving = false
    @State private var saved = false
    @State private var saveError: String?

    init(event: Event, available: [ShareStat], startId: String, options: Binding<ShareCardOptions>, now: Date) {
        self.event = event
        self.available = available
        _options = options
        self.now = now
        _selected = State(initialValue: [startId])
    }

    /// A number can drop off Home (e.g. the event ends) while the sheet is open; never show an empty card.
    private var onCard: [ShareStat] {
        let byId = Dictionary(available.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let picked = selected.compactMap { byId[$0] }
        return picked.isEmpty ? Array(available.prefix(1)) : picked
    }

    private var dark: Bool { options.dark ?? (appScheme == .dark) }
    private var cardWidth: CGFloat { max(min(size.width - 32, 520), 280) }

    private var image: ShareCardImage {
        ShareCardImage(event: event, stats: onCard, options: options, dark: dark, now: now, width: cardWidth)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                preview
                ScrollView { editor }
                    .scrollBounceBehavior(.basedOnSize)
            }
            .onGeometryChange(for: CGSize.self) { $0.size } action: { size = $0 }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Share Stats")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom) { actions }
            .alert("Couldn't Save Image", isPresented: Binding { saveError != nil } set: { if !$0 { saveError = nil } }) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(saveError ?? "")
            }
        }
        .presentationDetents([.large])
        .presentationBackground(Color(.systemGroupedBackground))
    }

    // MARK: Preview

    /// The card at the width it's exported at, scaled down when it would crowd out the options below it.
    private var preview: some View {
        let room = max(size.height * 0.45, 160)
        let scale = cardHeight > room ? room / cardHeight : 1
        return ShareCardView(event: event, stats: onCard, options: options, dark: dark, now: now)
            .frame(width: cardWidth)
            .fixedSize(horizontal: false, vertical: true)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { cardHeight = $0 }
            .scaleEffect(scale, anchor: .top)
            .frame(height: cardHeight * scale, alignment: .top)
            .frame(maxWidth: .infinity)
            .padding(.top, 8)
            .padding(.bottom, 12)
            .animation(.smooth, value: options)
            .animation(.smooth, value: selected)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Preview: \(event.name). " + onCard.map { "\($0.label) \($0.display)" }.joined(separator: ". "))
    }

    // MARK: Options

    private var editor: some View {
        VStack(alignment: .leading, spacing: 0) {
            section("Numbers on the Card", hint: "Up to \(ShareStats.maxOnCard)")
            FlowLayout(spacing: 8, lineSpacing: 8) {
                ForEach(available) { stat in chip(stat) }
            }
            .padding(.horizontal, 16)

            section("Colour")
            HStack(spacing: 12) {
                ForEach(ShareCardColour.allCases) { swatch($0) }
            }
            .padding(.horizontal, 16)

            section("Style")
            Picker("Style", selection: $options.style) {
                ForEach(ShareCardStyle.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)

            section("Layout")
            Picker("Layout", selection: $options.layout) {
                ForEach(ShareCardLayout.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)

            VStack(spacing: 0) {
                toggle("Dark Card", isOn: Binding { dark } set: { options.dark = $0 })
                Divider().padding(.leading, 16)
                toggle("Icons", isOn: $options.showIcons)
                Divider().padding(.leading, 16)
                toggle("Dates, Place and Time", isOn: $options.showDetails)
                Divider().padding(.leading, 16)
                toggle("Totals and Progress", isOn: $options.showTotals)
            }
            .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 22, style: .continuous))
            .padding(.horizontal, 16)
            .padding(.top, 24)
            .padding(.bottom, 16)
        }
        .sensoryFeedback(.selection, trigger: options) { _, _ in Haptics.enabled }
    }

    private func section(_ title: String, hint: String? = nil) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            if let hint {
                Text(hint).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 20)
        .padding(.bottom, 8)
    }

    private func chip(_ stat: ShareStat) -> some View {
        let isOn = selected.contains(stat.id)
        let full = selected.count >= ShareStats.maxOnCard
        return Button {
            Haptics.selection()
            selected = ShareStats.toggle(selected, stat.id)
        } label: {
            Label("\(stat.label) · \(stat.display)", systemImage: isOn ? "checkmark" : stat.icon)
                .font(.subheadline.weight(isOn ? .semibold : .regular))
                .lineLimit(1)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .foregroundStyle(isOn ? Color.accentColor : .primary)
                .background(isOn ? AnyShapeStyle(Color.accentColor.opacity(0.15)) : AnyShapeStyle(Color(.secondarySystemGroupedBackground)), in: .capsule)
                .overlay {
                    if !isOn { Capsule().strokeBorder(.separator, lineWidth: 0.5) }
                }
        }
        .buttonStyle(.plain)
        .disabled(!isOn && full)
        .opacity(!isOn && full ? 0.4 : 1)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }

    /// A colour seed shown in its own scheme's primary; the chosen one becomes a cookie with a tick.
    private func swatch(_ colour: ShareCardColour) -> some View {
        let scheme = ShareScheme(colour, dark: appScheme == .dark)
        let isOn = options.colour == colour
        return Button {
            options.colour = colour
        } label: {
            ZStack {
                if isOn {
                    CookieShape(lobes: 9, depth: 0.1).fill(scheme.primary)
                    Image(systemName: "checkmark").font(.body.weight(.bold)).foregroundStyle(scheme.onPrimary)
                } else {
                    Circle().fill(scheme.primary)
                    Circle().strokeBorder(.separator, lineWidth: 0.5)
                }
            }
            .frame(width: 44, height: 44)
            .contentShape(.circle)
        }
        .buttonStyle(.plain)
        .animation(.snappy, value: isOn)
        .accessibilityLabel(colour.label)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }

    private func toggle(_ title: String, isOn: Binding<Bool>) -> some View {
        Toggle(title, isOn: isOn)
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
    }

    // MARK: Actions

    private var actions: some View {
        HStack(spacing: 10) {
            Button(action: save) {
                Label(saved ? "Saved" : "Save Image", systemImage: saved ? "checkmark" : "square.and.arrow.down")
                    .contentTransition(.symbolEffect(.replace))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(saving)
            ShareLink(item: image, preview: SharePreview("\(event.name) stats", image: image)) {
                Label("Share Image", systemImage: "square.and.arrow.up")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
        }
        .controlSize(.large)
        .fontWeight(.semibold)
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 6)
        .background(.bar)
    }

    private func save() {
        guard !saving else { return }
        Haptics.tap()
        saving = true
        let image = self.image
        Task {
            defer { saving = false }
            do {
                guard let data = image.render() else { throw CocoaError(.fileWriteUnknown) }
                guard await PHPhotoLibrary.requestAuthorization(for: .addOnly) == .authorized else {
                    Haptics.reject()
                    saveError = "Turn on photo access for this app in Settings to save images."
                    return
                }
                try await Self.addToPhotos(data, fileName: ShareStats.fileName(image.event, now: image.now))
                Haptics.confirm()
                UIAccessibility.post(notification: .announcement, argument: "Image saved to Photos")
                withAnimation { saved = true }
                try? await Task.sleep(for: .seconds(2))
                withAnimation { saved = false }
            } catch {
                Haptics.reject()
                saveError = error.friendlyMessage
            }
        }
    }

    /// Photos runs the change block on its own queue, so it mustn't be main-actor isolated.
    nonisolated private static func addToPhotos(_ data: Data, fileName: String) async throws {
        try await PHPhotoLibrary.shared().performChanges {
            let options = PHAssetResourceCreationOptions()
            options.originalFilename = fileName
            PHAssetCreationRequest.forAsset().addResource(with: .photo, data: data, options: options)
        }
    }
}

/// The card as a PNG, rendered only when it's actually shared or saved.
struct ShareCardImage: Transferable {
    let event: Event
    let stats: [ShareStat]
    let options: ShareCardOptions
    let dark: Bool
    let now: Date
    let width: CGFloat

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .png) { item in
            try await MainActor.run {
                guard let data = item.render() else { throw CocoaError(.fileWriteUnknown) }
                return data
            }
        }
        .suggestedFileName { ShareStats.fileName($0.event, now: $0.now) }
    }

    @MainActor
    func render() -> Data? {
        let renderer = ImageRenderer(content: ShareCardView(event: event, stats: stats, options: options, dark: dark, now: now).frame(width: width))
        renderer.scale = 3
        return renderer.uiImage?.pngData()
    }
}
