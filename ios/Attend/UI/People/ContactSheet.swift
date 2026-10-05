import SwiftUI

/// Every way to reach someone, from the Contact button under their name: what each one opens
/// (the number, address or Slack ID) is shown before you tap it.
struct ContactSheet: View {
    let name: String
    let options: [ContactOption]
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(options) { option in
                        Button { open(option) } label: { row(option) }
                            .accessibilityLabel("\(option.title), \(option.detail)")
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Contact \(name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func row(_ option: ContactOption) -> some View {
        HStack(spacing: 14) {
            Image(systemName: option.systemImage)
                .font(.body.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: 36, height: 36)
                .background(.tint, in: .circle)
            VStack(alignment: .leading, spacing: 2) {
                Text(option.title).foregroundStyle(.primary)
                Text(option.detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
        .contentShape(.rect)
    }

    private func open(_ option: ContactOption) {
        Haptics.tap()
        // Captured: the fallback may run after the sheet has gone.
        let launch = openURL
        launch(option.url) { accepted in
            if !accepted, let fallback = option.fallback { launch(fallback) }
        }
        dismiss()
    }
}
