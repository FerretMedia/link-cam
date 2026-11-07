import CoreGraphics
import LinkCamShared

/// The latest exposure scope, for the scope panel. Only the selected kind is built.
struct ScopeReading {
    let kind: ScopeGraph
    /// The histogram's bins, 0 IRE first; empty for a waveform.
    let bins: [Int32]
    /// The tallest bin, leaving out the two ends (shared `Histogram.peak`).
    let peak: Int
    let crushed: Float
    let clipped: Float
    let waveform: CGImage?

    /// One pixel per column and level, brighter where more of the column sits
    /// at that level. The square root lifts thin traces so a small bright
    /// object still shows. Clipped levels are drawn in false color's clip red.
    static func waveformImage(cells: [Int32], samplesPerColumn: Int) -> CGImage? {
        let b = Bridge.shared
        let cols = Int(b.waveformColumns)
        let levels = Int(b.waveformLevels)
        guard cells.count >= cols * levels, samplesPerColumn > 0 else { return nil }
        let clip = Int(b.falseColorRgb(ire: 100))
        let gain = 1 / (Float(samplesPerColumn) * 0.04)
        // RGBA, premultiplied, top row first: the top row is 100 IRE.
        var pixels = [UInt8](repeating: 0, count: cols * levels * 4)
        for level in 0..<levels {
            let clipped = b.ireOf(step: Int32(level), steps: Int32(levels)) >= b.clipIre
            let rgb = clipped ? clip : 0xFAFAFA
            let row = (levels - 1 - level) * cols
            for col in 0..<cols {
                let n = cells[level * cols + col]
                if n == 0 { continue }
                let alpha = max(48, Int(min(1, (Float(n) * gain).squareRoot()) * 255))
                let i = (row + col) * 4
                pixels[i] = UInt8(((rgb >> 16) & 0xFF) * alpha / 255)
                pixels[i + 1] = UInt8(((rgb >> 8) & 0xFF) * alpha / 255)
                pixels[i + 2] = UInt8((rgb & 0xFF) * alpha / 255)
                pixels[i + 3] = UInt8(alpha)
            }
        }
        guard let provider = CGDataProvider(data: Data(pixels) as CFData) else { return nil }
        return CGImage(
            width: cols, height: levels, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: cols * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
        )
    }
}
