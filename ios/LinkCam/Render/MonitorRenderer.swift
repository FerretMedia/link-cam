import CoreGraphics
import CoreVideo
import LinkCamShared
import Metal
import QuartzCore

/// Draws the current source with the monitoring tools applied.
///
/// It draws only when something changes: a frame arrives, a setting changes
/// or the view is resized. A frame's two planes are mapped into Metal
/// textures without a copy and drawn through `monitorFragment`. The picture is
/// fitted to the view with a black surround; the SwiftUI overlays use the same
/// fit from the shared `Guides.fit`, so the frame lines land on the picture.
///
/// Frames arrive on the source's queue and are drawn on the renderer's own,
/// newest first: a frame that arrives while the last is still being drawn
/// replaces any other waiting, so the picture never falls behind.
///
/// With noise reduction on, each new frame first goes through
/// `denoiseFragment`, which blends it into a cleaned copy kept from frame to
/// frame (two textures, read one and write the other), and the display pass
/// reads that copy. When the scope is on, it also samples the frame's luma a
/// few times a second on the CPU, which on the iPad's shared memory costs
/// less than a GPU pass and a read back.
final class MonitorRenderer: @unchecked Sendable {
    /// What the picture is drawn with. Set from the main thread.
    struct Settings: Equatable {
        var tools = ToolSettings()
        var colorSpec = ColorSpecChoice.hdVideo
        var aspect: Float = 16 / 9
    }

    struct Stats {
        var fps: Float = 0
        /// Average time from a frame arriving in the app to its draw being committed.
        var latencyMs: Float = 0
    }

    let device: MTLDevice

    /// The newest scope reading, or nil once the scope is off. Called on the main queue.
    var onScopes: ((ScopeReading?) -> Void)?

    private let commandQueue: MTLCommandQueue
    private let monitorPipeline: MTLRenderPipelineState
    private let denoisePipeline: MTLRenderPipelineState
    private var textureCache: CVMetalTextureCache?
    private let ramp: MTLTexture
    private let queue = DispatchQueue(label: "cool.linker.cam.render", qos: .userInteractive)
    private let startTime = CACurrentMediaTime()

    // Guarded by `lock`.
    private let lock = NSLock()
    private var pending: (buffer: CVPixelBuffer, arrived: CFTimeInterval)?
    private var params = Params(Settings())
    private var layer: CAMetalLayer?
    private var drawableSize = CGSize.zero
    private var scheduled = false
    private var resetRequested = false
    private var stats = Stats()

    // The render queue's own.
    private var current: CVPixelBuffer?
    private var history: [MTLTexture] = []
    private var historyIndex = 0
    private var historyValid = false
    private var lastScope: CFTimeInterval = 0
    private var scopeShown = false
    private var ireKey: String?
    private var ireTable: KotlinFloatArray?
    private var windowStart: CFTimeInterval = 0
    private var windowFrames = 0
    private var windowLatency: CFTimeInterval = 0

    init() {
        guard let device = MTLCreateSystemDefaultDevice(),
              let commandQueue = device.makeCommandQueue(),
              let library = device.makeDefaultLibrary()
        else { fatalError("Link Cam needs Metal, which every iPad it runs on has") }
        self.device = device
        self.commandQueue = commandQueue

        let vertex = library.makeFunction(name: "fullscreenVertex")
        let monitor = MTLRenderPipelineDescriptor()
        monitor.label = "monitor"
        monitor.vertexFunction = vertex
        monitor.fragmentFunction = library.makeFunction(name: "monitorFragment")
        monitor.colorAttachments[0].pixelFormat = .bgra8Unorm
        let denoise = MTLRenderPipelineDescriptor()
        denoise.label = "denoise"
        denoise.vertexFunction = vertex
        denoise.fragmentFunction = library.makeFunction(name: "denoiseFragment")
        denoise.colorAttachments[0].pixelFormat = .rgba16Float
        do {
            monitorPipeline = try device.makeRenderPipelineState(descriptor: monitor)
            denoisePipeline = try device.makeRenderPipelineState(descriptor: denoise)
        } catch {
            fatalError("The monitor's shaders didn't build: \(error)")
        }
        CVMetalTextureCacheCreate(nil, nil, device, nil, &textureCache)

        // FalseColor.ramp(): 256×1, entry i covering i/255 of 0..100 IRE.
        let rampData = Bridge.shared.falseColorRamp()
        let rampDescriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: rampData.count / 4, height: 1, mipmapped: false)
        rampDescriptor.usage = .shaderRead
        guard let ramp = device.makeTexture(descriptor: rampDescriptor) else { fatalError("no ramp texture") }
        rampData.withUnsafeBytes { bytes in
            ramp.replace(region: MTLRegionMake2D(0, 0, rampData.count / 4, 1), mipmapLevel: 0, withBytes: bytes.baseAddress!, bytesPerRow: rampData.count)
        }
        self.ramp = ramp
    }

    // MARK: - Input, from any thread

    /// The view's layer, and its size in pixels.
    func attach(_ layer: CAMetalLayer, drawableSize: CGSize) {
        lock.withLock {
            self.layer = layer
            self.drawableSize = drawableSize
        }
        schedule()
    }

    /// Stops drawing into [layer], unless another view has taken over already.
    func detach(_ layer: CAMetalLayer) {
        lock.withLock {
            if self.layer === layer { self.layer = nil }
        }
    }

    func submit(_ buffer: CVPixelBuffer) {
        lock.withLock { pending = (buffer, CACurrentMediaTime()) }
        schedule()
    }

    func update(_ settings: Settings) {
        let next = Params(settings)
        let changed = lock.withLock { () -> Bool in
            guard next.settings != params.settings else { return false }
            params = next
            return true
        }
        if changed { schedule() }
    }

    /// Forgets the last picture and the noise reduction's history, for a new source.
    func reset() {
        lock.withLock {
            pending = nil
            resetRequested = true
        }
        schedule()
    }

    var currentStats: Stats { lock.withLock { stats } }

    private func schedule() {
        let start = lock.withLock { () -> Bool in
            guard !scheduled else { return false }
            scheduled = true
            return true
        }
        if start { queue.async { self.draw() } }
    }

    // MARK: - Drawing, on the render queue

    private func draw() {
        let (fresh, p, layer, size, reset) = lock.withLock { () -> ((buffer: CVPixelBuffer, arrived: CFTimeInterval)?, Params, CAMetalLayer?, CGSize, Bool) in
            scheduled = false
            let fresh = pending
            pending = nil
            let reset = resetRequested
            resetRequested = false
            return (fresh, params, self.layer, drawableSize, reset)
        }
        if reset {
            current = nil
            historyValid = false
        }
        if let fresh { current = fresh.buffer }
        guard let layer, size.width >= 1, size.height >= 1,
              let drawable = layer.nextDrawable(),
              let commandBuffer = commandQueue.makeCommandBuffer()
        else { return }

        // The CoreVideo wrappers keep each plane's memory alive until the GPU is done with it.
        var planes: (y: MTLTexture, cbcr: MTLTexture)?
        var keepAlive: [CVMetalTexture] = []
        if let frame = current, CVPixelBufferGetPlaneCount(frame) == 2,
           let y = texture(frame, plane: 0, format: .r8Unorm),
           let cbcr = texture(frame, plane: 1, format: .rg8Unorm) {
            planes = (y.texture, cbcr.texture)
            keepAlive = [y.ref, cbcr.ref]
        }

        let clean = p.settings.tools.noiseReduction != .off && planes != nil
        if !clean {
            historyValid = false
        } else if fresh != nil, let planes {
            denoise(commandBuffer, y: planes.y, cbcr: planes.cbcr, p: p)
        }

        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = drawable.texture
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        pass.colorAttachments[0].storeAction = .store
        if let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) {
            if let planes {
                let fit = Bridge.shared.fit(width: Float(size.width), height: Float(size.height), aspect: p.settings.aspect)
                encoder.setViewport(MTLViewport(
                    originX: Double(fit.left), originY: Double(fit.top),
                    width: Double(fit.width), height: Double(fit.height),
                    znear: 0, zfar: 1
                ))
                let showCleaned = clean && historyValid
                var u = p.uniforms
                u.lumaTexel = SIMD2(1 / Float(planes.y.width), 1 / Float(planes.y.height))
                u.time = Float((CACurrentMediaTime() - startTime).truncatingRemainder(dividingBy: 60))
                u.clean = showCleaned ? 1 : 0
                encoder.setRenderPipelineState(monitorPipeline)
                encoder.setFragmentTexture(planes.y, index: 0)
                encoder.setFragmentTexture(planes.cbcr, index: 1)
                encoder.setFragmentTexture(ramp, index: 2)
                encoder.setFragmentTexture(showCleaned ? history[historyIndex] : planes.y, index: 3)
                encoder.setFragmentBytes(&u, length: MemoryLayout<MonitorUniforms>.stride, index: 0)
                encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
            }
            encoder.endEncoding()
        }
        commandBuffer.present(drawable)
        commandBuffer.addCompletedHandler { _ in withExtendedLifetime(keepAlive) {} }
        commandBuffer.commit()

        if let fresh {
            account(arrived: fresh.arrived)
            sampleScopes(fresh.buffer, p)
        } else if !p.settings.tools.scope {
            sampleScopes(nil, p)
        }
    }

    private func texture(_ buffer: CVPixelBuffer, plane: Int, format: MTLPixelFormat) -> (texture: MTLTexture, ref: CVMetalTexture)? {
        guard let cache = textureCache else { return nil }
        let width = CVPixelBufferGetWidthOfPlane(buffer, plane)
        let height = CVPixelBufferGetHeightOfPlane(buffer, plane)
        var ref: CVMetalTexture?
        let status = CVMetalTextureCacheCreateTextureFromImage(nil, cache, buffer, nil, format, width, height, plane, &ref)
        guard status == kCVReturnSuccess, let ref, let texture = CVMetalTextureGetTexture(ref) else { return nil }
        return (texture, ref)
    }

    /// Blends the frame just arrived into the cleaned copy. The history starts
    /// afresh when the source, its size or the setting's first frame changes.
    private func denoise(_ commandBuffer: MTLCommandBuffer, y: MTLTexture, cbcr: MTLTexture, p: Params) {
        if history.count != 2 || history[0].width != y.width || history[0].height != y.height {
            // Half float keeps the fractions of a code that averaging frames produces.
            let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba16Float, width: y.width, height: y.height, mipmapped: false)
            d.usage = [.renderTarget, .shaderRead]
            d.storageMode = .private
            history = (0..<2).compactMap { _ in device.makeTexture(descriptor: d) }
            historyValid = false
            guard history.count == 2 else { return }
        }
        let previous = historyIndex
        let next = 1 - previous
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = history[next]
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else { return }
        var u = DenoiseUniforms(
            lumaTexel: SIMD2(1 / Float(y.width), 1 / Float(y.height)),
            chromaTexel: SIMD2(1 / Float(cbcr.width), 1 / Float(cbcr.height)),
            keep: historyValid ? p.keep : 0,
            noise: p.noise
        )
        encoder.setRenderPipelineState(denoisePipeline)
        encoder.setFragmentTexture(y, index: 0)
        encoder.setFragmentTexture(cbcr, index: 1)
        encoder.setFragmentTexture(history[previous], index: 2)
        encoder.setFragmentBytes(&u, length: MemoryLayout<DenoiseUniforms>.stride, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        historyIndex = next
        historyValid = true
    }

    private func account(arrived: CFTimeInterval) {
        let now = CACurrentMediaTime()
        if windowStart == 0 { windowStart = now }
        windowFrames += 1
        windowLatency += now - arrived
        let elapsed = now - windowStart
        guard elapsed >= 1 else { return }
        let next = Stats(fps: Float(Double(windowFrames) / elapsed), latencyMs: Float(windowLatency / Double(windowFrames) * 1000))
        lock.withLock { stats = next }
        windowStart = now
        windowFrames = 0
        windowLatency = 0
    }

    // MARK: - Scopes

    /// About 15 times a second: smooth to read, and a quarter of the frames at 60p.
    private static let scopePeriod: CFTimeInterval = 0.066

    /// Samples the luma plane on the shared scope grid, the way the Android
    /// app's scope.frag does, and bins it with the shared `Scopes`.
    private func sampleScopes(_ buffer: CVPixelBuffer?, _ p: Params) {
        guard p.settings.tools.scope, let buffer else {
            if scopeShown && !p.settings.tools.scope {
                scopeShown = false
                let callback = onScopes
                DispatchQueue.main.async { callback?(nil) }
            }
            return
        }
        let now = CACurrentMediaTime()
        guard now - lastScope >= Self.scopePeriod else { return }
        lastScope = now

        let b = Bridge.shared
        let gw = Int(b.scopeGridWidth)
        let gh = Int(b.scopeGridHeight)
        var codes = Data(count: gw * gh)
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        guard let base = CVPixelBufferGetBaseAddressOfPlane(buffer, 0) else {
            CVPixelBufferUnlockBaseAddress(buffer, .readOnly)
            return
        }
        let width = CVPixelBufferGetWidthOfPlane(buffer, 0)
        let height = CVPixelBufferGetHeightOfPlane(buffer, 0)
        let stride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0)
        let src = base.assumingMemoryBound(to: UInt8.self)
        // Codes as they are, no filtering, so a clipped highlight reads as clipped.
        codes.withUnsafeMutableBytes { raw in
            let out = raw.bindMemory(to: UInt8.self)
            for gy in 0..<gh {
                let row = src + ((2 * gy + 1) * height / (2 * gh)) * stride
                for gx in 0..<gw {
                    out[gy * gw + gx] = row[(2 * gx + 1) * width / (2 * gw)]
                }
            }
        }
        CVPixelBufferUnlockBaseAddress(buffer, .readOnly)

        let table = ireTable(p)
        let reading: ScopeReading
        switch p.settings.tools.scopeKind {
        case .histogram:
            let h = b.histogram(codes: codes, ire: table)
            reading = ScopeReading(
                kind: .histogram,
                bins: b.bins(histogram: h).int32s,
                peak: Int(h.peak),
                crushed: h.crushedFraction,
                clipped: h.clippedFraction,
                waveform: nil
            )
        case .waveform:
            let w = b.waveform(codes: codes, width: Int32(gw), height: Int32(gh), ire: table)
            reading = ScopeReading(
                kind: .waveform,
                bins: [],
                peak: 1,
                crushed: 0,
                clipped: 0,
                waveform: ScopeReading.waveformImage(cells: b.cells(waveform: w).int32s, samplesPerColumn: Int(w.samplesPerColumn))
            )
        }
        scopeShown = true
        let callback = onScopes
        DispatchQueue.main.async { callback?(reading) }
    }

    /// The scope's code-to-IRE table, built again only when the signal or its range changes.
    private func ireTable(_ p: Params) -> KotlinFloatArray {
        let key = "\(p.lumaOffset)/\(p.lumaScale)/\(p.settings.tools.signal.rawValue)"
        if key != ireKey || ireTable == nil {
            ireTable = Bridge.shared.ireTable(lumaOffset: p.lumaOffset, lumaScale: p.lumaScale, transfer: p.settings.tools.signal.rawValue)
            ireKey = key
        }
        return ireTable!
    }

    // MARK: - Settings, worked out once per change

    /// [Settings] turned into the shader's numbers, once when they change
    /// rather than every frame, since each comes from the shared module.
    private struct Params {
        let settings: Settings
        var uniforms = MonitorUniforms()
        var lumaOffset: Float = 0
        var lumaScale: Float = 1
        var keep: Float = 0
        var noise: Float = 0

        init(_ settings: Settings) {
            self.settings = settings
            let t = settings.tools
            let c = settings.colorSpec.effective(for: t.signal).constants
            uniforms.yuvToRgb = c.matrix
            uniforms.offset = c.offset
            uniforms.scale = c.scale
            let pc = t.peakingColor.rgb
            uniforms.peakColor = SIMD3(Float((pc >> 16) & 0xFF) / 255, Float((pc >> 8) & 0xFF) / 255, Float(pc & 0xFF) / 255)
            // Punch-in: the middle half of the picture at 2×.
            uniforms.crop = t.punchIn ? SIMD4(0.25, 0.25, 0.5, 0.5) : SIMD4(0, 0, 1, 1)
            uniforms.zebraLevel = t.zebras ? Float(t.zebraLevel) / 100 : 2
            uniforms.peakThreshold = t.peaking ? t.peakingSensitivity.threshold : -1
            uniforms.transfer = t.signal.ordinal
            uniforms.falseColor = t.falseColor ? 1 : 0
            lumaOffset = c.offset.x
            lumaScale = c.scale.x
            keep = t.noiseReduction.keep
            noise = t.noiseReduction.noise
        }
    }
}

extension Data {
    /// The bytes read as native-endian Int32s, as the shared module's int arrays cross over.
    var int32s: [Int32] {
        withUnsafeBytes { Array($0.bindMemory(to: Int32.self)) }
    }
}
