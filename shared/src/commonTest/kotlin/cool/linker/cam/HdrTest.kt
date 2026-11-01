package cool.linker.cam

import cool.linker.cam.model.Hdr
import cool.linker.cam.model.Transfer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HdrTest {
    private fun near(expected: Float, actual: Float, tolerance: Float) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, got $actual")

    @Test fun pqRoundTripsAndHlgMatchesPq() {
        // BT.2408: 203 nits is 58% PQ, and 75% HLG on a 1000-nit display.
        near(203f, Hdr.pqToNits(0.58f), 3f)
        near(10_000f, Hdr.pqToNits(1f), 1f)
        near(0f, Hdr.pqToNits(0f), 0.001f)
        for (nits in listOf(0.1f, 1f, 26f, 100f, 1000f)) near(nits, Hdr.pqToNits(Hdr.nitsToPq(nits)), nits * 0.002f)
        val white = Hdr.toSdr(0.75f, 0.75f, 0.75f, Transfer.HLG)
        val pqWhite = Hdr.toSdr(0.58f, 0.58f, 0.58f, Transfer.PQ)
        near(pqWhite[0], white[0], 0.01f)
    }

    @Test fun theRollOffLeavesShadowsAloneAndLandsThePeakOnWhite() {
        near(0.1f, Hdr.toneMap(0.1f), 1e-6f)
        near(0.28f, Hdr.KNEE, 0.01f)
        var last = 0f
        for (i in 1..400) {
            val y = Hdr.toneMap(i / 40f)
            assertTrue(y >= last && y <= 1f, "not monotonic at ${i / 40f}")
            last = y
        }
        // The camera's brightest lands on white, not short of it.
        near(1f, Hdr.toneMap(Hdr.SOURCE_PEAK_NITS / Hdr.SDR_WHITE_NITS), 1e-3f)
        // No kink at the knee.
        val d = 1e-3f
        near(1f, (Hdr.toneMap(Hdr.KNEE + d) - Hdr.toneMap(Hdr.KNEE)) / d, 0.02f)
    }

    @Test fun levelsLandWhereResolvePutsThem() {
        fun shown(nits: Float) = Hdr.displayLuma(Hdr.nitsToPq(nits), Transfer.PQ)
        // Below the knee, nits / 100 through gamma 2.4, as Resolve's Rec.709 Gamma 2.4 output.
        near((10f / 100f).pow(1f / 2.4f), shown(10f), 0.002f)
        near(0.57f, shown(26f), 0.01f)
        // 100 nits and reference white roll off just short of white; 1000 nits is white.
        near(0.86f, shown(100f), 0.015f)
        near(0.95f, shown(203f), 0.015f)
        near(1f, shown(1000f), 0.002f)
        near(0f, shown(0f), 1e-3f)
    }

    @Test fun greysStayGrey() {
        val grey = Hdr.toSdr(0.38f, 0.38f, 0.38f, Transfer.PQ)
        near(grey[0], grey[1], 1e-4f)
        near(grey[1], grey[2], 1e-4f)
    }

    @Test fun saturatedColoursComeBackInGamutAndBrightOnesWhiten() {
        // Pure BT.2020 green is outside BT.709; it should come out a strong green, not clipped garbage.
        val green = Hdr.toSdr(0f, 0.58f, 0f, Transfer.PQ)
        assertTrue(green.all { it in 0f..1f })
        assertTrue(green[1] > green[0] && green[1] > green[2])
        // A muted colour gains saturation: BT.2020 shown as 709 is what looked washed out.
        val shown = Hdr.toSdr(0.50f, 0.45f, 0.40f, Transfer.PQ)
        assertTrue(shown[0] - shown[2] > 0f)
        // A very bright red light runs toward white instead of staying a flat red.
        val dim = Hdr.toSdr(0.5f, 0.3f, 0.3f, Transfer.PQ)
        val bright = Hdr.toSdr(0.8f, 0.6f, 0.6f, Transfer.PQ)
        assertTrue(bright[0] - bright[2] < dim[0] - dim[2])
    }

    @Test fun sdrPassesThrough() {
        assertEquals(listOf(0.2f, 0.5f, 0.9f), Hdr.toSdr(0.2f, 0.5f, 0.9f, Transfer.SDR).toList())
    }
}
