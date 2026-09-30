import SwiftUI

/// The horizontally scrolling chip rows under the search field: status (with live counts) and, when
/// journeys use more than one transport mode, mode.
struct TravelFilterBar: View {
    let all: [TravelEntry]
    let query: String
    @Binding var filter: TravelFilter
    @Binding var mode: TravelMode?

    private var counts: [TravelFilter: Int] { TravelLogic.filterCounts(all, query: query, mode: mode) }
    private var modeCounts: [TravelMode: Int] { TravelLogic.modeCounts(all, query: query, filter: filter) }
    private var showsModes: Bool { TravelLogic.modesPresent(all).count > 1 }

    var body: some View {
        let counts = counts
        let statuses = TravelFilter.allCases.filter { $0 == .all || $0 == filter || (counts[$0] ?? 0) > 0 }
        VStack(spacing: 8) {
            ChipRow(selection: filter) {
                ForEach(statuses) { f in
                    TravelChip(
                        title: f.chipLabel, count: counts[f] ?? 0, systemImage: f.systemImage,
                        selected: filter == f, accessibilityName: f.label
                    ) {
                        filter = (filter == f && f != .all) ? .all : f
                    }
                    .id(f.id)
                }
            }
            if showsModes {
                let modeCounts = modeCounts
                ChipRow(selection: mode?.id ?? "") {
                    ForEach(TravelMode.allCases.filter { modeCounts[$0] != nil || $0 == mode }) { m in
                        TravelChip(
                            title: m.label, count: modeCounts[m] ?? 0,
                            systemImage: TravelMode.systemImage(mode: m.rawValue, direction: nil),
                            selected: mode == m, accessibilityName: "\(m.label) journeys"
                        ) {
                            mode = mode == m ? nil : m
                        }
                        .id(m.id)
                    }
                }
            }
        }
        .padding(.vertical, 8)
        .animation(.snappy, value: statuses)
    }
}

/// A horizontally scrolling row that keeps the selected chip in view.
private struct ChipRow<ID: Hashable, Content: View>: View {
    let selection: ID
    @ViewBuilder let content: Content

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: 8) { content }
                    .padding(.horizontal)
            }
            .scrollIndicators(.hidden)
            .scrollClipDisabled()
            .onChange(of: selection) { _, id in
                withAnimation(.snappy) { proxy.scrollTo(id, anchor: .center) }
            }
        }
    }
}

/// A capsule toggle: "Arrivals 8". Filled with the accent colour when selected.
private struct TravelChip: View {
    let title: String
    let count: Int
    var systemImage: String?
    let selected: Bool
    var accessibilityName: String?
    let action: () -> Void

    var body: some View {
        Button {
            Haptics.selection()
            withAnimation(.snappy) { action() }
        } label: {
            HStack(spacing: 5) {
                if let systemImage {
                    Image(systemName: systemImage).imageScale(.small)
                }
                Text(title)
                Text(count, format: .number)
                    .fontWeight(.bold)
                    .monospacedDigit()
                    .contentTransition(.numericText(value: Double(count)))
                    .foregroundStyle(selected ? AnyShapeStyle(.white.opacity(0.85)) : AnyShapeStyle(.secondary))
            }
            .font(.subheadline.weight(.medium))
            .lineLimit(1)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .foregroundStyle(selected ? AnyShapeStyle(.white) : AnyShapeStyle(.primary))
            .background {
                Capsule().fill(selected ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(Color(uiColor: .tertiarySystemFill)))
            }
            .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("\(accessibilityName ?? title), \(count)"))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
