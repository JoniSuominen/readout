import Foundation
import SwiftUI

/// A plain sRGB color whose contrast against a background can be measured.
struct WidgetRGB: Equatable {
    var red: Double
    var green: Double
    var blue: Double

    static let white = WidgetRGB(red: 1, green: 1, blue: 1)

    var color: Color { Color(red: red, green: green, blue: blue) }

    /// Relative luminance as WCAG defines it.
    var luminance: Double {
        func linear(_ channel: Double) -> Double {
            channel <= 0.03928 ? channel / 12.92 : pow((channel + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
    }

    /// WCAG contrast ratio, 1 to 21. Body text needs at least 4.5.
    func contrast(with other: WidgetRGB) -> Double {
        let lighter = max(luminance, other.luminance)
        let darker = min(luminance, other.luminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /// This color drawn at `opacity` over a background.
    func blended(over background: WidgetRGB, opacity: Double) -> WidgetRGB {
        WidgetRGB(
            red: red * opacity + background.red * (1 - opacity),
            green: green * opacity + background.green * (1 - opacity),
            blue: blue * opacity + background.blue * (1 - opacity)
        )
    }
}

/// How a layout's widget looks: its background, and whether tiles draw in
/// light or dark colors whatever the phone is set to.
enum WidgetAppearance: String, Codable, CaseIterable, Identifiable {
    /// Follows the phone's light and dark setting.
    case system
    case light
    case dark
    case black
    // There is no transparent style: iOS draws a dark box behind a clear
    // widget, never the wallpaper. Layouts saved with it load as System.
    case sky
    case ember

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .system: return "System"
        case .light: return "Light"
        case .dark: return "Dark"
        case .black: return "Black"
        case .sky: return "Sky"
        case .ember: return "Ember"
        }
    }

    /// The scheme tiles draw in. Nil follows the phone.
    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark, .black, .sky, .ember: return .dark
        }
    }

    /// Colors from one corner of the widget to the opposite one, for the
    /// appearances drawn as a gradient. Empty for the others.
    var gradientStops: [WidgetRGB] {
        switch self {
        case .sky:
            return [WidgetRGB(red: 0.04, green: 0.13, blue: 0.33), WidgetRGB(red: 0.07, green: 0.30, blue: 0.48)]
        case .ember:
            return [WidgetRGB(red: 0.40, green: 0.08, blue: 0.14), WidgetRGB(red: 0.58, green: 0.20, blue: 0.10)]
        default:
            return []
        }
    }

    var isGradient: Bool { !gradientStops.isEmpty }

    /// The widget's container background.
    @ViewBuilder
    var background: some View {
        switch self {
        case .system:
            Rectangle().fill(.background)
        case .light:
            Color.white
        case .dark:
            Color(red: 0.11, green: 0.11, blue: 0.12)
        case .black:
            Color.black
        case .sky, .ember:
            LinearGradient(colors: gradientStops.map(\.color), startPoint: .topLeading, endPoint: .bottomTrailing)
        }
    }

    /// The background for in-app previews, matching what the Home Screen draws.
    @ViewBuilder
    var previewBackground: some View {
        switch self {
        case .system:
            Color(.systemBackground)
        default:
            background
        }
    }

    // MARK: Tile colors

    /// Opacity of white secondary text on a gradient. The system's own
    /// secondary style is too faint on these backgrounds.
    static let gradientSecondaryOpacity = 0.78

    /// Captions and other secondary text.
    var secondaryInk: AnyShapeStyle {
        isGradient ? AnyShapeStyle(Color.white.opacity(Self.gradientSecondaryOpacity)) : AnyShapeStyle(HierarchicalShapeStyle.secondary)
    }

    /// An indicator color. On a gradient the system colors lose contrast, so
    /// lighter versions take their place.
    func tint(named name: String) -> Color {
        isGradient ? TileTint.bright(name) : TileTint.color(name)
    }

    /// Stale or failed refresh state.
    var warningColor: Color {
        isGradient ? TileTint.bright("orange") : .orange
    }
}

private struct WidgetAppearanceKey: EnvironmentKey {
    static let defaultValue = WidgetAppearance.system
}

extension EnvironmentValues {
    /// The appearance of the widget a tile is drawn in.
    var widgetAppearanceStyle: WidgetAppearance {
        get { self[WidgetAppearanceKey.self] }
        set { self[WidgetAppearanceKey.self] = newValue }
    }
}

private struct WidgetAppearanceModifier: ViewModifier {
    @Environment(\.colorScheme) private var systemScheme
    let appearance: WidgetAppearance

    func body(content: Content) -> some View {
        content
            .environment(\.colorScheme, appearance.colorScheme ?? systemScheme)
            .environment(\.widgetAppearanceStyle, appearance)
    }
}

extension View {
    /// Draws the content in the colors of a widget appearance.
    func widgetAppearance(_ appearance: WidgetAppearance) -> some View {
        modifier(WidgetAppearanceModifier(appearance: appearance))
    }
}
