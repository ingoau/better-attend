import SwiftUI

// Building blocks for Home's cards. Feature-local: other screens use List/Form.

/// Home's card surface: the grouped "cell" colour with continuous corners.
struct DashCard<Content: View>: View {
    var title: String?
    var systemImage: String?
    /// Trailing header link, e.g. "Travel ›".
    var actionTitle: String?
    var action: (() -> Void)?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let title {
                HStack(alignment: .firstTextBaseline) {
                    Group {
                        if let systemImage {
                            Label(title, systemImage: systemImage)
                        } else {
                            Text(title)
                        }
                    }
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
                    Spacer(minLength: 8)
                    if let actionTitle, let action {
                        Button {
                            Haptics.tap()
                            action()
                        } label: {
                            HStack(spacing: 3) {
                                Text(actionTitle)
                                Image(systemName: "chevron.forward").imageScale(.small).fontWeight(.semibold)
                            }
                            .font(.subheadline)
                        }
                        .buttonStyle(.borderless)
                    }
                }
            }
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .dashCardBackground()
    }
}

extension View {
    func dashCardBackground(_ fill: some ShapeStyle = Color(.secondarySystemGroupedBackground)) -> some View {
        background(fill, in: .rect(cornerRadius: 22, style: .continuous))
    }
}

/// A whole-card tap target that still looks like a card (no blue text, gentle press state).
struct DashCardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .contentShape(.rect(cornerRadius: 22, style: .continuous))
            .opacity(configuration.isPressed ? 0.6 : 1)
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .animation(.snappy(duration: 0.2), value: configuration.isPressed)
    }
}

/// An integer that rolls to its new value.
struct DashNumber: View {
    let value: Int
    var prefix = ""
    var suffix = ""

    var body: some View {
        Text("\(prefix)\(value.formatted())\(suffix)")
            .monospacedDigit()
            .contentTransition(.numericText(value: Double(value)))
            .animation(.snappy, value: value)
    }
}

/// "Live", "Upcoming" or "Ended".
struct DashPhasePill: View {
    let phase: Time.Phase

    var body: some View {
        switch phase {
        case .live: Pill(text: "Live", tone: .success, systemImage: "dot.radiowaves.left.and.right")
        case .upcoming: Pill(text: "Upcoming", tone: .info, systemImage: "calendar")
        case .past: Pill(text: "Ended", tone: .neutral, systemImage: "checkmark")
        case .unknown: EmptyView()
        }
    }
}

enum DashboardIcons {
    static func context(_ c: ScanContext) -> String {
        context(name: c.name, checksIn: c.checksIn, travel: c.isTravelPickup || c.isAirport)
    }

    static func context(name: String, checksIn: Bool, travel: Bool) -> String {
        let n = name.lowercased()
        if checksIn { return "person.crop.circle.badge.checkmark" }
        if travel { return "airplane.arrival" }
        if ["lunch", "dinner", "breakfast", "meal", "snack", "food"].contains(where: n.contains) { return "fork.knife" }
        if ["bus", "shuttle"].contains(where: n.contains) { return "bus" }
        return "checkmark.seal"
    }

    static func travelMode(_ mode: String?, direction: String?) -> String {
        switch mode {
        case "plane":
            switch direction {
            case "inbound": "airplane.arrival"
            case "outbound": "airplane.departure"
            default: "airplane"
            }
        case "train": "tram"
        case "bus": "bus"
        case "car": "car"
        default: "figure.walk"
        }
    }
}
