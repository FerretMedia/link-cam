package cool.linker.cam

import cool.linker.cam.pattern.TestPattern
import kotlin.test.Test
import kotlin.test.assertEquals

class TestPatternTest {
    private val planes = TestPattern.base()
    private val w = TestPattern.WIDTH

    private fun luma(x: Int, y: Int) = planes.y[y * w + x].toInt() and 0xFF

    @Test
    fun rampRunsFromLimitedBlackToLimitedWhite() {
        val bottom = TestPattern.HEIGHT - 1
        assertEquals(16, luma(0, bottom))
        assertEquals(235, luma(w - 1, bottom))
    }

    @Test
    fun firstBarIsSeventyFivePercentGrey() {
        // 75% of the 219 codes between limited black and white.
        assertEquals(16 + 164, luma(10, 10))
        assertEquals(128, planes.cb[0].toInt() and 0xFF)
        assertEquals(128, planes.cr[0].toInt() and 0xFF)
    }

    @Test
    fun squareSweepsAcrossAndBackInsideThePicture() {
        assertEquals(0, TestPattern.squareLeft(0))
        assertEquals(w - TestPattern.SQUARE, TestPattern.squareLeft(TestPattern.SWEEP_FRAMES / 2))
        assertEquals(0, TestPattern.squareLeft(TestPattern.SWEEP_FRAMES))
        for (f in 0 until TestPattern.SWEEP_FRAMES) {
            val left = TestPattern.squareLeft(f)
            check(left in 0..w - TestPattern.SQUARE) { "frame $f: $left" }
        }
        assertEquals(0, TestPattern.SQUARE_TOP % 2)
    }
}
