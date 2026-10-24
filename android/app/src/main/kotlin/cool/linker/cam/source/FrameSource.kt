package cool.linker.cam.source

import cool.linker.cam.model.ColorSpec
import cool.linker.cam.model.SourceFormat

/**
 * Anything that produces pictures for the monitor: a UVC capture device, the
 * built-in test pattern and, later, the device's own camera.
 *
 * Every source delivers three 8-bit planes (Y, Cb, Cr) at whatever chroma
 * subsampling it has. The renderer doesn't know which source it's drawing,
 * which is what lets every tool work on all of them.
 */
interface FrameSource {
    val format: SourceFormat
    val colorSpec: ColorSpec get() = format.defaultColorSpec

    /** Begins producing frames. [onFrame] runs on the producer's thread. */
    fun start(onFrame: () -> Unit)

    fun stop()

    /** GL thread. Takes the newest frame, if a new one arrived, and describes it in [into]. */
    fun acquire(into: FrameInfo): Boolean

    /** GL thread. Copies the acquired frame into three R8 textures already sized from [acquire]. */
    fun upload(y: Int, cb: Int, cr: Int)

    fun stats(): SourceStats

    /** Stops and releases everything. The source can't be used afterwards. */
    fun close()
}

class FrameInfo {
    val planeWidth = IntArray(3)
    val planeHeight = IntArray(3)
    var captureNanos = 0L
    var decodeMicros = 0L
}

data class SourceStats(val received: Long = 0, val dropped: Long = 0, val errors: Long = 0)
