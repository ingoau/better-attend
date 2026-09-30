import SwiftUI

/// "Where am I scanning?": the checkpoint (scan context) above the camera. A menu for several, a
/// static row for one, a loading row or an error with retry, nothing for none.
struct ContextPicker: View {
    let contexts: [ScanContext]?
    let selectedId: String?
    let timezone: String?
    let error: String?
    let onSelect: (String) -> Void
    let onRetry: () -> Void

    private var selected: ScanContext? { contexts?.first { $0.id == selectedId } }

    var body: some View {
        Group {
            if contexts == nil, error == nil {
                row {
                    ProgressView().frame(width: 36, height: 36)
                    Text("Loading checkpoints…").foregroundStyle(.secondary)
                    Spacer(minLength: 0)
                }
            } else if contexts == nil, let error {
                row {
                    icon("exclamationmark.triangle.fill", tone: .warning)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Couldn't load checkpoints").font(.subheadline.weight(.semibold))
                        Text(error).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    }
                    Spacer(minLength: 0)
                    Button("Retry") { Haptics.tap(); onRetry() }
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                        .controlSize(.small)
                }
            } else if let contexts, contexts.count == 1, let only = contexts.first {
                row { label(for: only, showsChevron: false) }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("Scanning at \(only.name)")
            } else if let contexts, contexts.count > 1 {
                Menu {
                    Picker("Checkpoint", selection: Binding(get: { selectedId ?? "" }, set: { id in
                        Haptics.selection()
                        onSelect(id)
                    })) {
                        ForEach(contexts) { c in
                            Label {
                                Text(c.name)
                                if let sub = subtitle(c) { Text(sub) }
                            } icon: {
                                Image(systemName: c.systemImage)
                            }
                            .tag(c.id)
                        }
                    }
                    .pickerStyle(.inline)
                } label: {
                    row {
                        if let selected {
                            label(for: selected, showsChevron: true)
                        } else {
                            icon("mappin.and.ellipse", tone: .neutral)
                            Text("Choose a checkpoint").font(.body.weight(.semibold))
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.up.chevron.down").foregroundStyle(.secondary)
                        }
                    }
                    .contentShape(.rect(cornerRadius: 18))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Checkpoint: \(selected?.name ?? "none")")
                .accessibilityHint("Choose where you're scanning")
            }
        }
        .animation(.smooth, value: contexts)
    }

    private func subtitle(_ c: ScanContext) -> String? {
        let parts = [c.purpose, c.isLive() ? "Happening now" : c.windowLabel(tz: timezone)].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    @ViewBuilder
    private func label(for c: ScanContext, showsChevron: Bool) -> some View {
        icon(c.systemImage, tone: .brand)
        VStack(alignment: .leading, spacing: 1) {
            Text("Scanning at")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(c.name)
                .font(.body.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
        }
        Spacer(minLength: 4)
        if c.isLive() {
            Pill(text: "Now", tone: .success, systemImage: "dot.radiowaves.left.and.right")
        } else if let window = c.windowLabel(tz: timezone) {
            Text(window)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        if showsChevron {
            Image(systemName: "chevron.up.chevron.down")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)
        }
    }

    private func icon(_ name: String, tone: Tone) -> some View {
        Image(systemName: name)
            .font(.body.weight(.semibold))
            .foregroundStyle(tone == .neutral ? Color.secondary : tone.color)
            .frame(width: 36, height: 36)
            .background((tone == .brand ? Tone.brand : tone).container, in: .circle)
    }

    private func row<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        HStack(spacing: 12, content: content)
            .padding(.leading, 8)
            .padding(.trailing, 14)
            .padding(.vertical, 8)
            .frame(minHeight: 56)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: .rect(cornerRadius: 18, style: .continuous))
    }
}
