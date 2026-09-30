import SwiftUI

/// Hack Club brand colours.
enum HackClub {
    static let red = Color(hex: 0xEC3750)
    static let orange = Color(hex: 0xFF8C37)
    static let yellow = Color(hex: 0xF1C40F)
    static let green = Color(hex: 0x33D6A6)
    static let cyan = Color(hex: 0x5BC0DE)
    static let blue = Color(hex: 0x338EDA)
    static let purple = Color(hex: 0xA633D6)
    static let dark = Color(hex: 0x17171D)
}

/// Semantic status colours for outcomes, pills and tinted cards. Each tone has a strong colour
/// for icons and fills, plus a container / on-container pair for tinted surfaces.
enum Tone: Hashable, Sendable {
    case success, warning, info, danger, neutral, brand

    var color: Color {
        switch self {
        case .success: Color(light: 0x0E8A63, dark: 0x5BE0B5)
        case .warning: Color(light: 0xB85A00, dark: 0xFFB783)
        case .info: Color(light: 0x1D6FB8, dark: 0x9FCAFF)
        case .danger: Color(light: 0xC8102E, dark: 0xFFB3B3)
        case .neutral: Color.secondary
        case .brand: Color.accentColor
        }
    }

    /// Text/icon colour on top of `color`.
    var onColor: Color {
        switch self {
        case .success: Color(light: 0xFFFFFF, dark: 0x00382A)
        case .warning: Color(light: 0xFFFFFF, dark: 0x4F2500)
        case .info: Color(light: 0xFFFFFF, dark: 0x003259)
        case .danger: Color(light: 0xFFFFFF, dark: 0x680013)
        case .neutral, .brand: .white
        }
    }

    var container: Color {
        switch self {
        case .success: Color(light: 0xC9F4E4, dark: 0x00513D)
        case .warning: Color(light: 0xFFDCC2, dark: 0x703700)
        case .info: Color(light: 0xD3E4FF, dark: 0x00497E)
        case .danger: Color(light: 0xFFDAD9, dark: 0x93001F)
        case .neutral: Color(uiColor: .tertiarySystemFill)
        case .brand: Color.accentColor.opacity(0.15)
        }
    }

    var onContainer: Color {
        switch self {
        case .success: Color(light: 0x00382A, dark: 0xB4F5DC)
        case .warning: Color(light: 0x3A1C00, dark: 0xFFDCC2)
        case .info: Color(light: 0x001C38, dark: 0xD3E4FF)
        case .danger: Color(light: 0x410008, dark: 0xFFDAD9)
        case .neutral: Color.primary
        case .brand: Color.accentColor
        }
    }
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: opacity)
    }

    /// A colour that adapts to light and dark appearance.
    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                           blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        })
    }

    /// Parses "#3b82f6" / "3b82f6" (group colours from the API).
    init?(hexString: String?) {
        guard var s = hexString?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return nil }
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        self.init(hex: v)
    }
}

extension Font {
    /// Big rounded numbers for stats ("84 / 120").
    static func stat(_ size: CGFloat, weight: Font.Weight = .bold) -> Font {
        .system(size: size, weight: weight, design: .rounded)
    }
}
