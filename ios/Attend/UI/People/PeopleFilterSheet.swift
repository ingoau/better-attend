import SwiftUI

/// Sort and advanced filters. Changes apply live; the button at the bottom shows how many match.
struct PeopleFilterSheet: View {
    let event: Event
    let people: [Participant]
    let contexts: [ScanContext]
    @Bindable var model: PeopleModel
    @Environment(\.dismiss) private var dismiss

    private var statuses: [String] {
        Array(Set(people.compactMap(\.status))).sorted {
            (PeopleFilter.statusOrder.firstIndex(of: $0) ?? 99, $0) < (PeopleFilter.statusOrder.firstIndex(of: $1) ?? 99, $1)
        }
    }

    private var diets: [String] { Array(Set(people.compactMap(\.dietType))).sorted() }

    private var resultCount: Int {
        PeopleFilter.apply(people, query: model.query, quick: model.quick, options: model.options, sort: model.sort,
                           canViewSensitive: event.canViewSensitiveData).participants.count
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Sort By") {
                    Picker("Sort by", selection: tick($model.sort)) {
                        ForEach(PeopleSort.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }

                if !contexts.isEmpty {
                    Section {
                        Picker(selection: tick($model.options.scannedAtContextId)) {
                            Text("Anywhere").tag(String?.none)
                            ForEach(contexts) { c in
                                Label(c.name, systemImage: c.isAirport || c.isTravelPickup ? "airplane.arrival" : c.checksIn ? "person.badge.shield.checkmark" : "qrcode")
                                    .tag(Optional(c.id))
                            }
                        } label: {
                            Label("Scan point", systemImage: "qrcode.viewfinder")
                        }
                        if model.options.scannedAtContextId != nil {
                            Picker("Scanned", selection: tick($model.options.notScannedAtContext)) {
                                Text("Scanned there").tag(false)
                                Text("Not scanned there").tag(true)
                            }
                            .pickerStyle(.segmented)
                            .labelsHidden()
                        }
                    } header: {
                        Text("Scanned At")
                    }
                }

                if !statuses.isEmpty {
                    Section {
                        ForEach(statuses, id: \.self) { s in
                            CheckRow(title: PeopleText.status(s), selected: model.options.statuses.contains(s)) {
                                toggle(s, in: \.statuses)
                            }
                        }
                    } header: {
                        Text("Registration Status")
                    } footer: {
                        Text("Leave all unticked to include every status.")
                    }
                }

                Section("Travel") {
                    TriPicker(title: "Inbound", systemImage: "airplane.arrival", yes: "Has travel", no: "None",
                              selection: tick($model.options.inboundTravel))
                    TriPicker(title: "Outbound", systemImage: "airplane.departure", yes: "Has travel", no: "None",
                              selection: tick($model.options.outboundTravel))
                }

                Section("Travel Mode") {
                    ForEach(PeopleText.travelModes, id: \.self) { m in
                        CheckRow(title: PeopleText.travelModeOption(m), systemImage: PeopleText.travelModeSymbol(m),
                                 selected: model.options.travelModes.contains(m)) {
                            toggle(m, in: \.travelModes)
                        }
                    }
                }

                Section("Waiver & Badge") {
                    TriPicker(title: "Waiver", systemImage: "signature", yes: "Signed", no: "Not signed",
                              selection: tick($model.options.waiverSigned))
                    TriPicker(title: "NFC badge", systemImage: "wave.3.right", yes: "Assigned", no: "No badge",
                              selection: tick($model.options.nfcAssigned))
                }

                if event.canViewSensitiveData && !diets.isEmpty {
                    Section("Diet") {
                        ForEach(diets, id: \.self) { d in
                            CheckRow(title: PeopleText.humanize(d) ?? d, selected: model.options.dietTypes.contains(d)) {
                                toggle(d, in: \.dietTypes)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Filter & Sort")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Clear All") {
                        Haptics.tap()
                        withAnimation { model.options = FilterOptions() }
                    }
                    .disabled(model.options.activeCount == 0)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    dismiss()
                } label: {
                    Text(resultCount == 1 ? "Show 1 Person" : "Show \(resultCount) People")
                        .contentTransition(.numericText(value: Double(resultCount)))
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                .controlSize(.large)
                .padding(.horizontal, 20)
                .padding(.vertical, 10)
                .animation(.smooth, value: resultCount)
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    /// Wraps a binding so every change gets a selection tick.
    private func tick<T: Equatable>(_ binding: Binding<T>) -> Binding<T> {
        Binding(get: { binding.wrappedValue }, set: { new in
            if new != binding.wrappedValue { Haptics.selection() }
            withAnimation(.smooth) { binding.wrappedValue = new }
        })
    }

    private func toggle(_ value: String, in keyPath: WritableKeyPath<FilterOptions, Set<String>>) {
        Haptics.selection()
        withAnimation(.smooth) {
            if model.options[keyPath: keyPath].contains(value) {
                model.options[keyPath: keyPath].remove(value)
            } else {
                model.options[keyPath: keyPath].insert(value)
            }
        }
    }
}

/// A Settings-style multi-select row with a trailing checkmark.
private struct CheckRow: View {
    let title: String
    var systemImage: String?
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                if let systemImage {
                    Label(title, systemImage: systemImage)
                } else {
                    Text(title)
                }
                Spacer()
                Image(systemName: "checkmark")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.tint)
                    .opacity(selected ? 1 : 0)
            }
            .contentShape(.rect)
        }
        .tint(.primary)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// "Any / Yes / No" as a menu picker row.
private struct TriPicker: View {
    let title: String
    let systemImage: String
    let yes: String
    let no: String
    @Binding var selection: TriState

    var body: some View {
        Picker(selection: $selection) {
            Text("Any").tag(TriState.any)
            Text(yes).tag(TriState.yes)
            Text(no).tag(TriState.no)
        } label: {
            Label(title, systemImage: systemImage)
        }
    }
}
