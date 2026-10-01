import SwiftUI
import WidgetKit

@main
struct AttendWidgetsBundle: WidgetBundle {
    var body: some Widget {
        CheckInWidget()
        ArrivalsWidget()
        QuickScanWidget()
        TicketWidget()
        ScanControl()
    }
}

/// Organizer: live check-in progress for the selected event.
struct CheckInWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKind.checkIn, provider: SnapshotProvider(schedule: .organizer)) { entry in
            FamilyReader { CheckInWidgetView(snapshot: entry.snapshot, family: $0, now: entry.date) }
        }
        .configurationDisplayName("Check-in Progress")
        .description("Live check-in count for your event, with who's still to arrive and per-station totals.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

/// Organizer, travel events: who's waiting at the airport and who's next.
struct ArrivalsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKind.arrivals, provider: SnapshotProvider(schedule: .organizer)) { entry in
            FamilyReader { ArrivalsWidgetView(snapshot: entry.snapshot, family: $0, now: entry.date) }
        }
        .configurationDisplayName("Arrivals")
        .description("Airport pickups at a glance: awaiting pickup, collected, checked in and the next arrival.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

/// One tap to the scanner, on the Home Screen or Lock Screen.
struct QuickScanWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKind.quickScan, provider: SnapshotProvider(schedule: .none)) { entry in
            FamilyReader { QuickScanWidgetView(snapshot: entry.snapshot, family: $0) }
        }
        .configurationDisplayName("Quick Scan")
        .description("One tap to open the scanner with the camera ready.")
        .supportedFamilies([.systemSmall, .accessoryCircular])
    }
}

/// Participant: the next event, a countdown and the pass.
struct TicketWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKind.ticket, provider: SnapshotProvider(schedule: .ticket)) { entry in
            FamilyReader { TicketWidgetView(snapshot: entry.snapshot, family: $0, now: entry.date) }
        }
        .configurationDisplayName("My Ticket")
        .description("Your next Hack Club event with a countdown. Tap to show your QR pass.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular, .accessoryInline])
    }
}

/// Control Center / Lock Screen / Action button control that opens the scanner.
struct ScanControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: WidgetKind.scanControl) {
            ControlWidgetButton(action: OpenScannerIntent()) {
                Label("Scan Tickets", systemImage: "qrcode.viewfinder")
            }
        }
        .displayName("Scan Tickets")
        .description("Opens the BetterAttend scanner with the camera ready.")
    }
}

/// Hands the environment's widget family to the shared (family-parameterised) views.
private struct FamilyReader<Content: View>: View {
    @Environment(\.widgetFamily) private var family
    @ViewBuilder var content: (WidgetFamily) -> Content

    var body: some View { content(family) }
}
