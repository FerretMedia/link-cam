import LinkCamShared
import SwiftUI
import UIKit

/// Ferret Media's brand squares, as on linker.cool and in the Android app:
/// rounded blocks in the two brand colours at full strength, lightly blurred,
/// each drifting on its own period, with grain over them.
///
/// Blue keeps to the left edge and crimson to the right with ink between,
/// because laid over each other the two go violet-grey. Only on the arrival
/// screen; the monitor itself carries no brand.
struct BrandSquares: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height
            // Sized from the short side, so the screen gets corners of colour rather than walls of it.
            let u = min(w, h)
            let squares = [
                Square(x: -u * 0.22, y: -u * 0.14, width: u * 0.62, height: u * 0.40, color: LC.brand, motion: 0),
                Square(x: -u * 0.30, y: h - u * 0.26, width: u * 0.52, height: u * 0.46, color: LC.brand, motion: 1),
                Square(x: w - u * 0.30, y: u * 0.08, width: u * 0.44, height: u * 0.30, color: LC.crimson, motion: 2),
                Square(x: w - u * 0.46, y: h - u * 0.20, width: u * 0.70, height: u * 0.44, color: LC.crimson, motion: 3),
            ]
            TimelineView(.animation(paused: reduceMotion)) { timeline in
                let t = timeline.date.timeIntervalSinceReferenceDate
                ZStack(alignment: .topLeading) {
                    LC.ink
                    ForEach(squares.indices, id: \.self) { i in
                        let s = squares[i]
                        let d = reduceMotion ? 0 : Self.drift(t, motion: s.motion)
                        RoundedRectangle(cornerRadius: 12)
                            .fill(s.color)
                            .frame(width: s.width, height: s.height)
                            .blur(radius: 24)
                            .offset(x: s.x + CGFloat(Bridge.shared.driftX) * d, y: s.y + CGFloat(Bridge.shared.driftY) * d)
                    }
                    Rectangle()
                        .fill(ImagePaint(image: Image(uiImage: Self.grain), scale: 1))
                        .blendMode(.overlay)
                        .opacity(0.4)
                }
                .compositingGroup()
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }

    private struct Square {
        let x, y, width, height: CGFloat
        let color: Color
        let motion: Int
    }

    private static let periods = Bridge.shared.driftPeriodsMs.map { Double($0.intValue) / 1000 }
    private static let delays = Bridge.shared.driftDelaysMs.map { Double($0.intValue) / 1000 }

    /// 0…1…0 over the square's period, eased at both ends, after its start delay.
    private static func drift(_ t: TimeInterval, motion: Int) -> CGFloat {
        let period = periods[motion]
        let phase = max(0, t - delays[motion]).truncatingRemainder(dividingBy: period) / period
        let x = phase < 0.5 ? phase * 2 : (1 - phase) * 2
        return CGFloat(x * x * (3 - 2 * x))
    }

    /// Grey noise, tiled: the grain ferretmedia's site lays over its squares.
    private static let grain: UIImage = {
        let size = 160
        var rng = SplitMix(seed: 7)
        var pixels = [UInt8](repeating: 255, count: size * size * 4)
        for i in 0..<(size * size) {
            let v = UInt8(truncatingIfNeeded: rng.next())
            pixels[i * 4] = v
            pixels[i * 4 + 1] = v
            pixels[i * 4 + 2] = v
        }
        let provider = CGDataProvider(data: Data(pixels) as CFData)!
        let image = CGImage(
            width: size, height: size, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: size * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
        )!
        return UIImage(cgImage: image)
    }()
}

/// A small seeded generator, so the grain is the same every launch.
private struct SplitMix {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
