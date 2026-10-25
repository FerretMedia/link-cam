package cool.linker.cam.tools

import cool.linker.cam.model.Hdr
import cool.linker.cam.model.Transfer

/**
 * The exposure scopes: a histogram and a luma waveform.
 *
 * Both are built from a small grid of 8-bit luma codes that the renderer reads
 * back from the GPU a few times a second. [GRID_WIDTH]×[GRID_HEIGHT] samples
 * is plenty for a graph a few hundred pixels wide, and small enough to bin on
 * the CPU in well under a millisecond.
 *
 * Levels are in IRE on the source's own luma, the same scale the zebras and
 * false color use, and are clamped to 0..100: sub-blacks land in the bottom
 * level and super-whites in the top one.
 */
object Scopes {
    const val GRID_WIDTH = 320
    const val GRID_HEIGHT = 180

    const val HISTOGRAM_BINS = 128
    const val WAVEFORM_COLUMNS = 160
    const val WAVEFORM_LEVELS = 128

    /**
     * IRE for each of the 256 luma codes. [lumaOffset] and [lumaScale] are the Y
     * entries of `ColorSpec.shaderConstants()`. For an HDR [transfer], the IRE
     * the code is shown at once converted, so the scope agrees with the
     * picture and with the other tools.
     */
    fun ireTable(lumaOffset: Float, lumaScale: Float, transfer: Transfer = Transfer.SDR): FloatArray =
        FloatArray(256) {
            val y = (it / 255f - lumaOffset) * lumaScale
            Exposure.ire(if (transfer == Transfer.SDR) y else Hdr.displayLuma(y, transfer))
        }

    /** Which of [steps] evenly spaced levels across 0..100 IRE a level falls in. */
    fun step(ire: Float, steps: Int): Int =
        (ire / 100f * (steps - 1) + 0.5f).toInt().coerceIn(0, steps - 1)

    /** The level at the centre of a step, the inverse of [step]. */
    fun ireOf(step: Int, steps: Int): Float = step * 100f / (steps - 1)

    fun histogram(codes: ByteArray, ire: FloatArray): Histogram {
        val binOf = IntArray(256) { step(ire[it], HISTOGRAM_BINS) }
        val bins = IntArray(HISTOGRAM_BINS)
        var crushed = 0
        var clipped = 0
        for (b in codes) {
            val code = b.toInt() and 0xFF
            bins[binOf[code]]++
            val level = ire[code]
            if (level < Exposure.CRUSH_IRE) crushed++ else if (level >= Exposure.CLIP_IRE) clipped++
        }
        return Histogram(bins, codes.size, crushed, clipped)
    }

    /** [codes] is a [width]×[height] grid, row by row, left to right. */
    fun waveform(codes: ByteArray, width: Int, height: Int, ire: FloatArray): Waveform {
        val levelOf = IntArray(256) { step(ire[it], WAVEFORM_LEVELS) }
        val columnOf = IntArray(width) { it * WAVEFORM_COLUMNS / width }
        val cells = IntArray(WAVEFORM_COLUMNS * WAVEFORM_LEVELS)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val level = levelOf[codes[row + x].toInt() and 0xFF]
                cells[level * WAVEFORM_COLUMNS + columnOf[x]]++
            }
        }
        return Waveform(cells, samplesPerColumn = width * height / WAVEFORM_COLUMNS)
    }
}

/** How many samples fall at each level, bin 0 being 0 IRE and the last 100 IRE. */
class Histogram(val bins: IntArray, val total: Int, val crushed: Int, val clipped: Int) {
    val crushedFraction: Float get() = if (total == 0) 0f else crushed.toFloat() / total
    val clippedFraction: Float get() = if (total == 0) 0f else clipped.toFloat() / total

    /**
     * The tallest bin, leaving out the two ends. A blown-out sky piles up in
     * the top bin and would otherwise flatten the rest of the graph; the clip
     * readout reports it instead.
     */
    val peak: Int get() = (1 until bins.size - 1).maxOf { bins[it] }.coerceAtLeast(1)
}

/**
 * Sample counts per column and level, level 0 being 0 IRE. Column 0 is the
 * left edge of the picture.
 */
class Waveform(val cells: IntArray, val samplesPerColumn: Int) {
    fun count(column: Int, level: Int): Int = cells[level * Scopes.WAVEFORM_COLUMNS + column]
}
