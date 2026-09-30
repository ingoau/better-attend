import Foundation

/// A non-2xx response from Attend. Network failures stay `URLError`s.
struct APIError: Error, Hashable, Sendable, LocalizedError {
    var status: Int
    var message: String

    var isUnauthorized: Bool { status == 401 }
    var isForbidden: Bool { status == 403 }
    var isNotFound: Bool { status == 404 }
    var isUnprocessable: Bool { status == 422 }
    var isRateLimited: Bool { status == 429 }
    var isServerError: Bool { status >= 500 }

    var errorDescription: String? { message }
}

extension Error {
    /// True for failures worth retrying later (offline, timeouts, rate limits, 5xx).
    var isTransient: Bool {
        if let api = self as? APIError { return api.isRateLimited || api.isServerError }
        return self is URLError
    }

    var isCancellation: Bool {
        self is CancellationError || (self as? URLError)?.code == .cancelled
    }

    /// Human-readable message for any failure, suitable for an alert or banner.
    var friendlyMessage: String {
        if let api = self as? APIError {
            if api.isUnauthorized { return "Your session has expired. Please sign in again." }
            if api.isForbidden { return api.message != "Forbidden" ? api.message : "You don't have access to this." }
            if api.isRateLimited { return "Attend is rate limiting this network. Try again in a minute." }
            if api.isServerError { return "Attend is having trouble right now (\(api.status)). Try again shortly." }
            return api.message.isBlank ? "Something went wrong (\(api.status))." : api.message
        }
        if let url = self as? URLError {
            switch url.code {
            case .notConnectedToInternet, .networkConnectionLost, .cannotFindHost, .cannotConnectToHost,
                 .dnsLookupFailed, .dataNotAllowed, .internationalRoamingOff:
                return "You're offline. Check your connection."
            case .timedOut:
                return "Attend took too long to respond."
            default:
                return "Couldn't reach Attend. Check your connection."
            }
        }
        if self is DecodingError { return "Attend sent something this app doesn't understand." }
        let text = (self as? LocalizedError)?.errorDescription ?? localizedDescription
        return text.isBlank ? "Something went wrong." : text
    }
}
