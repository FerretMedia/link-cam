package cool.linker.cam.source

import android.hardware.usb.UsbDeviceConnection
import cool.linker.cam.model.SourceFormat
import cool.linker.cam.uvc.UvcDevice

/**
 * A UVC capture device as a [FrameSource].
 *
 * The GL thread and the UI thread both touch the native device: the GL thread
 * to take frames, the UI thread to close it when the dongle is unplugged. The
 * lock keeps a close from landing between [acquire] and [upload].
 */
class UvcSource(
    private val device: UvcDevice,
    private val connection: UsbDeviceConnection,
    override val format: SourceFormat,
) : FrameSource {
    private val lock = Any()
    private var closed = false
    private val dims = IntArray(8)
    private val times = LongArray(2)

    override fun start(onFrame: () -> Unit) = device.start(format) { onFrame() }

    override fun stop() = synchronized(lock) { if (!closed) device.stop() }

    override fun acquire(into: FrameInfo): Boolean = synchronized(lock) {
        if (closed || !device.acquire(dims, times)) return@synchronized false
        for (c in 0..2) {
            into.planeWidth[c] = dims[c * 2]
            into.planeHeight[c] = dims[c * 2 + 1]
        }
        into.captureNanos = times[0]
        into.decodeMicros = times[1]
        true
    }

    override fun upload(y: Int, cb: Int, cr: Int) = synchronized(lock) {
        if (!closed) device.upload(y, cb, cr)
    }

    override fun stats(): SourceStats = synchronized(lock) {
        if (closed) {
            SourceStats()
        } else {
            val s = device.stats()
            SourceStats(s.received, s.dropped, s.decodeErrors)
        }
    }

    /**
     * Restarts the same device in another [format]. This source hands the
     * device over to the one returned and must not be used again.
     */
    fun switchTo(format: SourceFormat): UvcSource = synchronized(lock) {
        check(!closed) { "source is closed" }
        device.stop()
        closed = true
        UvcSource(device, connection, format)
    }

    fun formats(): List<SourceFormat> = synchronized(lock) { if (closed) emptyList() else device.formats() }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            device.close()
            connection.close()
        }
    }
}
