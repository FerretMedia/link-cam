package cool.linker.cam

import cool.linker.cam.model.Hdr
import cool.linker.cam.model.Transfer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HdrTest {
    private fun near(expected: Float, actual: Float, tolerance: Float) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, got $actual")

    @Test fun pqAndHlgPutReferenceWhiteAt203Nits() {
        // BT.2408: 58% PQ, and 75% HLG on a 1000-nit display.
        near(203f, Hdr.pqToNits(0.58f), 3f)
        near(10_000f, Hdr.pqToNits(1f), 1f)
        near(0f, Hdr.pqToNits(0f), 0.001f)
        val white = Hdr.toSdr(0.75f, 0.75f, 0.75f, Transfer.HLG)
        val pqWhite = Hdr.toSdr(0.58f, 0.58f, 0.58f, Transfer.PQ)
        near(pqWhite[0], white[0], 0.01f)
    }

    @Test fun theShoulderLeavesMidtonesAloneAndNeverClips() {
        near(0.18f, Hdr.toneMap(0.18f), 1e-6f)
        near(Hdr.KNEE, Hdr.toneMap(Hdr.KNEE), 1e-6f)
        var last = 0f
        for (i in 1..400) {
            val y = Hdr.toneMap(i / 20f)
            assertTrue(y >= last && y < 1f)
            last = y
        }
        // Reference white lands bright but with room above it for highlights.
        near(0.8f, Hdr.toneMap(1f), 1e-6f)
    }

    @Test fun greysStayGreyAndWhiteReadsLikeWhite() {
        val grey = Hdr.toSdr(0.38f, 0.38f, 0.38f, Transfer.PQ)
        near(grey[0], grey[1], 1e-4f)
        near(grey[1], grey[2], 1e-4f)
        // Reference white shows near 90%, where normal footage puts white.
        near(0.91f, Hdr.displayLuma(0.58f, Transfer.PQ), 0.02f)
        // Black stays black.
        near(0f, Hdr.displayLuma(0f, Transfer.PQ), 1e-4f)
    }

    @Test fun saturatedColoursComeBackInGamut() {
        // Pure BT.2020 green is outside BT.709; it should come out a strong green, not clipped garbage.
        val green = Hdr.toSdr(0f, 0.58f, 0f, Transfer.PQ)
        assertTrue(green.all { it in 0f..1f })
        assertTrue(green[1] > green[0] && green[1] > green[2])
        // A muted colour gains saturation: BT.2020 shown as 709 is what looked washed out.
        val muted = floatArrayOf(0.50f, 0.45f, 0.40f)
        val shown = Hdr.toSdr(muted[0], muted[1], muted[2], Transfer.PQ)
        assertTrue(shown[0] - shown[2] > 0f)
    }

    @Test fun sdrPassesThrough() {
        assertEquals(listOf(0.2f, 0.5f, 0.9f), Hdr.toSdr(0.2f, 0.5f, 0.9f, Transfer.SDR).toList())
    }
}
