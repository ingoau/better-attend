import SwiftUI
import WidgetKit

@main
struct AttendWidgetsBundle: WidgetBundle {
    var body: some Widget {
        QuickScanWidget()
    }
}

// Placeholder: replaced by the widgets implementation.
struct QuickScanWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "QuickScan", provider: Provider()) { _ in
            Label("Scan", systemImage: "qrcode.viewfinder")
                .containerBackground(.fill.tertiary, for: .widget)
                .widgetURL(DeepLink.scan)
        }
        .configurationDisplayName("Quick Scan")
        .description("Jump straight into the scanner.")
    }

    struct Entry: TimelineEntry { let date: Date }

    struct Provider: TimelineProvider {
        func placeholder(in context: Context) -> Entry { Entry(date: .now) }
        func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) { completion(Entry(date: .now)) }
        func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
            completion(Timeline(entries: [Entry(date: .now)], policy: .never))
        }
    }
}
