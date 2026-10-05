import SwiftUI

/// Top-level tabs. Which ones show depends on the user's roles and the selected event.
enum AppTab: String, Hashable, CaseIterable, Identifiable {
    case home, scan, people, travel, tickets

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: "Home"
        case .scan: "Scan"
        case .people: "People"
        case .travel: "Travel"
        case .tickets: "Tickets"
        }
    }

    var systemImage: String {
        switch self {
        case .home: "house"
        case .scan: "qrcode.viewfinder"
        case .people: "person.2"
        case .travel: "airplane.arrival"
        case .tickets: "ticket"
        }
    }
}

/// Screens pushed onto a tab's navigation stack.
enum Route: Hashable {
    /// `browseIds` is the People list as displayed, so the detail can page to previous / next.
    case participant(eventId: String, participantEventId: String, browseIds: [String] = [])
    case ticket(id: String)
    case blasts(eventId: String)
}

enum AppSheet: String, Identifiable {
    case settings, eventPicker
    var id: String { rawValue }
}

struct KioskConfig: Identifiable, Hashable {
    var eventId: String
    var scanContextId: String?
    var id: String { "\(eventId)|\(scanContextId ?? "")" }
}

/// Navigation state for the signed-in app. Screens call these instead of owning navigation.
@MainActor
@Observable
final class Router {
    var tab: AppTab = .home
    var paths: [AppTab: [Route]] = [:]
    var sheet: AppSheet?
    var kiosk: KioskConfig?
    /// A confirmation that outlives the screen that raised it (e.g. "Removed Sam" after a
    /// participant page closes). Shown over the current tab.
    var toast: Toast?

    /// Pushes onto the current tab's stack.
    func open(_ route: Route) {
        paths[tab, default: []].append(route)
    }

    func openParticipant(eventId: String, participantEventId: String, browseIds: [String] = []) {
        open(.participant(eventId: eventId, participantEventId: participantEventId, browseIds: browseIds))
    }

    /// Selects a tab; re-selecting the current one pops it to the root, like UIKit.
    func switchTab(_ newTab: AppTab) {
        if tab == newTab { paths[newTab] = [] }
        tab = newTab
    }

    func back() {
        guard var p = paths[tab], !p.isEmpty else { return }
        p.removeLast()
        paths[tab] = p
    }

    func path(for tab: AppTab) -> Binding<[Route]> {
        Binding(get: { self.paths[tab] ?? [] }, set: { self.paths[tab] = $0 })
    }

    /// Handles attend:// deep links from widgets, controls, quick actions and Spotlight:
    /// `attend://scan|home|people|travel|tickets`, `attend://ticket/<id>`,
    /// `attend://participant/<eventId>/<participantEventId>`, `attend://blasts`, `attend://kiosk`,
    /// `attend://settings`, `attend://events`. Returns false for links that aren't navigation.
    @discardableResult
    func handle(_ url: URL, available tabs: [AppTab], selectedEventId: String?) -> Bool {
        guard url.scheme == DeepLink.scheme, let host = url.host()?.lowercased() else { return false }
        let args = Array(url.pathComponents.dropFirst())
        func go(_ t: AppTab) { if tabs.contains(t) { sheet = nil; kiosk = nil; tab = t; paths[t] = [] } }
        switch host {
        case "scan", "scanner": go(.scan)
        case "home": go(.home)
        case "people", "search": go(.people)
        case "travel": go(.travel)
        case "tickets", "my-tickets": go(.tickets)
        case "ticket":
            if tabs.contains(.tickets) { go(.tickets) }
            if let id = args.first { open(.ticket(id: id)) }
        case "participant":
            guard args.count >= 2 else { return false }
            if tabs.contains(.people) { go(.people) }
            open(.participant(eventId: args[0], participantEventId: args[1]))
        case "blasts", "announcements":
            guard let selectedEventId else { return false }
            go(.home)
            open(.blasts(eventId: selectedEventId))
        case "kiosk":
            guard let selectedEventId else { return false }
            kiosk = KioskConfig(eventId: selectedEventId, scanContextId: args.first)
        case "settings": sheet = .settings
        case "events": sheet = .eventPicker
        default: return false
        }
        return true
    }
}
