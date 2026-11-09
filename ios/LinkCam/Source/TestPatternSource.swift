import CoreVideo
import Foundation
import LinkCamShared

/// The built-in test signal, the shared module's `TestPattern`: 75% bars over
/// a 0–100 IRE ramp with a white square sweeping across, as BT.709 limited
/// range at 1080p60. The still part is drawn once; each frame copies it into
/// a pooled pixel buffer and draws the square on top.
final class TestPatternSource: FrameSource, @unchecked Sendable {
    let name = "Test pattern"
    let format: SourceFormatInfo
    var formats: [SourceFormatInfo] { [format] }
    let defaultColorSpec = ColorSpecChoice.hdVideo

    private let width: Int
    private let height: Int
    private let luma: Data
    private let chroma: Data
    private let white: [UInt8]
    private let queue = DispatchQueue(label: "cool.linker.cam.testpattern", qos: .userInteractive)
    private var timer: DispatchSourceTimer?
    private var pool: CVPixelBufferPool?
    private var frame: Int32 = 0
    private let counters = FrameCounters()

    init() {
        let b = Bridge.shared
        width = Int(b.testPatternWidth)
        height = Int(b.testPatternHeight)
        format = SourceFormatInfo(width: width, height: height, fps: Double(b.testPatternFps), codec: "I420")
        luma = b.testPatternLuma()
        chroma = b.testPatternChroma()
        white = b.testPatternWhite.map { UInt8($0.intValue) }
    }

    func start(onFrame: @escaping (CVPixelBuffer) -> Void, onProblem: @escaping (String?) -> Void) {
        guard timer == nil else { return }
        let attributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
            kCVPixelBufferMetalCompatibilityKey as String: true,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any],
        ]
        CVPixelBufferPoolCreate(nil, nil, attributes as CFDictionary, &pool)
        let t = DispatchSource.makeTimerSource(flags: .strict, queue: queue)
        t.schedule(deadline: .now(), repeating: 1.0 / Double(Bridge.shared.testPatternFps), leeway: .milliseconds(1))
        t.setEventHandler { [weak self] in
            guard let self, let buffer = self.render() else { return }
            self.counters.received()
            onFrame(buffer)
        }
        timer = t
        t.resume()
    }

    func stop() {
        timer?.cancel()
        timer = nil
    }

    func select(_ format: SourceFormatInfo) {}

    func stats() -> SourceStats { counters.snapshot }

    private func render() -> CVPixelBuffer? {
        guard let pool else { return nil }
        var out: CVPixelBuffer?
        guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &out) == kCVReturnSuccess, let buffer = out else { return nil }
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }

        let b = Bridge.shared
        let size = Int(b.testPatternSquare)
        let left = Int(b.testPatternSquareLeft(frame: frame))
        let top = Int(b.testPatternSquareTop)
        frame &+= 1

        // Plane 0: Y, one byte a pixel.
        if let base = CVPixelBufferGetBaseAddressOfPlane(buffer, 0) {
            let stride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0)
            let rows = base.assumingMemoryBound(to: UInt8.self)
            luma.withUnsafeBytes { src in
                for y in 0..<height {
                    memcpy(rows + y * stride, src.baseAddress! + y * width, width)
                }
            }
            for y in top..<(top + size) {
                memset(rows + y * stride + left, Int32(white[0]), size)
            }
        }
        // Plane 1: Cb and Cr interleaved, at half size each way.
        if let base = CVPixelBufferGetBaseAddressOfPlane(buffer, 1) {
            let stride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 1)
            let rows = base.assumingMemoryBound(to: UInt8.self)
            let rowBytes = width // width / 2 pairs of two bytes
            chroma.withUnsafeBytes { src in
                for y in 0..<(height / 2) {
                    memcpy(rows + y * stride, src.baseAddress! + y * rowBytes, rowBytes)
                }
            }
            for y in (top / 2)..<((top + size) / 2) {
                let row = rows + y * stride
                for x in (left / 2)..<((left + size) / 2) {
                    row[2 * x] = white[1]
                    row[2 * x + 1] = white[2]
                }
            }
        }
        return buffer
    }
}
