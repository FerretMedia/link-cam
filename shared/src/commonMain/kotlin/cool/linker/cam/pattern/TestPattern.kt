package cool.linker.cam.pattern

import cool.linker.cam.model.ColorSpec
import cool.linker.cam.model.SourceFormat

/**
 * The built-in test signal, the same on every platform: a 1080p60 picture
 * delivered the way a capture device would, as BT.709 limited-range 4:2:0.
 *
 * Top two thirds: 75% colour bars. Bottom third: a luma ramp from 0 to 100
 * IRE, which is how to check that zebras and false color fire at the right
 * levels. A white square moves across the bars, so there is motion and a
 * sharp edge for peaking. The apps copy [base] into each frame and draw the
 * square over it.
 */
object TestPattern {
    const val WIDTH = 1920
    const val HEIGHT = 1080
    const val FPS = 60

    /** The moving square's side, in pixels. */
    const val SQUARE = 120

    /** Frames for the square to sweep across and back: 4 s at 60p. */
    const val SWEEP_FRAMES = 240

    val format = SourceFormat(SourceFormat.Codec.I420, WIDTH, HEIGHT, FPS.toDouble())
    val spec: ColorSpec = format.defaultColorSpec

    /** Y at full size, Cb and Cr at half size in both directions, row by row from the top. */
    class Planes(val y: ByteArray, val cb: ByteArray, val cr: ByteArray)

    fun base(): Planes {
        val chromaW = WIDTH / 2
        val chromaH = HEIGHT / 2
        val y = ByteArray(WIDTH * HEIGHT)
        val cb = ByteArray(chromaW * chromaH)
        val cr = ByteArray(chromaW * chromaH)
        val barsBottom = HEIGHT * 2 / 3
        val bars = listOf(
            floatArrayOf(0.75f, 0.75f, 0.75f), // white
            floatArrayOf(0.75f, 0.75f, 0f), // yellow
            floatArrayOf(0f, 0.75f, 0.75f), // cyan
            floatArrayOf(0f, 0.75f, 0f), // green
            floatArrayOf(0.75f, 0f, 0.75f), // magenta
            floatArrayOf(0.75f, 0f, 0f), // red
            floatArrayOf(0f, 0f, 0.75f), // blue
        ).map { spec.encode(it[0], it[1], it[2]) }
        val ramp = Array(WIDTH) { x ->
            val level = x.toFloat() / (WIDTH - 1)
            spec.encode(level, level, level)
        }
        for (row in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val code = if (row < barsBottom) bars[x * bars.size / WIDTH] else ramp[x]
                y[row * WIDTH + x] = code[0].toByte()
                if (x % 2 == 0 && row % 2 == 0) {
                    val i = (row / 2) * chromaW + x / 2
                    cb[i] = code[1].toByte()
                    cr[i] = code[2].toByte()
                }
            }
        }
        return Planes(y, cb, cr)
    }

    /** The square's Y, Cb and Cr codes. */
    val white: IntArray get() = spec.encode(1f, 1f, 1f)

    /** Where the square's left edge is in frame [frame]: it sweeps across the bars and back. */
    fun squareLeft(frame: Int): Int {
        val half = SWEEP_FRAMES / 2
        val phase = frame % SWEEP_FRAMES
        return (if (phase < half) phase else SWEEP_FRAMES - phase) * (WIDTH - SQUARE) / half
    }

    /** The square's top edge, centred on the bars. Even, so its chroma lines up. */
    const val SQUARE_TOP = HEIGHT / 3 - SQUARE / 2
}
