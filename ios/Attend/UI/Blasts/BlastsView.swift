import SwiftUI

/// Announcements: Slack DMs to every confirmed participant, with live delivery progress.
struct BlastsView: View {
    let eventId: String

    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var scenePhase
    @State private var model: BlastsModel
    @State private var composing = false
    /// Kept outside the sheet so "Keep Draft" survives closing it.
    @State private var draft = ""
    @State private var now = Date()

    init(eventId: String) {
        self.eventId = eventId
        _model = State(initialValue: BlastsModel(eventId: eventId))
    }

    private var event: Event? { app.events.events?.first { $0.id == eventId } }

    /// People the next blast would reach, from the cached roster; nil when we can't tell
    /// (no roster access, or only a partial roster).
    private var recipientEstimate: Int? {
        guard event?.canViewParticipants == true, let roster = app.participants.roster(eventId), roster.syncedAt != nil else { return nil }
        return BlastLogic.estimateRecipients(roster.participants)
    }

    private struct PollKey: Equatable {
        var generation: Int
        var active: Bool
        var foreground: Bool
    }

    var body: some View {
        content
            .navigationTitle("Announcements")
            .blastsSubtitle(event?.name)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button("New Announcement", systemImage: "square.and.pencil") { compose() }
                        .disabled(model.blasts == nil)
                }
            }
            .sheet(isPresented: $composing) {
                BlastComposer(model: model, draft: $draft, recipientEstimate: recipientEstimate)
            }
            .task {
                await app.participants.load(eventId)
                await model.refresh(api: app.api)
            }
            // Live progress for blasts still sending, only while this screen is visible and the app is active.
            .task(id: PollKey(generation: model.pollGeneration, active: model.hasActive, foreground: scenePhase == .active)) {
                guard scenePhase == .active else { return }
                await model.pollWhileActive(api: app.api)
            }
            .task {
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(30))
                    now = Date()
                }
            }
    }

    @ViewBuilder private var content: some View {
        if let blasts = model.blasts {
            list(blasts)
        } else if let error = model.error {
            ContentUnavailableView {
                Label("Couldn't Load Announcements", systemImage: "icloud.slash")
            } description: {
                Text(error)
            } actions: {
                Button("Try Again") {
                    Haptics.tap()
                    Task { await model.refresh(api: app.api) }
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.loading)
            }
        } else {
            ProgressView("Loading announcements…")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func list(_ blasts: [SlackBlast]) -> some View {
        let active = blasts.filter(BlastLogic.isActive)
        let history = blasts.filter { !BlastLogic.isActive($0) }
        return List {
            if let error = model.error {
                Section {
                    NoticeBanner(message: error, tone: .warning) { Task { await model.refresh(api: app.api) } }
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
            }
            if !active.isEmpty {
                Section("Sending") {
                    ForEach(active) { row($0) }
                }
            }
            if !history.isEmpty {
                Section(active.isEmpty ? "Sent" : "Earlier") {
                    ForEach(history) { row($0) }
                }
            }
        }
        .listStyle(.insetGrouped)
        .frame(maxWidth: 760)
        .frame(maxWidth: .infinity)
        .background(Color(.systemGroupedBackground))
        .animation(.smooth, value: blasts)
        .overlay {
            if blasts.isEmpty {
                ContentUnavailableView {
                    Label("No Announcements Yet", systemImage: "megaphone")
                } description: {
                    Text("Reach everyone at once with a Slack DM, perfect for “lunch is ready” or “buses leave at 9”.")
                } actions: {
                    Button("Write One") { compose() }
                        .buttonStyle(.borderedProminent)
                }
            }
        }
        .refreshable { await model.refresh(api: app.api) }
    }

    private func row(_ blast: SlackBlast) -> some View {
        BlastRow(blast: blast, now: now)
            .contextMenu {
                let text = BlastLogic.toPlain(blast.message)
                Button("Copy Text", systemImage: "doc.on.doc") { UIPasteboard.general.string = text }
                Button("Use as Draft", systemImage: "square.and.pencil") { reuse(text) }
            } preview: {
                BlastRow(blast: blast, now: now, expanded: true)
                    .padding()
                    .frame(width: 340)
                    .background(Color(.secondarySystemGroupedBackground))
            }
            .swipeActions(edge: .leading) {
                Button("Reuse", systemImage: "arrow.uturn.backward") { reuse(BlastLogic.toPlain(blast.message)) }
                    .tint(.accentColor)
            }
    }

    private func compose() {
        Haptics.tap()
        model.sendError = nil
        composing = true
    }

    private func reuse(_ text: String) {
        draft = text
        compose()
    }
}

/// One announcement: status, text (tap to expand), delivery progress and who sent it.
private struct BlastRow: View {
    let blast: SlackBlast
    let now: Date
    @State var expanded = false

    var body: some View {
        let active = BlastLogic.isActive(blast)
        let failed = blast.status == "failed" || blast.failedCount > 0
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                statusPill
                Spacer()
                Text(Time.ago(blast.createdAt, now: now) ?? "")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(BlastLogic.toPlain(blast.message))
                .font(.body)
                .lineLimit(expanded ? nil : 4)
                .frame(maxWidth: .infinity, alignment: .leading)
            if active {
                ProgressView(value: BlastLogic.fraction(blast))
                    .tint(Tone.info.color)
                    .animation(.smooth(duration: 0.8), value: BlastLogic.fraction(blast))
                    .accessibilityHidden(true)
            }
            HStack(spacing: 6) {
                Image(systemName: "person.2.fill").imageScale(.small)
                Text(BlastLogic.progressText(blast))
                    .contentTransition(.numericText())
                    .animation(.snappy, value: blast.sentCount)
                    .foregroundStyle(failed ? Tone.danger.color : .secondary)
                Spacer(minLength: 8)
                if let by = blast.sentBy?.nonBlank {
                    Text(by).lineLimit(1)
                }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, 6)
        .contentShape(.rect)
        .onTapGesture {
            Haptics.selection()
            withAnimation(.smooth) { expanded.toggle() }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: expanded ? "Collapse" : "Expand") { expanded.toggle() }
    }

    @ViewBuilder private var statusPill: some View {
        switch blast.status {
        case "pending": Pill(text: "Queued", tone: .info, systemImage: "clock")
        case "in_progress": Pill(text: "Sending", tone: .info, systemImage: "paperplane")
        case "completed": Pill(text: "Sent", tone: .success, systemImage: "checkmark.circle.fill")
        case "failed": Pill(text: "Failed", tone: .danger, systemImage: "exclamationmark.circle.fill")
        default: Pill(text: blast.status.replacingOccurrences(of: "_", with: " ").capitalizedFirst)
        }
    }
}

extension View {
    /// The event name under the title on iOS 26; nothing on earlier systems.
    @ViewBuilder
    fileprivate func blastsSubtitle(_ subtitle: String?) -> some View {
        if #available(iOS 26.0, *), let subtitle {
            navigationSubtitle(subtitle)
        } else {
            self
        }
    }
}
