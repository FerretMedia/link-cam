import LinkCamShared
import SwiftUI

/// The exposure graph, floating over a corner of the picture. See-through,
/// so the picture still shows behind it, and drawn in the chrome's greys with
/// false color's clip red and crush purple at the ends, so the graph and the
/// false color overlay agree on what "clipped" means.
struct ScopePanel: View {
    let reading: ScopeReading?
    let kind: ScopeGraph

    private static let clipRed = Color(rgb: Int(Bridge.shared.falseColorRgb(ire: 100)))
    private static let crushPurple = Color(rgb: Int(Bridge.shared.falseColorRgb(ire: 0)))
    /// Under one in a thousand samples is noise, not something to act on.
    private static let speck: Float = 0.001

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(kind.label.uppercased()).lcLegend()
            graph
                .frame(height: 96)
                .background(LC.surround.opacity(0.35))
            if let reading, reading.kind == .histogram {
                HStack {
                    level("▼", reading.crushed, Self.crushPurple, "Crushed")
                    Spacer()
                    level("▲", reading.clipped, Self.clipRed, "Clipped")
                }
            }
        }
        .padding(10)
        .background(LC.inkRaised.opacity(0.55), in: RoundedRectangle(cornerRadius: LC.radius))
        .allowsHitTesting(false)
    }

    @ViewBuilder
    private var graph: some View {
        if let reading, reading.kind == kind {
            switch reading.kind {
            case .histogram:
                Canvas { ctx, size in
                    Self.referenceLines(ctx, size, vertical: true)
                    let b = Bridge.shared
                    let bins = reading.bins
                    guard !bins.isEmpty else { return }
                    let peak = CGFloat(max(reading.peak, 1))
                    let barWidth = size.width / CGFloat(bins.count)
                    for (i, n) in bins.enumerated() where n > 0 {
                        let ire = b.ireOf(step: Int32(i), steps: Int32(bins.count))
                        let color: Color = ire < b.crushIre ? Self.crushPurple : ire >= b.clipIre ? Self.clipRed : LC.paper.opacity(0.8)
                        // At least a pixel, so a level that's there never reads as empty.
                        let height = max(1, min(1, CGFloat(n) / peak) * size.height)
                        ctx.fill(Path(CGRect(x: CGFloat(i) * barWidth, y: size.height - height, width: barWidth + 0.5, height: height)), with: .color(color))
                    }
                }
            case .waveform:
                ZStack {
                    if let image = reading.waveform {
                        Image(decorative: image, scale: 1)
                            .resizable()
                            .interpolation(.low)
                    }
                    Canvas { ctx, size in Self.referenceLines(ctx, size, vertical: false) }
                }
            }
        } else {
            Canvas { ctx, size in Self.referenceLines(ctx, size, vertical: kind == .histogram) }
        }
    }

    private func level(_ arrow: String, _ fraction: Float, _ color: Color, _ name: String) -> some View {
        Text("\(arrow) \(Self.percent(fraction))")
            .font(.lcReadout)
            .foregroundStyle(fraction >= Self.speck ? color : LC.faint)
            .accessibilityLabel("\(name) \(Self.percent(fraction))")
    }

    /// Faint lines at 0, 25, 50, 75 and 100 IRE: across the graph for a waveform, up it for a histogram.
    private static func referenceLines(_ ctx: GraphicsContext, _ size: CGSize, vertical: Bool) {
        let stroke: CGFloat = 1
        for ire in [0.0, 25, 50, 75, 100] as [CGFloat] {
            var line = Path()
            if vertical {
                let x = ire / 100 * (size.width - stroke) + stroke / 2
                line.move(to: CGPoint(x: x, y: 0))
                line.addLine(to: CGPoint(x: x, y: size.height))
            } else {
                let y = (1 - ire / 100) * (size.height - stroke) + stroke / 2
                line.move(to: CGPoint(x: 0, y: y))
                line.addLine(to: CGPoint(x: size.width, y: y))
            }
            ctx.stroke(line, with: .color(LC.inkLine.opacity(ire == 50 ? 1 : 0.7)), lineWidth: stroke)
        }
    }

    private static func percent(_ f: Float) -> String {
        switch f {
        case ...0: "0%"
        case ..<0.001: "<0.1%"
        case ..<0.1: String(format: "%.1f%%", f * 100)
        default: String(format: "%.0f%%", f * 100)
        }
    }
}
