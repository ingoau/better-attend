import SwiftUI

// Placeholder: replaced by the feature implementation.
struct SettingsView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Button("Sign Out", role: .destructive) {
                    Task { await app.auth.signOut() }
                }
            }
            .navigationTitle("Settings")
            .toolbar { Button("Done") { dismiss() } }
        }
    }
}
