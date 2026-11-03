import LinkCamShared
import SwiftUI

/// Frame lines and safe areas, drawn over the picture as vector lines rather
/// than in the shader: they stay sharp, cost nothing on the GPU, and can never
/// end up in a recording.
struct GuidesOverlay: View {
    let aspect: Float
    let tools: ToolSettings

    var body: some View {
        if tools.guides || tools.safeAreas {
            Canvas { ctx, size in
                let b = Bridge.shared
                // The same fit the renderer uses, so the lines land on the picture.
                let picture = b.fit(width: Float(size.width), height: Float(size.height), aspect: aspect)
                let stroke: CGFloat = 1.5
                if tools.guides {
                    let frame = b.frameLine(picture: picture, guide: tools.guideAspect.rawValue)
                    // Darken what falls outside the frame line, then draw the line.
                    var outside = Path(picture.cg)
                    outside.addPath(Path(frame.cg))
                    ctx.fill(outside, with: .color(.black.opacity(0.45)), style: FillStyle(eoFill: true))
                    ctx.stroke(Path(frame.cg), with: .color(.white.opacity(0.85)), lineWidth: stroke)
                }
                if tools.safeAreas {
                    ctx.stroke(Path(picture.inset(fraction: b.actionSafe).cg), with: .color(.white.opacity(0.5)), lineWidth: stroke)
                    ctx.stroke(Path(picture.inset(fraction: b.titleSafe).cg), with: .color(.white.opacity(0.35)), lineWidth: stroke)
                    let r = picture.cg
                    let arm: CGFloat = 12
                    var cross = Path()
                    cross.move(to: CGPoint(x: r.midX - arm, y: r.midY))
                    cross.addLine(to: CGPoint(x: r.midX + arm, y: r.midY))
                    cross.move(to: CGPoint(x: r.midX, y: r.midY - arm))
                    cross.addLine(to: CGPoint(x: r.midX, y: r.midY + arm))
                    ctx.stroke(cross, with: .color(.white.opacity(0.7)), lineWidth: stroke)
                }
            }
            .allowsHitTesting(false)
        }
    }
}

extension LinkCamShared.Rect {
    var cg: CGRect { CGRect(x: CGFloat(left), y: CGFloat(top), width: CGFloat(width), height: CGFloat(height)) }
}
