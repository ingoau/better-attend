import AuthenticationServices
import SwiftUI

struct LoginView: View {
    var message: String?
    @Environment(AppModel.self) private var app
    @Environment(\.webAuthenticationSession) private var webAuthenticationSession
    @State private var signingIn = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                Spacer(minLength: 24)
                AppGlyph(size: 88)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 6) {
                    Text("FOR HACK CLUB ATTEND")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(.tint)
                        .tracking(1.5)
                    Text("BetterAttend")
                        .font(.system(size: 56, weight: .black, design: .rounded))
                        .lineLimit(1)
                        .minimumScaleFactor(0.5)
                    Text("Check people in, keep track of who's here, and carry your event pass — all in one place.")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                VStack(alignment: .leading, spacing: 14) {
                    Feature(symbol: "qrcode.viewfinder", text: "Scan tickets and NFC badges, even offline")
                    Feature(symbol: "chart.bar.fill", text: "Live check-in counts on your Home Screen")
                    Feature(symbol: "ticket.fill", text: "Your event pass, ready at the door")
                }

                Spacer(minLength: 12)

                if let message {
                    Label(message, systemImage: "exclamationmark.triangle.fill")
                        .font(.callout)
                        .foregroundStyle(Tone.danger.onContainer)
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Tone.danger.container, in: .rect(cornerRadius: 16))
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }

                VStack(spacing: 12) {
                    Button(action: signIn) {
                        HStack {
                            if signingIn { ProgressView().tint(.white) }
                            Text("Sign in with Hack Club")
                        }
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.capsule)
                    .controlSize(.large)
                    .disabled(signingIn)

                    Text("For attendees and event staff. You'll sign in on auth.hackclub.com.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(24)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
        .animation(.smooth, value: message)
        .sensoryFeedback(.error, trigger: message) { _, new in new != nil && Haptics.enabled }
    }

    private func signIn() {
        Haptics.tap()
        signingIn = true
        Task {
            defer { signingIn = false }
            do {
                let callback = try await webAuthenticationSession.authenticate(
                    using: app.auth.makeAuthorizeURL(),
                    callback: .customScheme(AuthStore.callbackScheme),
                    preferredBrowserSession: .shared,
                    additionalHeaderFields: [:]
                )
                await app.auth.handleCallback(callback)
            } catch let error as ASWebAuthenticationSessionError where error.code == .canceledLogin {
                app.auth.cancelled()
            } catch {
                app.auth.cancelled("Sign-in couldn't be completed. Please try again.")
            }
        }
    }
}

private struct Feature: View {
    let symbol: String
    let text: String

    var body: some View {
        Label {
            Text(text).font(.body)
        } icon: {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .foregroundStyle(.tint)
                .frame(width: 28)
        }
    }
}

/// The app icon's ticket-with-a-check, drawn in SwiftUI.
struct AppGlyph: View {
    var size: CGFloat = 64

    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.225, style: .continuous)
            .fill(HackClub.red.gradient)
            .frame(width: size, height: size)
            .overlay {
                ZStack {
                    TicketShape()
                        .fill(.white)
                        .frame(width: size * 0.62, height: size * 0.4)
                    Image(systemName: "checkmark")
                        .font(.system(size: size * 0.2, weight: .heavy))
                        .foregroundStyle(HackClub.red)
                }
            }
            .shadow(color: HackClub.red.opacity(0.35), radius: size * 0.12, y: size * 0.06)
    }
}

/// A ticket outline with semicircle notches on both sides.
struct TicketShape: Shape {
    func path(in rect: CGRect) -> Path {
        let r = rect.height * 0.14
        let notch = rect.height * 0.16
        var p = Path()
        p.move(to: CGPoint(x: rect.minX + r, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
        p.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.minY + r), control: CGPoint(x: rect.maxX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.midY - notch))
        p.addArc(center: CGPoint(x: rect.maxX, y: rect.midY), radius: notch, startAngle: .degrees(-90), endAngle: .degrees(90), clockwise: true)
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r))
        p.addQuadCurve(to: CGPoint(x: rect.maxX - r, y: rect.maxY), control: CGPoint(x: rect.maxX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
        p.addQuadCurve(to: CGPoint(x: rect.minX, y: rect.maxY - r), control: CGPoint(x: rect.minX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.midY + notch))
        p.addArc(center: CGPoint(x: rect.minX, y: rect.midY), radius: notch, startAngle: .degrees(90), endAngle: .degrees(-90), clockwise: true)
        p.addLine(to: CGPoint(x: rect.minX, y: rect.minY + r))
        p.addQuadCurve(to: CGPoint(x: rect.minX + r, y: rect.minY), control: CGPoint(x: rect.minX, y: rect.minY))
        p.closeSubpath()
        return p
    }
}

#Preview {
    LoginView(message: nil).environment(AppModel(demo: true))
}
