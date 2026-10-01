import SwiftUI

/// The image that gets shared: event name, the chosen numbers and `shareCardLink`, in its own colour scheme.
/// Drawn at a fixed text size so the exported image doesn't depend on the viewer's Dynamic Type setting.
struct ShareCardView: View {
    let event: Event
    let stats: [ShareStat]
    let options: ShareCardOptions
    let dark: Bool
    let now: Date

    var body: some View {
        let cs = ShareScheme(options.colour, dark: dark)
        // Dark schemes have bright containers, so dark cards flip which layer carries the colour.
        let (container, content): (Color, Color) = switch options.style {
        case .tonal: dark ? (cs.surfaceContainerHigh, cs.onSurface) : (cs.primaryContainer, cs.onPrimaryContainer)
        case .bold: (cs.primary, cs.onPrimary)
        case .playful: (cs.surfaceContainer, cs.onSurface)
        case .outline: (cs.surface, cs.onSurface)
        }
        let shape = RoundedRectangle(cornerRadius: 36, style: .continuous)

        VStack(alignment: .leading, spacing: 0) {
            Text(event.name)
                .font(.system(size: 24, weight: .semibold))
                .lineLimit(2)
            if options.showDetails, let details = DashboardLogic.subtitle(event) {
                Text(details)
                    .font(.system(size: 15))
                    .opacity(0.8)
                    .lineLimit(1)
                    .padding(.top, 2)
            }
            ShareCardTiles(stats: stats, options: options, scheme: cs)
                .padding(.vertical, 16)
            HStack(alignment: .firstTextBaseline) {
                Text(shareCardLink)
                    .font(.system(size: 14, weight: .semibold))
                Spacer(minLength: 8)
                if options.showDetails, let asOf = Time.dayTime(Time.iso(now), tz: event.timezone, now: now) {
                    Text("As of \(asOf)")
                        .font(.system(size: 12, weight: .medium))
                        .opacity(0.8)
                        .lineLimit(1)
                }
            }
        }
        .foregroundStyle(content)
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(container, in: shape)
        .overlay {
            if options.style == .outline { shape.strokeBorder(cs.outline, lineWidth: 2) }
        }
        .environment(\.colorScheme, dark ? .dark : .light)
        .dynamicTypeSize(.large)
    }
}

/// The numbers, chunked into rows whose tiles share one height.
private struct ShareCardTiles: View {
    let stats: [ShareStat]
    let options: ShareCardOptions
    let scheme: ShareScheme

    var body: some View {
        let columns = options.layout.columns(stats.count)
        let rows = stride(from: 0, to: stats.count, by: columns).map { Array(stats[$0..<min($0 + columns, stats.count)]) }
        VStack(spacing: 8) {
            ForEach(Array(rows.enumerated()), id: \.offset) { r, row in
                HStack(spacing: 8) {
                    ForEach(Array(row.enumerated()), id: \.element.id) { c, stat in
                        let index = r * columns + c
                        let palette = TilePalette(options.style, index: index, scheme: scheme)
                        Group {
                            if options.layout == .list {
                                ShareListTile(stat: stat, index: index, options: options, palette: palette)
                            } else {
                                ShareStatTile(stat: stat, index: index, options: options, palette: palette, columns: columns)
                            }
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    }
                    // Keep a short last row's tiles the same width as the rows above.
                    ForEach(0..<(columns - row.count), id: \.self) { _ in
                        Color.clear.frame(maxWidth: .infinity)
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// The container / content / accent colours for one tile.
private struct TilePalette {
    var container: Color
    var content: Color
    var accent: Color
    var border: Color?

    init(container: Color, content: Color, accent: Color, border: Color? = nil) {
        self.container = container
        self.content = content
        self.accent = accent
        self.border = border
    }

    init(_ style: ShareCardStyle, index: Int, scheme cs: ShareScheme) {
        let dark = cs.dark
        switch style {
        case .tonal:
            self = dark ? .init(container: cs.primaryContainer, content: cs.onPrimaryContainer, accent: cs.onPrimaryContainer)
                : .init(container: cs.surfaceContainerLowest, content: cs.onSurface, accent: cs.primary)
        case .bold:
            self = dark ? .init(container: cs.onPrimary, content: cs.primary, accent: cs.primary)
                : .init(container: cs.primaryContainer, content: cs.onPrimaryContainer, accent: cs.primary)
        case .playful:
            switch index % 3 {
            case 0: self = .init(container: cs.primaryContainer, content: cs.onPrimaryContainer, accent: dark ? cs.onPrimaryContainer : cs.primary)
            case 1: self = .init(container: cs.tertiaryContainer, content: cs.onTertiaryContainer, accent: dark ? cs.onTertiaryContainer : cs.tertiary)
            default: self = .init(container: cs.secondaryContainer, content: cs.onSecondaryContainer, accent: dark ? cs.onSecondaryContainer : cs.secondary)
            }
        case .outline:
            self = .init(container: .clear, content: cs.onSurface, accent: cs.primary, border: cs.outlineVariant)
        }
    }
}

private extension View {
    func tileBackground(_ p: TilePalette) -> some View {
        let shape = RoundedRectangle(cornerRadius: 24, style: .continuous)
        return foregroundStyle(p.content)
            .background(p.container, in: shape)
            .overlay {
                if let border = p.border { shape.strokeBorder(border, lineWidth: 1.5) }
            }
    }
}

private struct ShareStatTile: View {
    let stat: ShareStat
    let index: Int
    let options: ShareCardOptions
    let palette: TilePalette
    let columns: Int

    var body: some View {
        let maxSize: CGFloat = switch columns { case 1: 72; case 2: 56; default: 44 }
        VStack(alignment: .leading, spacing: 0) {
            // Three across leaves no room beside the label, so the icon goes on top and every label takes two
            // lines; either way the numbers line up along each row.
            if columns >= 3 {
                if options.showIcons {
                    ShareTileIcon(icon: stat.icon, index: index, style: options.style, palette: palette)
                        .padding(.bottom, 8)
                }
                Text(stat.label)
                    .font(.system(size: 14, weight: .medium))
                    .lineLimit(2, reservesSpace: true)
            } else {
                HStack(spacing: 8) {
                    if options.showIcons {
                        ShareTileIcon(icon: stat.icon, index: index, style: options.style, palette: palette)
                    }
                    Text(stat.label)
                        .font(.system(size: 14, weight: .medium))
                        .lineLimit(2)
                }
            }
            Text(stat.display)
                .font(.system(size: maxSize, weight: .bold, design: .rounded))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(20 / maxSize)
                .padding(.top, 4)
            Spacer(minLength: 0)
            ShareTotals(stat: stat, options: options, palette: palette)
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .tileBackground(palette)
    }
}

private struct ShareListTile: View {
    let stat: ShareStat
    let index: Int
    let options: ShareCardOptions
    let palette: TilePalette

    var body: some View {
        HStack(spacing: 12) {
            if options.showIcons {
                ShareTileIcon(icon: stat.icon, index: index, style: options.style, palette: palette)
            }
            VStack(alignment: .leading, spacing: 0) {
                Text(stat.label)
                    .font(.system(size: 16, weight: .medium))
                    .lineLimit(2)
                ShareTotals(stat: stat, options: options, palette: palette)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(stat.display)
                .font(.system(size: 36, weight: .bold, design: .rounded))
                .monospacedDigit()
                .lineLimit(1)
                .fixedSize()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .tileBackground(palette)
    }
}

/// Expressive shapes the Playful style puts behind each tile's icon.
private let playfulShapes: [CookieShape] = [
    CookieShape(lobes: 9, depth: 0.08), CookieShape(lobes: 4, depth: 0.22), CookieShape(lobes: 8, depth: 0.06),
    CookieShape(lobes: 10, depth: 0.1), CookieShape(lobes: 6, depth: 0.1), CookieShape(lobes: 8, depth: 0.16),
]

private struct ShareTileIcon: View {
    let icon: String
    let index: Int
    let style: ShareCardStyle
    let palette: TilePalette

    var body: some View {
        if style == .playful {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(palette.container)
                .frame(width: 32, height: 32)
                .background(palette.accent, in: playfulShapes[index % playfulShapes.count])
        } else {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(palette.accent)
                .frame(width: 26, height: 22)
        }
    }
}

private struct ShareTotals: View {
    let stat: ShareStat
    let options: ShareCardOptions
    let palette: TilePalette

    var body: some View {
        if options.showTotals, let total = stat.total, let fraction = stat.fraction {
            VStack(alignment: .leading, spacing: 8) {
                Text("of \(total.formatted())")
                    .font(.system(size: 14, weight: .medium))
                    .opacity(0.75)
                Capsule()
                    .fill(palette.accent.opacity(0.2))
                    .frame(height: 6)
                    .overlay(alignment: .leading) {
                        GeometryReader { g in
                            Capsule().fill(palette.accent).frame(width: max(6, g.size.width * fraction))
                        }
                    }
            }
        }
    }
}

// MARK: - Shapes

/// A circle with `lobes` soft bumps around its edge, like Material's cookie and flower shapes.
struct CookieShape: Shape {
    var lobes: Int
    /// How deep the dips between bumps are, as a fraction of the radius.
    var depth: CGFloat

    func path(in rect: CGRect) -> Path {
        let c = CGPoint(x: rect.midX, y: rect.midY)
        let r = min(rect.width, rect.height) / 2
        let steps = 180
        var p = Path()
        for i in 0...steps {
            let t = CGFloat(i) / CGFloat(steps) * 2 * .pi
            let radius = r * (1 - depth / 2 + depth / 2 * cos(CGFloat(lobes) * t))
            let point = CGPoint(x: c.x + radius * cos(t - .pi / 2), y: c.y + radius * sin(t - .pi / 2))
            if i == 0 { p.move(to: point) } else { p.addLine(to: point) }
        }
        p.closeSubpath()
        return p
    }
}

// MARK: - Colour schemes

/// A small Material-style colour scheme generated from one of the card's seed colours: tones of the seed for
/// primary, a muted version for secondary, a hue-shifted one for tertiary and lightly tinted neutrals.
struct ShareScheme {
    let dark: Bool
    var primary, onPrimary, primaryContainer, onPrimaryContainer: Color
    var secondary, secondaryContainer, onSecondaryContainer: Color
    var tertiary, tertiaryContainer, onTertiaryContainer: Color
    var surface, surfaceContainer, surfaceContainerHigh, surfaceContainerLowest, onSurface: Color
    var outline, outlineVariant: Color

    init(_ colour: ShareCardColour, dark: Bool) {
        self.dark = dark
        let (hue, sat, _) = Self.hsl(colour.seed)
        let p = Palette(hue: hue, sat: sat)
        let s = Palette(hue: hue, sat: sat * 0.35)
        let t = Palette(hue: (hue + 60).truncatingRemainder(dividingBy: 360), sat: sat * 0.8)
        let n = Palette(hue: hue, sat: 0.08)
        func roles(_ pal: Palette) -> (Color, Color, Color, Color) {
            dark ? (pal[80], pal[20], pal[30], pal[90]) : (pal[40], pal[100], pal[90], pal[10])
        }
        (primary, onPrimary, primaryContainer, onPrimaryContainer) = roles(p)
        (secondary, _, secondaryContainer, onSecondaryContainer) = roles(s)
        (tertiary, _, tertiaryContainer, onTertiaryContainer) = roles(t)
        surface = dark ? n[6] : n[98]
        surfaceContainer = dark ? n[12] : n[94]
        surfaceContainerHigh = dark ? n[17] : n[92]
        surfaceContainerLowest = dark ? n[4] : n[100]
        onSurface = dark ? n[90] : n[10]
        outline = dark ? n[60] : n[50]
        outlineVariant = dark ? n[30] : n[80]
        // Keep the exact brand red as the light-mode primary so the card reads as Hack Club.
        if colour == .red, !dark {
            primary = Color(hex: colour.seed)
            onPrimary = .white
        }
    }

    /// Tones of one hue, indexed by lightness 0–100.
    private struct Palette {
        var hue: Double
        var sat: Double

        subscript(tone: Int) -> Color {
            // Very light and very dark tones get less saturation so they read as tinted, not neon.
            let l = Double(tone) / 100
            let s = sat * (1 - pow(abs(l - 0.5) * 2, 3) * 0.5)
            return ShareScheme.color(hue: hue, sat: s, light: l)
        }
    }

    private static func hsl(_ hex: UInt32) -> (Double, Double, Double) {
        let r = Double((hex >> 16) & 0xFF) / 255, g = Double((hex >> 8) & 0xFF) / 255, b = Double(hex & 0xFF) / 255
        let mx = max(r, g, b), mn = min(r, g, b), l = (mx + mn) / 2
        guard mx != mn else { return (0, 0, l) }
        let d = mx - mn
        let s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn)
        var h: Double
        switch mx {
        case r: h = (g - b) / d + (g < b ? 6 : 0)
        case g: h = (b - r) / d + 2
        default: h = (r - g) / d + 4
        }
        h *= 60
        return (h, s, l)
    }

    private static func color(hue: Double, sat: Double, light: Double) -> Color {
        let c = (1 - abs(2 * light - 1)) * sat
        let x = c * (1 - abs((hue / 60).truncatingRemainder(dividingBy: 2) - 1))
        let m = light - c / 2
        let (r, g, b): (Double, Double, Double) = switch hue {
        case ..<60: (c, x, 0)
        case ..<120: (x, c, 0)
        case ..<180: (0, c, x)
        case ..<240: (0, x, c)
        case ..<300: (x, 0, c)
        default: (c, 0, x)
        }
        return Color(.sRGB, red: r + m, green: g + m, blue: b + m)
    }
}
