import SwiftUI

// Placeholder: replaced by the feature implementation.
struct ParticipantDetailView: View {
    let eventId: String
    let participantEventId: String
    var browseIds: [String] = []
    var body: some View {
        ContentUnavailableView("Participant", systemImage: "hammer", description: Text("Coming soon"))
            .navigationTitle("Participant")
    }
}
