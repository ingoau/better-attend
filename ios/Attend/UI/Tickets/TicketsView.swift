import SwiftUI

// Placeholder: replaced by the feature implementation.
struct TicketsView: View {
    /// True when Tickets is the only tab, so it carries the account button.
    var showsAccount = false

    var body: some View {
        ContentUnavailableView("Tickets", systemImage: "hammer", description: Text("Coming soon"))
            .navigationTitle("My Tickets")
            .toolbar { if showsAccount { ToolbarItem(placement: .topBarTrailing) { AccountButton() } } }
    }
}
