package cool.linker.cam.uvc

import cool.linker.cam.model.SourceFormat

class UvcException(message: String) : Exception(message)

/**
 * One open USB Video Class device.
 *
 * Open it with the file descriptor of a `UsbDeviceConnection` and keep that
 * connection open until [close]. Frames are decoded on libuvc's own thread
 * into a triple buffer, so the renderer never waits on the camera: it calls
 * [acquire] to take the newest frame, sizes its textures, then [upload]s.
 * Both of those must run on the GL thread.
 */
class UvcDevice private constructor(private var handle: Long) : AutoCloseable {

    /** Every format, size and frame rate the device offers. */
    fun formats(): List<SourceFormat> {
        val flat = nativeFormats(live())
        return (flat.indices step 4).map { i ->
            SourceFormat(
                codec = SourceFormat.Codec.entries[flat[i]],
                width = flat[i + 1],
                height = flat[i + 2],
                fps = SourceFormat.fpsFromInterval(flat[i + 3]),
            )
        }
    }

    /** Starts streaming [format]. [onFrame] runs on the camera thread after each frame. */
    fun start(format: SourceFormat, onFrame: Runnable) {
        // libuvc matches the rate as `10000000 / interval == fps` in integer
        // maths, so 59.94 must be asked for as 59. Truncating the double, which
        // came from the same division, lands on the same number.
        nativeStart(live(), format.codec.ordinal, format.width, format.height, format.fps.toInt(), onFrame)
    }

    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    /**
     * Takes the newest frame, if one arrived since the last call, and fills
     * [dims] with the plane sizes `[yW, yH, cbW, cbH, crW, crH, _, _]` and
     * [times] with `[captureNanos, decodeMicros]`. GL thread only.
     */
    fun acquire(dims: IntArray, times: LongArray): Boolean = nativeAcquire(live(), dims, times)

    /** Uploads the acquired frame into three R8 textures of the acquired sizes. GL thread only. */
    fun upload(y: Int, cb: Int, cr: Int) = nativeUpload(live(), y, cb, cr)

    data class Stats(val received: Long, val dropped: Long, val decodeErrors: Long)

    fun stats(): Stats {
        val out = LongArray(3)
        nativeStats(live(), out)
        return Stats(out[0], out[1], out[2])
    }

    override fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    private fun live(): Long {
        check(handle != 0L) { "UvcDevice is closed" }
        return handle
    }

    companion object {
        init {
            System.loadLibrary("linkcam_uvc")
        }

        /** @throws UvcException when the device can't be opened as a UVC camera. */
        fun open(fileDescriptor: Int): UvcDevice = UvcDevice(nativeOpen(fileDescriptor))

        @JvmStatic private external fun nativeOpen(fd: Int): Long
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeFormats(handle: Long): IntArray
        @JvmStatic private external fun nativeStart(
            handle: Long, codec: Int, width: Int, height: Int, fps: Int, onFrame: Runnable,
        )
        @JvmStatic private external fun nativeStop(handle: Long)
        @JvmStatic private external fun nativeAcquire(handle: Long, dims: IntArray, times: LongArray): Boolean
        @JvmStatic private external fun nativeUpload(handle: Long, y: Int, cb: Int, cr: Int)
        @JvmStatic private external fun nativeStats(handle: Long, out: LongArray)
    }
}
