import SwiftUI
import UIKit

/// The detail page's list: header, safety alerts, check-in, then everything we know about the person.
struct DetailList: View {
    let participant: Participant
    let model: ParticipantDetailModel
    @Binding var confirm: DetailConfirmation?
    @Binding var addingNote: Bool
    @Binding var headerHidden: Bool
    @Environment(AppModel.self) private var app

    var body: some View {
        let p = participant
        let event = model.event(app)
        let tz = event?.timezone
        let pii = event?.canViewParticipantPii == true
        let sensitive = event?.canViewSensitiveData == true
        let alerts = Safety.alerts(p, canViewSensitive: sensitive)
        let can = ParticipantActionVisibility(event)
        let registrationFooter = "Registration: \(PeopleText.status(p.status))."
            + (can.remove ? " Removing deletes the registration for good; withdrawing can be undone." : "")

        List {
            DetailHeader(participant: p, timezone: tz, canViewPii: pii, model: model)

            if let error = model.error, !model.detailLoaded {
                Section {
                    NoticeBanner(message: "Showing saved details. \(error)", retry: { Task { await model.refresh(app) } })
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
            }

            if !alerts.isEmpty {
                Section {
                    ForEach(alerts, id: \.title) { SafetyAlertRow(alert: $0) }
                }
            }

            CheckInSection(participant: p, model: model, confirm: $confirm)

            if !model.detailLoaded && model.loading {
                Section {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Loading full profile…").foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
                }
            }

            if let t = p.travelInbound { ParticipantTravelSection(travel: t, timezone: tz, canViewPii: pii) }
            if let t = p.travelOutbound { ParticipantTravelSection(travel: t, timezone: tz, canViewPii: pii) }

            if !p.groups.isEmpty {
                Section("Groups") {
                    FlowLayout(spacing: 8, lineSpacing: 8) {
                        ForEach(p.groups) { GroupChip(group: $0) }
                    }
                    .padding(.vertical, 4)
                }
            }

            ContactSection(participant: p, canViewPii: pii, model: model)
            PersonalSection(participant: p, model: model)
            AccommodationSection(participant: p, model: model)
            if sensitive {
                MedicalSection(participant: p, model: model)
                AccessibilitySection(accessibility: p.accessibility, model: model)
            }
            SafeguardingSection(participant: p, canViewSensitive: sensitive, model: model)
            GuardiansSection(participant: p, model: model)
            ConsentsSection(consents: p.consents ?? [], timezone: tz)
            BadgeSection(participant: p, model: model, confirm: $confirm)
            if !model.notesHidden {
                NotesSection(model: model, addingNote: $addingNote)
            }
            if can.showsRegistrationSection {
                Section {
                    if can.withdraw {
                        if p.status == "withdrawn" {
                            Button { confirm = .reinstate } label: {
                                BusyLabel("Reinstate Registration", systemImage: "person.fill.checkmark", busy: model.busy == .updatingStatus)
                            }
                        } else {
                            Button(role: .destructive) { confirm = .withdraw } label: {
                                BusyLabel("Withdraw from Event", systemImage: "person.fill.xmark", busy: model.busy == .updatingStatus)
                            }
                            .foregroundStyle(Tone.danger.color)
                        }
                    }
                    if can.remove {
                        Button(role: .destructive) { confirm = .remove(eventName: event?.name ?? "this event") } label: {
                            BusyLabel("Remove from Event", systemImage: "trash", busy: model.busy == .removing)
                        }
                        .foregroundStyle(Tone.danger.color)
                    }
                } footer: {
                    Text(registrationFooter)
                }
                .disabled(model.busy != nil)
            }
        }
        .listStyle(.insetGrouped)
        .listSectionSpacing(.compact)
        .readableContentWidth()
        .refreshable { await model.refresh(app) }
        .onScrollGeometryChange(for: Bool.self) { geo in
            geo.contentOffset.y + geo.contentInsets.top > 170
        } action: { _, hidden in
            headerHidden = hidden
        }
        .animation(.smooth, value: p)
        .animation(.smooth, value: model.notes)
    }
}

// MARK: - Header

private struct DetailHeader: View {
    let participant: Participant
    let timezone: String?
    let canViewPii: Bool
    let model: ParticipantDetailModel

    var body: some View {
        let p = participant
        let sub = [p.fullName?.nonBlank.flatMap { $0 != p.name ? $0 : nil }, p.pronouns?.nonBlank, p.personal?.age.map { "Age \($0)" }]
            .compactMap { $0 }.joined(separator: " · ")
        Section {
            VStack(spacing: 12) {
                Avatar(name: p.fullName ?? p.name, url: p.headshotUrl, size: 96)
                    .overlay(alignment: .bottomTrailing) {
                        if p.isCheckedIn {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.title)
                                .symbolRenderingMode(.palette)
                                .foregroundStyle(.white, Tone.success.color)
                                .background(Circle().fill(Color(uiColor: .systemGroupedBackground)).padding(-3))
                                .transition(.scale.combined(with: .opacity))
                                .accessibilityHidden(true)
                        }
                    }
                VStack(spacing: 3) {
                    Text(p.name)
                        .font(.title.weight(.bold))
                        .multilineTextAlignment(.center)
                    if !sub.isEmpty {
                        Text(sub)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                }
                FlowLayout(spacing: 6, lineSpacing: 6) {
                    StatusPill(participant: p)
                    if p.status != "complete" && p.isCheckedIn { Pill(text: PeopleText.status(p.status), tone: .warning) }
                    if p.nfcBadgeAssigned { Pill(text: "Badge", tone: .info, systemImage: "wave.3.right") }
                    Pill(text: p.shortCode, tone: .neutral, systemImage: "ticket")
                        .fontDesign(.monospaced)
                        .contextMenu {
                            Button("Copy Ticket Code", systemImage: "doc.on.doc") {
                                Clipboard.copy(p.shortCode)
                                model.toast = .info("Copied \(p.shortCode)", systemImage: "doc.on.doc.fill")
                            }
                        }
                        .accessibilityLabel("Ticket code \(p.shortCode.map(String.init).joined(separator: " "))")
                }
                ContactTiles(participant: p, canViewPii: canViewPii)
                    .padding(.top, 4)
            }
            .frame(maxWidth: .infinity)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
        }
    }
}

/// Contacts-style action buttons: call, message, WhatsApp, FaceTime, mail, Slack.
private struct ContactTiles: View {
    let participant: Participant
    let canViewPii: Bool
    @Environment(\.openURL) private var openURL

    private static let hasWhatsApp = URL(string: "whatsapp://").map { UIApplication.shared.canOpenURL($0) } ?? false

    var body: some View {
        let phone = canViewPii ? participant.phone?.nonBlank : nil
        // Whatever email the server sends is already on screen, so mailing it needs no extra permission.
        let email = participant.email?.nonBlank
        let slack = participant.slackUserId?.nonBlank
        if phone != nil || email != nil || slack != nil {
            HStack(spacing: 8) {
                if canViewPii {
                    tile("Call", "phone.fill", phone.flatMap(ContactLinks.call))
                    tile("Message", "message.fill", phone.flatMap(ContactLinks.sms))
                    if Self.hasWhatsApp { tile("WhatsApp", "bubble.left.and.bubble.right.fill", phone.flatMap(ContactLinks.whatsApp)) }
                    tile("FaceTime", "video.fill", phone.flatMap(ContactLinks.faceTime))
                }
                tile("Mail", "envelope.fill", email.flatMap(ContactLinks.email))
                tile("Slack", "number", slack.flatMap(ContactLinks.slack), fallback: slack.flatMap(ContactLinks.slackWeb))
            }
        }
    }

    /// [fallback] opens if nothing handles [url] (Slack not installed → its web profile).
    private func tile(_ title: String, _ symbol: String, _ url: URL?, fallback: URL? = nil) -> some View {
        Button {
            Haptics.tap()
            if let url {
                openURL(url) { accepted in
                    if !accepted, let fallback { openURL(fallback) }
                }
            }
        } label: {
            VStack(spacing: 5) {
                Image(systemName: symbol).font(.title3)
                Text(title).font(.caption2.weight(.medium)).lineLimit(1).minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, minHeight: 58)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: .rect(cornerRadius: 14))
            .contentShape(.rect(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .foregroundStyle(url == nil ? AnyShapeStyle(.tertiary) : AnyShapeStyle(.tint))
        .disabled(url == nil)
    }
}

// MARK: - Safety

private struct SafetyAlertRow: View {
    let alert: SafetyAlert

    var body: some View {
        let tone: Tone = alert.danger ? .danger : .warning
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: alert.systemImage)
                .font(.body.weight(.semibold))
                .foregroundStyle(tone.onColor)
                .frame(width: 34, height: 34)
                .background(tone.color, in: .circle)
            VStack(alignment: .leading, spacing: 2) {
                Text(alert.title).font(.headline)
                if let detail = alert.detail {
                    Text(detail).font(.subheadline)
                }
            }
            .foregroundStyle(tone.onContainer)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 2)
        .listRowBackground(tone.container)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Check-in

private struct CheckInSection: View {
    let participant: Participant
    let model: ParticipantDetailModel
    @Binding var confirm: DetailConfirmation?
    @Environment(AppModel.self) private var app

    var body: some View {
        let p = participant
        let tz = model.event(app)?.timezone
        let contexts = model.contexts(app)
        let points = ParticipantDetailLogic.scanPoints(p, contexts: contexts)
        let scanned = points.compactMap(\.scan)
        let checkIns = contexts.filter(\.checksIn)
        let defaultContext = EventLogic.defaultContext(checkIns.isEmpty ? contexts : checkIns)

        Section {
            if p.isCheckedIn {
                let scan = PeopleFilter.checkInScan(p)
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Checked in \(Time.time(p.checkedInAt, tz: tz) ?? "")").font(.headline)
                        Text([Time.day(p.checkedInAt, tz: tz), scan?.scanContextName].compactMap { $0 }.joined(separator: " · "))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.title2)
                        .foregroundStyle(Tone.success.color)
                }
            } else if p.isActive {
                Button {
                    Task { await model.checkIn(app, at: defaultContext) }
                } label: {
                    HStack(spacing: 8) {
                        if model.busy == .checkingIn(defaultContext?.id) {
                            ProgressView().tint(Tone.success.onColor)
                        } else {
                            Image(systemName: "checkmark.circle.fill")
                        }
                        Text(defaultContext.map { checkIns.count > 1 ? "Check In · \($0.name)" : "Check In" } ?? "Check In")
                            .lineLimit(1)
                    }
                    .font(.headline)
                    .foregroundStyle(Tone.success.onColor)
                    .frame(maxWidth: .infinity, minHeight: 34)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.roundedRectangle(radius: 14))
                .controlSize(.large)
                .tint(Tone.success.color)
                .disabled(model.busy != nil)
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            } else {
                Label("Not checked in. \(PeopleText.status(p.status)) registrations can't be checked in.", systemImage: "nosign")
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("Check-In")
        }

        if !points.isEmpty {
            Section {
                ForEach(points) { pt in
                    ScanPointRow(point: pt, timezone: tz, participant: p, model: model, confirm: $confirm,
                                 context: contexts.first { $0.id == pt.id })
                }
                if scanned.count > 1 {
                    Button(role: .destructive) {
                        confirm = .undoAll(scans: scanned.reduce(0) { $0 + max(1, $1.scanCount) }, places: scanned.count)
                    } label: {
                        BusyLabel("Undo All Scans…", systemImage: "arrow.uturn.backward.circle", busy: model.busy == .undoing(nil))
                    }
                    .foregroundStyle(Tone.danger.color)
                    .disabled(model.busy != nil)
                }
            } header: {
                Text("Scan Points")
            } footer: {
                if scanned.isEmpty { Text("Not scanned anywhere yet.") }
            }
        }
    }
}

private struct ScanPointRow: View {
    let point: ParticipantDetailLogic.ScanPoint
    let timezone: String?
    let participant: Participant
    let model: ParticipantDetailModel
    @Binding var confirm: DetailConfirmation?
    let context: ScanContext?
    @Environment(AppModel.self) private var app

    var body: some View {
        let scan = point.scan
        HStack(spacing: 12) {
            Image(systemName: scan != nil ? "checkmark.circle.fill" : "circle")
                .font(.title3)
                .foregroundStyle(scan != nil ? AnyShapeStyle(Tone.success.color) : AnyShapeStyle(.tertiary))
                .contentTransition(.symbolEffect(.replace))
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    if point.isTravelPickup {
                        Image(systemName: "airplane.arrival").foregroundStyle(.secondary).imageScale(.small)
                    }
                    Text(point.name).lineLimit(2)
                    if point.checksIn { Pill(text: "Check-in", tone: .success) }
                }
                Text(subtitle(scan))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            if model.busy == .checkingIn(point.id) || model.busy == .undoing(point.id) {
                ProgressView()
            } else if scan != nil {
                Button("Undo") { undo() }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .tint(.orange)
                    .disabled(model.busy != nil)
            } else if participant.isActive, let context {
                Button(point.checksIn ? "Check In" : "Scan") {
                    Task { await model.checkIn(app, at: context) }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(model.busy != nil)
            }
        }
        .swipeActions {
            if scan != nil {
                Button("Undo", systemImage: "arrow.uturn.backward") { undo() }.tint(.orange)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func undo() {
        confirm = .undo(contextId: point.id, place: point.name, scans: max(1, point.scan?.scanCount ?? 1))
    }

    private func subtitle(_ s: ContextScanSummary?) -> String {
        guard let s else { return "Not scanned" }
        let first = Time.dayTime(s.firstScannedAt, tz: timezone) ?? "Scanned"
        guard s.scanCount > 1 else { return first }
        return "\(first) · \(s.scanCount) scans, last \(Time.time(s.lastScannedAt, tz: timezone) ?? "–")"
    }
}

/// A button label that swaps its icon for a spinner while busy.
struct BusyLabel: View {
    let title: String
    let systemImage: String
    let busy: Bool

    init(_ title: String, systemImage: String, busy: Bool) {
        self.title = title
        self.systemImage = systemImage
        self.busy = busy
    }

    var body: some View {
        Label {
            Text(title)
        } icon: {
            if busy { ProgressView() } else { Image(systemName: systemImage) }
        }
    }
}

// MARK: - Travel

private struct ParticipantTravelSection: View {
    let travel: Travel
    let timezone: String?
    let canViewPii: Bool

    var body: some View {
        let t = travel
        let inbound = t.direction != "outbound"
        let from = t.departureCity ?? t.trainDepartureStation ?? t.departureStation ?? t.busDepartureLocation
        let to = t.arrivalCity ?? t.trainArrivalStation ?? t.arrivalStation ?? t.busArrivalLocation
        let pickedUp = t.legs.last?.travelPickedUpAt
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Label([PeopleText.travelMode(t.mode), t.carrier, t.flightNumber].compactMap { $0?.nonBlank }.joined(separator: " · "),
                      systemImage: PeopleText.travelModeSymbol(t.mode))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)
                if from != nil || to != nil {
                    Text("\(from ?? "?") → \(to ?? "?")").font(.title3.weight(.semibold))
                }
                FlowLayout(spacing: 6) {
                    if inbound && t.mode == "plane" {
                        if let pickedUp {
                            Pill(text: "Picked up \(Time.time(pickedUp, tz: timezone) ?? "")", tone: .success, systemImage: "checkmark")
                        } else if t.pickupDismissedAt != nil {
                            Pill(text: "Pickup not needed", tone: .neutral)
                        } else {
                            Pill(text: "Awaiting pickup", tone: .warning, systemImage: "clock")
                        }
                    }
                    if t.isUnaccompaniedMinor { Pill(text: "Unaccompanied minor", tone: .warning, systemImage: "figure.child") }
                }
            }
            .padding(.vertical, 2)
            ForEach(Array(t.legs.sorted { $0.position < $1.position }.enumerated()), id: \.offset) { _, leg in
                LegRow(leg: leg, timezone: timezone)
            }
            if t.legs.isEmpty {
                FieldRow("Departs", Time.dayTime(t.departureTime, tz: timezone))
                FieldRow("Arrives", Time.dayTime(t.arrivalTime, tz: timezone))
            }
            FieldRow("Expected arrival", Time.dayTime(t.expectedArrivalTime, tz: timezone))
            if canViewPii { FieldRow("Leaving from", t.originAddress) }
            FieldRow("Details", t.otherDetails)
            FieldRow("Notes", t.notes)
            if t.visaRequired == true || t.visaStatus != nil || t.passportNationality != nil {
                FieldRow("Passport", t.passportNationality)
                FieldRow("Visa", [PeopleText.humanize(t.visaStatus), t.visaType, t.visaNumber].compactMap { $0?.nonBlank }.joined(separator: " · ").nonBlank)
            }
        } header: {
            Text(inbound ? "Arriving" : "Departing")
        }
    }
}

private struct LegRow: View {
    let leg: TravelLeg
    let timezone: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(leg.flightCode ?? "Flight").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                Spacer()
                if let status = leg.liveStatus?.nonBlank { Pill(text: status, tone: Self.tone(status)) }
            }
            Text("\(leg.departureAirport ?? "?") → \(leg.arrivalAirport ?? "?")")
                .font(.title2.weight(.bold))
                .fontDesign(.rounded)
            let dep = Time.dayTime(leg.liveDepartureTime ?? leg.departureTime, tz: timezone)
            let arr = Time.dayTime(leg.liveArrivalTime ?? leg.arrivalTime, tz: timezone)
            if let dep { Text("Departs \(dep)").font(.subheadline).foregroundStyle(.secondary) }
            if let arr { Text("Arrives \(arr)").font(.subheadline).foregroundStyle(.secondary) }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    static func tone(_ status: String) -> Tone {
        switch status.lowercased() {
        case "arrived", "landed": .success
        case "departed", "enroute", "en route", "active": .info
        case "delayed": .warning
        case "cancelled", "canceled": .danger
        default: .neutral
        }
    }
}

// MARK: - Fields

/// Label above value, Contacts style. Renders nothing for blank values; long-press copies.
struct FieldRow: View {
    let label: String
    let value: String?
    var highlight: Tone?

    init(_ label: String, _ value: String?, highlight: Tone? = nil) {
        self.label = label
        self.value = value
        self.highlight = highlight
    }

    var body: some View {
        if let value = value?.nonBlank {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.subheadline).foregroundStyle(.secondary)
                Text(value)
                    .font(highlight != nil ? .body.weight(.semibold) : .body)
                    .foregroundStyle(highlight.map { $0 == .neutral ? AnyShapeStyle(.primary) : AnyShapeStyle($0.color) } ?? AnyShapeStyle(.primary))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(.rect)
            .contextMenu {
                Button("Copy \(label)", systemImage: "doc.on.doc") { Clipboard.copy(value) }
            }
            .accessibilityElement(children: .combine)
        }
    }
}

/// A tappable value (phone, email) in the accent colour, with copy and extra actions on long-press.
private struct LinkRow<Extra: View>: View {
    let label: String
    let value: String
    let url: URL?
    /// Opens if nothing handles `url` (Slack not installed → its web profile).
    var fallback: URL? = nil
    @ViewBuilder var extra: Extra
    @Environment(\.openURL) private var openURL

    var body: some View {
        Button {
            if let url {
                openURL(url) { accepted in
                    if !accepted, let fallback { openURL(fallback) }
                }
            }
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.subheadline).foregroundStyle(.secondary)
                Text(value).foregroundStyle(url == nil ? AnyShapeStyle(.primary) : AnyShapeStyle(.tint))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(url == nil)
        .contextMenu {
            Button("Copy", systemImage: "doc.on.doc") { Clipboard.copy(value) }
            extra
        }
    }
}

extension LinkRow where Extra == EmptyView {
    init(label: String, value: String, url: URL?, fallback: URL? = nil) {
        self.init(label: label, value: value, url: url, fallback: fallback) { EmptyView() }
    }
}

/// Phone row with call on tap and message / FaceTime / WhatsApp on long-press.
private struct PhoneRow: View {
    let label: String
    let phone: String
    @Environment(\.openURL) private var openURL

    var body: some View {
        LinkRow(label: label, value: phone, url: ContactLinks.call(phone)) {
            if let url = ContactLinks.sms(phone) { Button("Message", systemImage: "message") { openURL(url) } }
            if let url = ContactLinks.faceTime(phone) { Button("FaceTime", systemImage: "video") { openURL(url) } }
            if let url = ContactLinks.faceTimeAudio(phone) { Button("FaceTime Audio", systemImage: "phone.bubble") { openURL(url) } }
            if let url = ContactLinks.whatsApp(phone) { Button("WhatsApp", systemImage: "bubble.left.and.bubble.right") { openURL(url) } }
        }
    }
}

/// A row of yes / no chips ("Waiver signed ✓", "Photos ✗").
private struct CheckChips: View {
    let items: [(String, Bool?)]

    var body: some View {
        let shown = items.compactMap { label, value in value.map { (label, $0) } }
        if !shown.isEmpty {
            FlowLayout(spacing: 6, lineSpacing: 6) {
                ForEach(shown, id: \.0) { label, value in
                    Pill(text: label, tone: value ? .success : .neutral, systemImage: value ? "checkmark" : "xmark")
                        .accessibilityLabel("\(label): \(value ? "yes" : "no")")
                }
            }
            .padding(.vertical, 4)
        }
    }
}

// MARK: - Sections

private struct ContactSection: View {
    let participant: Participant
    let canViewPii: Bool
    let model: ParticipantDetailModel

    var body: some View {
        let p = participant
        let phone = canViewPii ? p.phone?.nonBlank : nil
        let email = p.email?.nonBlank
        let slack = p.slackUserId?.nonBlank
        if phone != nil || email != nil || slack != nil {
            Section("Contact") {
                if let phone { PhoneRow(label: "Mobile", phone: phone) }
                if let email { LinkRow(label: "Email", value: email, url: ContactLinks.email(email)) }
                if let slack { LinkRow(label: "Slack", value: slack, url: ContactLinks.slack(slack), fallback: ContactLinks.slackWeb(slack)) }
            }
        }
    }
}

private struct PersonalSection: View {
    let participant: Participant
    let model: ParticipantDetailModel

    var body: some View {
        if let personal = participant.personal {
            let legal = [personal.legalFirstName, personal.legalLastName].compactMap { $0?.nonBlank }.joined(separator: " ").nonBlank
            let dob = personal.dateOfBirth.flatMap(PeopleText.date).map { d in d + (personal.age.map { " (age \($0))" } ?? "") }
            let address = personal.address.flatMap { a in
                [a.line1, a.line2, [a.city, a.state, a.postalCode].compactMap { $0?.nonBlank }.joined(separator: " ").nonBlank, a.country]
                    .compactMap { $0?.nonBlank }.joined(separator: "\n").nonBlank
            }
            let fields: [(String, String?)] = [
                ("Legal name", legal),
                ("Preferred name", personal.preferredName.flatMap { $0 != personal.legalFirstName ? $0 : nil }),
                ("Date of birth", dob),
                ("Age", dob == nil ? personal.age.map(String.init) : nil),
                ("T-shirt size", personal.tshirtSize ?? participant.tshirtSize),
                ("Engagement", PeopleText.humanize(personal.engagementPreference)),
                ("Engagement notes", personal.engagementNotes),
                ("Address", address),
            ]
            if fields.contains(where: { $0.1?.nonBlank != nil }) {
                Section("Personal") {
                    ForEach(fields, id: \.0) { FieldRow($0.0, $0.1) }
                }
            }
        }
    }
}

private struct AccommodationSection: View {
    let participant: Participant
    let model: ParticipantDetailModel

    var body: some View {
        if let a = participant.accommodation {
            let stay = [PeopleText.date(a.checkInDate), PeopleText.date(a.checkOutDate)].compactMap { $0 }.joined(separator: " – ").nonBlank
            let fields: [(String, String?, Tone?)] = [
                ("Room", a.assignedRoom, .brand),
                ("Venue", a.venueName, nil),
                ("Stay", stay, nil),
                ("Rooming", a.roomingExempt == true ? "Exempt" : nil, nil),
                ("Gender identity", a.genderIdentityOther ?? PeopleText.humanize(a.genderIdentity), nil),
                ("Roommate genders", a.preferredRoommateGenders?.compactMap(PeopleText.humanize).joined(separator: ", ").nonBlank, nil),
                ("Roommate preferences", a.roommatePreferences, nil),
                ("Keep apart from", a.roommateExclusions, .danger),
                ("Room type", PeopleText.humanize(a.roomTypePreference), nil),
                ("Quiet room", a.quietRoomPreference == true ? "Preferred" : nil, nil),
                ("Accessibility", a.accessibilityNeeds, nil),
                ("Notes", a.notes, nil),
            ]
            if fields.contains(where: { $0.1?.nonBlank != nil }) {
                Section("Accommodation") {
                    ForEach(fields, id: \.0) { FieldRow($0.0, $0.1, highlight: $0.2) }
                }
            }
        }
    }
}

private struct MedicalSection: View {
    let participant: Participant
    let model: ParticipantDetailModel

    var body: some View {
        let p = participant
        let m = p.medicalDetail
        let d = p.dietaryDetail
        let fields: [(String, String?, Tone?)] = [
            ("Allergies", p.allergies, .danger),
            ("Allergy severity", PeopleText.humanize(m?.allergySeverity), nil),
            ("Life-threatening allergies", p.lifeThreateningAllergies, .danger),
            ("Medical conditions", p.medicalConditions, nil),
            ("Medications", p.medications, nil),
            ("Refrigeration", p.requiresRefrigeration ? "Medication must be kept cold" : nil, .warning),
            ("Emergency action plan", m?.emergencyActionPlan, .danger),
            ("Medical notes", m?.additionalNotes, nil),
            ("Diet", PeopleText.humanize(p.dietType), nil),
            ("Cross-contamination", p.crossContaminationRisk ? "Risk: prepare separately" : nil, .warning),
            ("Intolerances", d?.intolerances, nil),
            ("Dietary notes", d?.notes, nil),
        ]
        if fields.contains(where: { $0.1?.nonBlank != nil }) {
            Section("Medical & Dietary") {
                ForEach(fields, id: \.0) { FieldRow($0.0, $0.1, highlight: $0.2) }
            }
        }
    }
}

private struct AccessibilitySection: View {
    let accessibility: Accessibility?
    let model: ParticipantDetailModel

    var body: some View {
        if let a = accessibility {
            let flags: [String] = [
                a.usesWheelchair == true ? "Wheelchair user" : nil, a.stepFreeRequired == true ? "Step-free access" : nil,
                a.needsCaptioning == true ? "Captioning" : nil, a.needsLargePrint == true ? "Large print" : nil,
                a.needsSignLanguage == true ? "Sign language" : nil, a.lightSensitivity == true ? "Light sensitive" : nil,
                a.noiseSensitivity == true ? "Noise sensitive" : nil, a.strobeSensitivity == true ? "Strobe sensitive" : nil,
                a.hasAdhd == true ? "ADHD" : nil, a.hasAutism == true ? "Autism" : nil, a.hasDyslexia == true ? "Dyslexia" : nil,
                a.prayerSpaceRequired == true ? "Prayer space" : nil, a.requiresPrivateSpace == true ? "Private space" : nil,
            ].compactMap { $0 }
            let texts: [(String, String?)] = [
                ("Mobility", a.mobilityNeeds), ("Sensory", a.sensoryNeeds), ("Communication", a.communicationNeeds),
                ("Neurodivergence", a.neurodivergentNotes), ("Religious practice", a.religiousPractices),
                ("Distance limits", a.distanceLimitations), ("Unavailable times", a.unavailableTimes), ("Other", a.otherNeeds),
            ]
            if !flags.isEmpty || texts.contains(where: { $0.1?.nonBlank != nil }) {
                Section("Accessibility") {
                    if !flags.isEmpty {
                        FlowLayout(spacing: 6, lineSpacing: 6) {
                            ForEach(flags, id: \.self) { Pill(text: $0, tone: .info) }
                        }
                        .padding(.vertical, 4)
                    }
                    ForEach(texts, id: \.0) { FieldRow($0.0, $0.1) }
                }
            }
        }
    }
}

private struct SafeguardingSection: View {
    let participant: Participant
    let canViewSensitive: Bool
    let model: ParticipantDetailModel

    var body: some View {
        let p = participant
        let s = p.safeguardingDetail
        Section("Safeguarding") {
            CheckChips(items: [
                ("Waiver signed", p.waiverSigned),
                ("Freedom waiver", canViewSensitive ? p.freedomWaiverGranted : nil),
                ("Can leave alone", p.canLeaveUnaccompanied),
                ("High support", p.highSupportFlag ? true : nil),
            ])
            FieldRow("High support notes", s?.highSupportNotes)
            FieldRow("Authorised for pickup", s?.authorizedPickupAdults, highlight: .brand)
            FieldRow("Other instructions", s?.otherInstructions)
        }
    }
}

private struct GuardiansSection: View {
    let participant: Participant
    let model: ParticipantDetailModel

    var body: some View {
        let p = participant
        let guardians = (p.guardians ?? []).sorted { $0.isPrimary && !$1.isPrimary }
        let fallback = p.emergencyContacts ?? []
        if !guardians.isEmpty || !fallback.isEmpty || p.parentGuardianName != nil {
            if guardians.isEmpty {
                Section("Emergency Contacts") {
                    if let name = p.parentGuardianName?.nonBlank {
                        Text(name).font(.headline)
                        if let phone = p.parentGuardianPhone?.nonBlank { PhoneRow(label: "Parent / guardian phone", phone: phone) }
                        if let email = p.parentGuardianEmail?.nonBlank { LinkRow(label: "Parent / guardian email", value: email, url: ContactLinks.email(email)) }
                    }
                    ForEach(Array(fallback.enumerated()), id: \.offset) { _, c in EmergencyContactRow(contact: c) }
                }
            } else {
                ForEach(Array(guardians.enumerated()), id: \.offset) { i, g in
                    Section(i == 0 ? (guardians.count > 1 ? "Guardians" : "Guardian") : "") {
                        GuardianRows(guardian: g)
                    }
                }
            }
        }
    }
}

private struct GuardianRows: View {
    let guardian: Guardian

    var body: some View {
        let g = guardian
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(g.name ?? "Guardian").font(.headline)
                if g.isPrimary { Pill(text: "Primary", tone: .brand) }
                if let st = g.status {
                    let done = st == "completed"
                    Pill(text: done ? "Complete" : PeopleText.humanize(st) ?? st, tone: done ? .success : .warning, systemImage: done ? "checkmark" : "clock")
                }
            }
            if let rel = g.relationship?.nonBlank { Text(rel).font(.subheadline).foregroundStyle(.secondary) }
            CheckChips(items: [("Media", g.mediaPermission), ("Photos", g.photoPermission), ("Travel", g.travelPermission),
                               ("Emergency medical", g.emergencyMedicalConsent), ("OTC meds", g.otcMedicationConsent)])
        }
        .padding(.vertical, 2)
        if let phone = g.phone?.nonBlank { PhoneRow(label: "Phone", phone: phone) }
        if let email = g.email?.nonBlank { LinkRow(label: "Email", value: email, url: ContactLinks.email(email)) }
        ForEach(Array(g.emergencyContacts.filter { $0.name != g.name || $0.phone != g.phone }.enumerated()), id: \.offset) { _, c in
            EmergencyContactRow(contact: c)
        }
    }
}

private struct EmergencyContactRow: View {
    let contact: EmergencyContact

    var body: some View {
        let label = [contact.name ?? "Contact", "Emergency contact", contact.relationship].compactMap { $0?.nonBlank }.joined(separator: " · ")
        if let phone = contact.phone?.nonBlank {
            PhoneRow(label: label, phone: phone)
        } else {
            FieldRow(label, contact.email ?? contact.name)
        }
    }
}

private struct ConsentsSection: View {
    let consents: [Consent]
    let timezone: String?
    @Environment(\.openURL) private var openURL

    var body: some View {
        if !consents.isEmpty {
            Section("Consents") {
                ForEach(Array(consents.enumerated()), id: \.offset) { _, c in row(c) }
            }
        }
    }

    private func row(_ c: Consent) -> some View {
        let tone: Tone = switch c.status {
        case "signed": .success
        case "sent", "viewed": .info
        case "failed", "voided": .danger
        default: .neutral
        }
        let sub: String? = if let signed = c.signedAt { "Signed \(Time.day(signed, tz: timezone) ?? "")" }
            else if let pending = c.pendingOn { "Waiting on \(PeopleText.humanize(pending)?.lowercased() ?? pending)" }
            else if let sent = c.sentAt { "Sent \(Time.day(sent, tz: timezone) ?? "")" }
            else { nil }
        return HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(PeopleText.consent(c.consentType))
                    Pill(text: PeopleText.humanize(c.status) ?? "Unknown", tone: tone, systemImage: c.status == "signed" ? "checkmark" : nil)
                }
                if let sub { Text(sub).font(.subheadline).foregroundStyle(.secondary) }
                if let failure = c.failureReason?.nonBlank { Text(failure).font(.subheadline).foregroundStyle(Tone.danger.color) }
            }
            Spacer(minLength: 0)
            if let url = c.documentUrl.flatMap(URL.init(string:)) {
                Button("Open \(PeopleText.consent(c.consentType))", systemImage: "arrow.up.forward.square") { openURL(url) }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                    .font(.title3)
            }
        }
        .padding(.vertical, 2)
    }
}

// MARK: - NFC badge

private struct BadgeSection: View {
    let participant: Participant
    let model: ParticipantDetailModel
    @Binding var confirm: DetailConfirmation?
    @Environment(AppModel.self) private var app

    var body: some View {
        let p = participant
        let canWrite = BadgeWriter.isAvailable
        Section {
            HStack(spacing: 12) {
                Image(systemName: p.nfcBadgeAssigned ? "wave.3.right.circle.fill" : "wave.3.right.circle")
                    .font(.title2)
                    .foregroundStyle(p.nfcBadgeAssigned ? AnyShapeStyle(Tone.info.color) : AnyShapeStyle(.secondary))
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.nfcBadgeAssigned ? "Badge assigned" : "No badge yet")
                    Text(p.nfcBadgeAssigned ? "Taps in at any Attend scanner." : "Write a blank NFC badge to let them tap in.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .accessibilityElement(children: .combine)
            if canWrite {
                Button {
                    Task { await model.writeBadge(app) }
                } label: {
                    HStack {
                        Label(p.nfcBadgeAssigned ? "Write a New Badge" : "Write Badge", systemImage: "square.and.pencil")
                        Spacer()
                        if model.badge.isWorking { ProgressView() }
                    }
                }
                .disabled(model.badge.isWorking || model.busy != nil)
            }
            if p.nfcBadgeAssigned {
                Button(role: .destructive) { confirm = .resetBadge } label: {
                    BusyLabel("Reset Badge…", systemImage: "arrow.counterclockwise", busy: model.busy == .resettingBadge)
                }
                .foregroundStyle(Tone.danger.color)
                .disabled(model.busy != nil)
            }
        } header: {
            Text("NFC Badge")
        } footer: {
            if !canWrite {
                Text("This device can't write NFC badges. Use an iPhone to write one.")
            } else if p.slackUserId?.nonBlank != nil {
                Text("Any phone that taps the badge will open \(p.name)'s Hack Club badge page.")
            } else {
                Text("\(p.name) has no Slack account linked, so the badge will only work with Attend scanners.")
            }
        }
    }
}
