import CoreVideo
import Foundation

/// Anything that produces pictures for the monitor: a capture device plugged
/// into the iPad, or the built-in test pattern.
///
/// Every source delivers bi-planar 4:2:0 pixel buffers (a Y plane and an
/// interleaved CbCr plane), which the renderer maps straight into Metal
/// textures. The renderer doesn't know which source it's drawing, which is
/// what lets every tool work on all of them.
protocol FrameSource: AnyObject {
    var name: String { get }
    var formats: [SourceFormatInfo] { get }
    var format: SourceFormatInfo { get }
    var defaultColorSpec: ColorSpecChoice { get }

    /// Begins producing frames. [onFrame] runs on the source's own queue.
    func start(onFrame: @escaping (CVPixelBuffer) -> Void, onProblem: @escaping (String?) -> Void)
    func stop()
    func select(_ format: SourceFormatInfo)
    func stats() -> SourceStats
}

struct SourceStats {
    var received = 0
    var dropped = 0
}

/// Frame counters, written on a source's queue and read by the status strip.
final class FrameCounters: @unchecked Sendable {
    private let lock = NSLock()
    private var value = SourceStats()

    func received() { lock.withLock { value.received += 1 } }
    func dropped() { lock.withLock { value.dropped += 1 } }
    func reset() { lock.withLock { value = SourceStats() } }
    var snapshot: SourceStats { lock.withLock { value } }
}
