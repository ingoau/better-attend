import SwiftUI

/// One journey: time (event zone), mode badge, name, route and reference, then direction, pickup and groups.
struct TravelRow: View {
    let entry: TravelEntry
    let tz: String?

    @ScaledMetric(relativeTo: .body) private var timeWidth: CGFloat = 58
    @ScaledMetric(relativeTo: .body) private var badgeSize: CGFloat = 38

    private var inbound: Bool { TravelLogic.isArrival(entry) }
    private var time: String? { Time.time(entry.primaryTimeAt, tz: tz) }
    private var routeLine: String {
        [entry.route?.nonBlank, entry.reference?.nonBlank].compactMap { $0 }.joined(separator: " · ")
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            timeColumn
            modeBadge
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(entry.name)
                        .font(.body.weight(.semibold))
                        .lineLimit(1)
                    if entry.isUnaccompaniedMinor {
                        Pill(text: "UM", tone: .warning, systemImage: "exclamationmark.triangle.fill")
                            .fixedSize()
                    }
                }
                if !routeLine.isEmpty {
                    Text(routeLine)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                badges.padding(.top, 2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(TravelLogic.accessibilityLabel(entry, time: time))
    }

    private var timeColumn: some View {
        let (clock, meridiem) = TravelLogic.splitTime(time)
        return VStack(spacing: 0) {
            Text(clock ?? "–:––")
                .font(.body.weight(.semibold))
                .monospacedDigit()
                .foregroundStyle(time == nil ? .tertiary : .primary)
            if let meridiem {
                Text(meridiem)
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.secondary)
            } else if time == nil {
                Text("TBC")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.tertiary)
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(0.8)
        .frame(width: timeWidth)
    }

    private var modeBadge: some View {
        let tone: Tone = inbound ? .success : .info
        return Image(systemName: TravelMode.systemImage(mode: entry.mode, direction: entry.direction))
            .font(.system(size: badgeSize * 0.45, weight: .semibold))
            .foregroundStyle(tone.onContainer)
            .frame(width: badgeSize, height: badgeSize)
            .background(tone.container, in: .circle)
    }

    private var badges: some View {
        // Wraps onto a second line at large Dynamic Type sizes rather than truncating.
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 6) { badgeContent }
            VStack(alignment: .leading, spacing: 4) { badgeContent }
        }
    }

    @ViewBuilder private var badgeContent: some View {
        Label(inbound ? "Arrives" : "Departs", systemImage: inbound ? "arrow.down.right" : "arrow.up.right")
            .font(.caption.weight(.semibold))
            .foregroundStyle(inbound ? Tone.success.color : Tone.info.color)
            .labelStyle(CompactLabelStyle())
        if let pickup = TravelLogic.pickup(entry.pickupState) {
            Pill(text: pickup.label, tone: pickup.tone, systemImage: pickup.systemImage)
        }
        if !entry.groups.isEmpty {
            HStack(spacing: 6) {
                ForEach(entry.groups.prefix(2)) { g in GroupDot(group: g) }
                if entry.groups.count > 2 {
                    Text("+\(entry.groups.count - 2)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}

/// A colour dot and the group's name ("● Team Blue").
private struct GroupDot: View {
    let group: ParticipantGroup

    var body: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(Color(hexString: group.color) ?? .secondary)
                .frame(width: 8, height: 8)
            Text(group.name)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }
}

private struct CompactLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 2) {
            configuration.icon.imageScale(.small)
            configuration.title
        }
    }
}
