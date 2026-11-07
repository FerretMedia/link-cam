import QuartzCore
import SwiftUI
import UIKit

/// The picture: a view backed by a CAMetalLayer that the renderer draws into
/// from its own queue whenever a frame arrives.
struct MonitorSurface: UIViewRepresentable {
    let renderer: MonitorRenderer

    func makeUIView(context: Context) -> MetalLayerView {
        let view = MetalLayerView()
        view.renderer = renderer
        return view
    }

    func updateUIView(_ view: MetalLayerView, context: Context) {
        view.renderer = renderer
    }

    static func dismantleUIView(_ view: MetalLayerView, coordinator: ()) {
        view.detach()
    }
}

final class MetalLayerView: UIView {
    override class var layerClass: AnyClass { CAMetalLayer.self }

    private var metalLayer: CAMetalLayer { layer as! CAMetalLayer }

    var renderer: MonitorRenderer? {
        didSet {
            guard renderer !== oldValue, let renderer else { return }
            metalLayer.device = renderer.device
            metalLayer.pixelFormat = .bgra8Unorm
            metalLayer.framebufferOnly = true
            // The shader writes Rec.709-primaries R'G'B'; tagging it lets iPadOS
            // show it correctly on a P3 screen instead of stretching it to P3.
            metalLayer.colorspace = CGColorSpace(name: CGColorSpace.sRGB)
            metalLayer.isOpaque = true
            backgroundColor = .black
            setNeedsLayout()
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let scale = window?.screen.scale ?? traitCollection.displayScale
        metalLayer.contentsScale = scale
        let size = CGSize(width: (bounds.width * scale).rounded(), height: (bounds.height * scale).rounded())
        if size.width >= 1, size.height >= 1 { metalLayer.drawableSize = size }
        renderer?.attach(metalLayer, drawableSize: size)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { detach() } else { setNeedsLayout() }
    }

    func detach() {
        renderer?.detach(metalLayer)
    }
}
