package cool.linker.cam.source

import android.opengl.GLES30
import cool.linker.cam.pattern.TestPattern
import java.nio.ByteBuffer
import java.util.concurrent.locks.LockSupport

/**
 * The built-in test signal ([TestPattern], shared with the iPad app), produced
 * on a thread of its own at 60 frames a second.
 */
class TestPatternSource : FrameSource {
    override val format = TestPattern.format

    private val chromaW = WIDTH / 2
    private val chromaH = HEIGHT / 2
    private val base = TestPattern.base().let { arrayOf(it.y, it.cb, it.cr) }

    // Triple buffer, like the native driver's: the generator owns `writing`,
    // the GL thread owns `reading`, and `ready` changes hands under the lock.
    private val slots = Array(3) {
        arrayOf(
            ByteBuffer.allocateDirect(WIDTH * HEIGHT),
            ByteBuffer.allocateDirect(chromaW * chromaH),
            ByteBuffer.allocateDirect(chromaW * chromaH),
        )
    }
    private val captureTimes = LongArray(3)
    private var writing = 0
    private var ready = 1
    private var reading = 2
    private var readyIsFresh = false
    private val swap = Any()

    @Volatile private var running = false
    private var thread: Thread? = null
    private var received = 0L
    private var dropped = 0L

    override fun start(onFrame: () -> Unit) {
        if (running) return
        running = true
        thread = Thread({ generate(onFrame) }, "LinkCamTestPattern").apply { start() }
    }

    private fun generate(onFrame: () -> Unit) {
        val period = 1_000_000_000L / 60
        var next = System.nanoTime()
        var frame = 0
        val white = TestPattern.white
        while (running) {
            val planes = slots[writing]
            for (c in 0..2) {
                planes[c].clear()
                planes[c].put(base[c])
            }
            val size = TestPattern.SQUARE
            val left = TestPattern.squareLeft(frame)
            val top = TestPattern.SQUARE_TOP
            for (y in top until top + size) {
                for (x in left until left + size) planes[0].put(y * WIDTH + x, white[0].toByte())
            }
            for (y in top / 2 until (top + size) / 2) {
                for (x in left / 2 until (left + size) / 2) {
                    planes[1].put(y * chromaW + x, white[1].toByte())
                    planes[2].put(y * chromaW + x, white[2].toByte())
                }
            }
            captureTimes[writing] = System.nanoTime()
            synchronized(swap) {
                val t = writing
                writing = ready
                ready = t
                if (readyIsFresh) dropped++
                readyIsFresh = true
                received++
            }
            onFrame()
            frame++
            next += period
            val wait = next - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait) else next = System.nanoTime()
        }
    }

    override fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    override fun acquire(into: FrameInfo): Boolean {
        synchronized(swap) {
            if (!readyIsFresh) return false
            val t = reading
            reading = ready
            ready = t
            readyIsFresh = false
        }
        into.planeWidth[0] = WIDTH; into.planeHeight[0] = HEIGHT
        into.planeWidth[1] = chromaW; into.planeHeight[1] = chromaH
        into.planeWidth[2] = chromaW; into.planeHeight[2] = chromaH
        into.captureNanos = captureTimes[reading]
        into.decodeMicros = 0
        return true
    }

    override fun upload(y: Int, cb: Int, cr: Int) {
        val planes = slots[reading]
        val textures = intArrayOf(y, cb, cr)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        for (c in 0..2) {
            val w = if (c == 0) WIDTH else chromaW
            val h = if (c == 0) HEIGHT else chromaH
            planes[c].position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[c])
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, planes[c])
        }
    }

    override fun stats() = synchronized(swap) { SourceStats(received, dropped, 0) }

    override fun close() = stop()

    companion object {
        const val WIDTH = TestPattern.WIDTH
        const val HEIGHT = TestPattern.HEIGHT
    }
}
