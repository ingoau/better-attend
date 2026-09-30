import CryptoKit
import Foundation
import UIKit

enum AuthState: Equatable {
    case loading
    case signedOut(message: String?)
    case signedIn(User)

    var user: User? { if case .signedIn(let u) = self { u } else { nil } }
}

/// Hack Club Auth sign-in using the same OAuth client and redirect as the official app:
/// Authorization Code + PKCE in an `ASWebAuthenticationSession`, then the code is exchanged by the
/// Attend backend (which holds the client secret) for a 14-day mobile token.
@MainActor
@Observable
final class AuthStore {
    static let authorizeURL = URL(string: "https://auth.hackclub.com/oauth/authorize")!
    static let clientId = "aaa422633e9a7df85892eb2ba84f02d9"
    static let redirectURI = "attend://oauth/callback"
    static let callbackScheme = "attend"

    private(set) var state: AuthState

    @ObservationIgnored private let store: SecureTokenStore
    @ObservationIgnored private let api: () -> AttendAPI
    /// Called whenever a session ends (sign-out or expiry) so account data can be wiped.
    @ObservationIgnored var onSignedOut: () -> Void = {}

    @ObservationIgnored private var pkceVerifier: String?
    @ObservationIgnored private var oauthState: String?

    init(store: SecureTokenStore, api: @escaping () -> AttendAPI) {
        self.store = store
        self.api = api
        if store.token != nil, let user = store.user {
            state = .signedIn(user)
        } else {
            state = .signedOut(message: nil)
        }
    }

    var currentUser: User? { state.user }

    /// A fresh authorize URL. Each call starts a new PKCE exchange.
    func makeAuthorizeURL() -> URL {
        let verifier = Self.randomURLSafe(64)
        let stateParam = Self.randomURLSafe(16)
        pkceVerifier = verifier
        oauthState = stateParam
        let challenge = Data(SHA256.hash(data: Data(verifier.utf8))).base64URLEncoded()
        var c = URLComponents(url: Self.authorizeURL, resolvingAgainstBaseURL: false)!
        c.queryItems = [
            .init(name: "client_id", value: Self.clientId),
            .init(name: "redirect_uri", value: Self.redirectURI),
            .init(name: "response_type", value: "code"),
            .init(name: "scope", value: "email"),
            .init(name: "state", value: stateParam),
            .init(name: "code_challenge", value: challenge),
            .init(name: "code_challenge_method", value: "S256"),
        ]
        return c.url!
    }

    /// Stores a freshly issued session and switches to the signed-in state.
    func signIn(with session: SessionResponse) {
        store.update(token: session.token, expiresAt: session.expiresAt)
        store.user = session.user
        state = .signedIn(session.user)
    }

    /// Completes sign-in from the redirect URL. Returns nil on success or an error message.
    @discardableResult
    func handleCallback(_ url: URL) async -> String? {
        if state == .loading || state.user != nil { return nil }
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func param(_ name: String) -> String? { items.first { $0.name == name }?.value }

        if let err = param("error") {
            return fail(err == "access_denied" ? nil : param("error_description") ?? "Sign-in failed (\(err))")
        }
        guard let code = param("code") else { return fail("Sign-in didn't return a code. Please try again.") }
        if let expected = oauthState, let returned = param("state"), returned != expected {
            return fail("Sign-in response didn't match this request. Please try again.")
        }
        guard let verifier = pkceVerifier else { return fail("Sign-in session expired. Please try again.") }

        state = .loading
        do {
            let session = try await api().createSession(code: code, redirectURI: Self.redirectURI, codeVerifier: verifier, deviceName: Self.deviceName)
            pkceVerifier = nil
            oauthState = nil
            signIn(with: session)
            return nil
        } catch let e as APIError where e.isUnauthorized {
            return fail("We couldn't find an Attend account for that Hack Club login. Ask your event organiser to add you, then try again.")
        } catch {
            if error.isCancellation {
                state = .signedOut(message: nil)
                return nil
            }
            return fail(error.friendlyMessage)
        }
    }

    /// The browser flow ended without a result (the user closed it, or it failed to start).
    func cancelled(_ message: String? = nil) {
        if state == .loading || state.user != nil { return }
        state = .signedOut(message: message)
    }

    private func fail(_ message: String?) -> String? {
        state = .signedOut(message: message)
        return message
    }

    /// Re-validates the stored session in the background; keeps working offline.
    func refreshUser() async {
        guard store.token != nil else { return }
        do {
            let user = try await api().me()
            store.user = user
            state = .signedIn(user)
        } catch let e as APIError where e.isUnauthorized {
            sessionExpired()
        } catch {
            // Offline: keep the cached session.
        }
    }

    func sessionExpired() {
        if store.token == nil, case .signedOut = state { return }
        endSession(message: "Your session expired. Please sign in again.")
    }

    func signOut() async {
        await api().deleteSession()
        endSession(message: nil)
    }

    private func endSession(message: String?) {
        let wasSignedIn = state.user != nil
        store.update(token: nil, expiresAt: nil)
        store.user = nil
        state = .signedOut(message: message)
        if wasSignedIn { onSignedOut() }
    }

    private static var deviceName: String {
        "\(UIDevice.current.model) (Better Attend)"
    }

    private static func randomURLSafe(_ bytes: Int) -> String {
        var b = [UInt8](repeating: 0, count: bytes)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes, &b)
        return Data(b).base64URLEncoded()
    }
}

extension Data {
    func base64URLEncoded() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
