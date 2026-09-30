import SwiftUI

// Placeholder: replaced by the feature implementation.
struct KioskView: View {
    let config: KioskConfig
    @Environment(Router.self) private var router

    var body: some View {
        NavigationStack {
            ContentUnavailableView("Kiosk", systemImage: "hammer", description: Text("Coming soon"))
                .toolbar { Button("Close") { router.kiosk = nil } }
        }
    }
}
