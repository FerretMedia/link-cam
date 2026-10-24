package cool.linker.cam

import cool.linker.cam.model.ColorSpec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorSpecTest {
    private fun assertRgb(expected: FloatArray, actual: FloatArray, tolerance: Float = 0.01f) {
        for (i in 0..2) {
            assertTrue(abs(expected[i] - actual[i]) <= tolerance, "channel $i: ${expected.toList()} vs ${actual.toList()}")
        }
    }

    @Test
    fun limitedRangeBlackAndWhiteLandOnZeroAndOne() {
        val spec = ColorSpec.HD_VIDEO
        assertRgb(floatArrayOf(0f, 0f, 0f), spec.toRgb(16, 128, 128))
        assertRgb(floatArrayOf(1f, 1f, 1f), spec.toRgb(235, 128, 128))
    }

    @Test
    fun fullRangeBlackAndWhiteLandOnZeroAndOne() {
        val spec = ColorSpec.JFIF
        assertRgb(floatArrayOf(0f, 0f, 0f), spec.toRgb(0, 128, 128))
        assertRgb(floatArrayOf(1f, 1f, 1f), spec.toRgb(255, 128, 128))
    }

    @Test
    fun bt709RedDecodesToRed() {
        // 100% red in BT.709 limited range: Y 63, Cb 102, Cr 240.
        assertRgb(floatArrayOf(1f, 0f, 0f), ColorSpec.HD_VIDEO.toRgb(63, 102, 240), tolerance = 0.02f)
    }

    @Test
    fun bt601RedDecodesToRed() {
        // 100% red in JFIF: Y 76, Cb 85, Cr 255.
        assertRgb(floatArrayOf(1f, 0f, 0f), ColorSpec.JFIF.toRgb(76, 85, 255), tolerance = 0.02f)
    }

    @Test
    fun readingJfifAsVideoRangeIsVisiblyWrong() {
        // The reason ColorSpec exists: the same bytes, the wrong interpretation.
        val right = ColorSpec.JFIF.toRgb(0, 128, 128)
        val wrong = ColorSpec.HD_VIDEO.toRgb(0, 128, 128)
        assertTrue(right[0] - wrong[0] > 0.05f)
    }

    @Test
    fun lumaShowsSubBlacksAndSuperWhites() {
        assertEquals(0f, ColorSpec.HD_VIDEO.luma(16))
        assertEquals(1f, ColorSpec.HD_VIDEO.luma(235))
        assertTrue(ColorSpec.HD_VIDEO.luma(254) > 1f)
        assertTrue(ColorSpec.HD_VIDEO.luma(1) < 0f)
    }

    @Test
    fun encodeThenDecodeComesBack() {
        for (spec in listOf(ColorSpec.HD_VIDEO, ColorSpec.JFIF)) {
            val rgb = floatArrayOf(0.75f, 0.75f, 0f) // 75% yellow, a test-pattern bar
            val c = spec.encode(rgb[0], rgb[1], rgb[2])
            assertRgb(rgb, spec.toRgb(c[0], c[1], c[2]), tolerance = 0.02f)
        }
    }
}
