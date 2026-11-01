package cool.linker.cam

import cool.linker.cam.model.ColorSpec
import cool.linker.cam.tools.Scopes
import kotlin.test.Test
import kotlin.test.assertEquals

class ScopesTest {
    private val limited = ColorSpec.HD_VIDEO.shaderConstants().let { Scopes.ireTable(it.offset[0], it.scale[0]) }

    @Test
    fun limitedRangeWhiteIsClippedAndBlackIsCrushed() {
        val codes = ByteArray(100) { if (it < 25) 16 else 235.toByte() }
        val h = Scopes.histogram(codes, limited)
        assertEquals(25, h.bins.first())
        assertEquals(75, h.bins.last())
        assertEquals(25, h.crushed)
        assertEquals(75, h.clipped)
        assertEquals(0.75f, h.clippedFraction)
    }

    @Test
    fun superWhitesLandInTheTopBin() {
        val h = Scopes.histogram(byteArrayOf(254.toByte()), limited)
        assertEquals(1, h.bins.last())
    }

    @Test
    fun midGreyIsNeitherCrushedNorClipped() {
        // 50 IRE in limited range: 16 + 0.5 × 219.
        val h = Scopes.histogram(ByteArray(10) { 126 }, limited)
        assertEquals(0, h.crushed)
        assertEquals(0, h.clipped)
        assertEquals(10, h.bins[Scopes.step(50f, Scopes.HISTOGRAM_BINS)])
    }

    @Test
    fun peakLeavesOutTheEnds() {
        val codes = ByteArray(100) { if (it < 90) 235.toByte() else 126 }
        assertEquals(10, Scopes.histogram(codes, limited).peak)
    }

    @Test
    fun waveformKeepsLeftAndRightApart() {
        // Black on the left half, white on the right.
        val w = 8
        val h = 2
        val codes = ByteArray(w * h) { if (it % w < w / 2) 16 else 235.toByte() }
        val wave = Scopes.waveform(codes, w, h, limited)
        val top = Scopes.WAVEFORM_LEVELS - 1
        assertEquals(0, wave.count(0, top))
        assertEquals(0, wave.count(Scopes.WAVEFORM_COLUMNS - 1, 0))
        val leftBlack = (0 until Scopes.WAVEFORM_COLUMNS / 2).sumOf { wave.count(it, 0) }
        val rightWhite = (Scopes.WAVEFORM_COLUMNS / 2 until Scopes.WAVEFORM_COLUMNS).sumOf { wave.count(it, top) }
        assertEquals(w * h / 2, leftBlack)
        assertEquals(w * h / 2, rightWhite)
    }
}
