import SwiftUI

/// The scanner's interrupt: one red banner per scan the server turned down after the card said
/// "Confirming…". They stay until dismissed; tapping one opens that person.
struct ScanAlertBanners: View {
    let alerts: [ScanRejection]
    let timezone: String?
    var onOpen: ((ScanRejection) -> Void)?
    var onDismiss: (ScanRejection) -> Void

    private static let visible = 3

    var body: some View {
        if !alerts.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(alerts.prefix(Self.visible)) { a in
                    banner(a)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
                if alerts.count > Self.visible {
                    Text("+\(alerts.count - Self.visible) more rejected")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Tone.danger.color)
                        .padding(.leading, 14)
                }
            }
        }
    }

    private func banner(_ a: ScanRejection) -> some View {
        let open = a.participantEventId == nil ? nil : onOpen
        return HStack(spacing: 12) {
            Image(systemName: "exclamationmark.octagon.fill").font(.title3)
            VStack(alignment: .leading, spacing: 1) {
                Text(a.headline)
                    .font(.headline)
                    .lineLimit(2)
                Text(["Rejected by Attend", a.contextName, Time.time(a.scannedAt, tz: timezone)].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption)
                    .lineLimit(1)
                    .opacity(0.9)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if open != nil { Image(systemName: "chevron.right").font(.subheadline.weight(.semibold)) }
            Button {
                withAnimation(.smooth) { onDismiss(a) }
            } label: {
                Image(systemName: "xmark")
                    .font(.subheadline.weight(.bold))
                    .frame(width: 44, height: 44)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Dismiss alert for \(a.name)")
        }
        .foregroundStyle(Tone.danger.onColor)
        .padding(.leading, 14)
        .padding(.vertical, 6)
        .background(Tone.danger.color, in: .rect(cornerRadius: 18, style: .continuous))
        .contentShape(.rect)
        .onTapGesture { open?(a) }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(open != nil ? .isButton : [])
    }
}

/// Home's banner for queued scans the server turned down when they synced. Each row names the person
/// and the reason and opens them (where `canOpen` allows: rows can belong to other events); it stays
/// until dismissed.
struct OfflineRejectionsCard: View {
    let rejections: [ScanRejection]
    let timezone: String?
    var onOpen: ((ScanRejection) -> Void)?
    var canOpen: (ScanRejection) -> Bool = { _ in true }
    var onDismissAll: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "icloud.slash.fill")
                    .font(.title3)
                    .foregroundStyle(Tone.danger.color)
                VStack(alignment: .leading, spacing: 2) {
                    Text(ScanRejectionText.offlineTitle(count: rejections.count))
                        .font(.headline)
                    Text("Scanned while offline, then turned down by Attend.")
                        .font(.caption)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button("Dismiss", action: onDismissAll)
                    .font(.subheadline.weight(.semibold))
                    .buttonStyle(.borderless)
                    .tint(Tone.danger.onContainer)
            }
            .padding(14)
            ForEach(rejections) { r in
                Divider().overlay(Tone.danger.onContainer.opacity(0.15)).padding(.horizontal, 14)
                row(r)
            }
        }
        .foregroundStyle(Tone.danger.onContainer)
        .background(Tone.danger.container, in: .rect(cornerRadius: 24, style: .continuous))
    }

    private func row(_ r: ScanRejection) -> some View {
        let open = r.participantEventId == nil || !canOpen(r) ? nil : onOpen
        let detail = [r.reason.capitalizedFirst, r.contextName, Time.dayTime(r.scannedAt, tz: timezone),
                      r.stillRecorded ? "still recorded on Attend, undo it from their page" : nil].compactMap { $0 }
        return HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(r.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text(detail.joined(separator: " · ")).font(.caption).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if open != nil { Image(systemName: "chevron.right").font(.caption.weight(.semibold)) }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .contentShape(.rect)
        .onTapGesture { open?(r) }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(open != nil ? .isButton : [])
    }
}

/// Shown when the cache's encryption key is unavailable: nothing is saved on the device, so the app is online only.
struct StorageUnavailableBanner: View {
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "lock.trianglebadge.exclamationmark")
                .font(.title3)
            VStack(alignment: .leading, spacing: 2) {
                Text("Secure Storage Unavailable").font(.subheadline.weight(.semibold))
                Text("This iPhone's encryption key couldn't be used, so nothing is saved to it. BetterAttend works online only: offline scans are kept until the app closes.")
                    .font(.caption)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .foregroundStyle(Tone.warning.onContainer)
        .padding(14)
        .background(Tone.warning.container, in: .rect(cornerRadius: 18, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}
