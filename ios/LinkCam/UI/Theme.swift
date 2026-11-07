import LinkCamShared
import SwiftUI

/// The design tokens from the shared module, as SwiftUI values.
///
/// The chrome is black, white and the greys between: a neutral surround keeps
/// the UI from tinting how the picture is judged. The brand colours are
/// rationed to the arrival screen and the one blue accent for a tool that's on.
enum LC {
    private static let b = Bridge.shared

    static let surround = Color(argb: b.colorSurround)
    static let ink = Color(argb: b.colorInk)
    static let inkRaised = Color(argb: b.colorInkRaised)
    static let inkLine = Color(argb: b.colorInkLine)
    static let paper = Color(argb: b.colorPaper)
    static let muted = Color(argb: b.colorMuted)
    static let faint = Color(argb: b.colorFaint)
    static let brand = Color(argb: b.colorBrand)
    static let crimson = Color(argb: b.colorCrimson)
    static let brandBright = Color(argb: b.colorBrandBright)
    static let crimsonBright = Color(argb: b.colorCrimsonBright)
    static let low = Color(red: 0xF8 / 255, green: 0x71 / 255, blue: 0x71 / 255)

    static let radius = CGFloat(b.sizeRadius)
    static let radiusChip = CGFloat(b.sizeRadiusChip)
    static let hitTarget = CGFloat(b.sizeHitTarget)
    static let rail = CGFloat(b.sizeRail)
    static let railItem = CGFloat(b.sizeRailItem)
}

extension Color {
    /// From a token's 0xAARRGGBB.
    init(argb: Int64) {
        self.init(
            .sRGB,
            red: Double((argb >> 16) & 0xFF) / 255,
            green: Double((argb >> 8) & 0xFF) / 255,
            blue: Double(argb & 0xFF) / 255,
            opacity: Double((argb >> 24) & 0xFF) / 255
        )
    }

    /// From 0xRRGGBB, as the tools' colours are kept.
    init(rgb: Int) {
        self.init(argb: 0xFF00_0000 | Int64(rgb))
    }
}

/// Type in the system font, like linker.cool. Every number is set in tabular
/// figures so readouts don't jitter as they change.
extension Font {
    static let lcDisplay = Font.system(size: 36, weight: .semibold)
    static let lcTitle = Font.system(size: 24, weight: .semibold)
    static let lcBody = Font.system(size: 15)
    static let lcLabel = Font.system(size: 14, weight: .medium)
    static let lcToggle = Font.system(size: 12, weight: .semibold).monospacedDigit()
    static let lcReadout = Font.system(size: 12).monospacedDigit()
    static let lcCameraValue = Font.system(size: 15, weight: .semibold).monospacedDigit()
    static let lcLegend = Font.system(size: 12, weight: .medium)
}

extension View {
    /// The small caps legend over a group of options.
    func lcLegend(_ color: Color = LC.muted) -> some View {
        font(.lcLegend).tracking(0.6).foregroundStyle(color)
    }
}
