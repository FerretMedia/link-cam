package cool.linker.cam

import cool.linker.cam.model.SourceFormat
import cool.linker.cam.tools.FalseColor
import cool.linker.cam.tools.GuideAspect
import cool.linker.cam.tools.Guides
import cool.linker.cam.tools.Peaking
import cool.linker.cam.tools.PeakingSensitivity
import cool.linker.cam.tools.Zebra
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolsTest {
    @Test
    fun falseColorMarksTheBandsAndGreysTheRest() {
        assertEquals(0x27C93F, FalseColor.rgb(40f)) // 18% grey
        assertEquals(0xFF2A2A, FalseColor.rgb(100f)) // clip
        assertEquals(0x8A2BE2, FalseColor.rgb(-3f)) // sub-black
        assertEquals(0x808080, FalseColor.rgb(50.2f)) // nothing to see: grey of the same level
    }

    @Test
    fun falseColorRampEndsAreTheClipColours() {
        val ramp = FalseColor.ramp()
        assertEquals(FalseColor.RAMP_SIZE * 4, ramp.size)
        val first = (ramp[0].toInt() and 0xFF shl 16) or (ramp[1].toInt() and 0xFF shl 8) or (ramp[2].toInt() and 0xFF)
        val lastIndex = (FalseColor.RAMP_SIZE - 1) * 4
        val last = (ramp[lastIndex].toInt() and 0xFF shl 16) or
            (ramp[lastIndex + 1].toInt() and 0xFF shl 8) or (ramp[lastIndex + 2].toInt() and 0xFF)
        assertEquals(0x8A2BE2, first)
        assertEquals(0xFF2A2A, last)
    }

    @Test
    fun zebraFiresAtItsLevel() {
        assertTrue(Zebra.fires(95f, 95))
        assertFalse(Zebra.fires(94.9f, 95))
    }

    @Test
    fun peakingSeesAHardEdgeAndNotAFlatField() {
        val flat = FloatArray(9) { 0.5f }
        val edge = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f)
        assertFalse(Peaking.fires(flat, PeakingSensitivity.HIGH))
        assertTrue(Peaking.fires(edge, PeakingSensitivity.LOW))
    }

    @Test
    fun widePictureIsPillarboxedInATallerView() {
        val r = Guides.fit(1000f, 1000f, 16f / 9f)
        assertEquals(0f, r.left)
        assertEquals(1000f, r.width)
        assertTrue(abs(r.height - 562.5f) < 0.01f)
    }

    @Test
    fun scopeFrameLineIsLetterboxedInsideA169Picture() {
        val picture = Guides.fit(1920f, 1080f, 16f / 9f)
        val line = Guides.frameLine(picture, GuideAspect.SCOPE)
        assertEquals(1920f, line.width)
        assertTrue(abs(line.height - 1920f / 2.39f) < 0.5f)
    }

    @Test
    fun fpsLabelsReadLikeACameraMenu() {
        assertEquals("60", SourceFormat.fpsLabel(60.0))
        assertEquals("59.94", SourceFormat.fpsLabel(60000.0 / 1001.0))
        assertEquals("30", SourceFormat.fpsLabel(SourceFormat.fpsFromInterval(333333)))
    }
}

class FormatChoiceTest {
    private fun f(codec: SourceFormat.Codec, w: Int, h: Int, fps: Double) = SourceFormat(codec, w, h, fps)

    @Test
    fun prefers1080p60OverA4kStream() {
        val formats = listOf(
            f(SourceFormat.Codec.MJPEG, 3840, 2160, 30.0),
            f(SourceFormat.Codec.MJPEG, 1920, 1080, 60.0),
            f(SourceFormat.Codec.MJPEG, 1920, 1080, 30.0),
            f(SourceFormat.Codec.YUYV, 1280, 720, 60.0),
        )
        assertEquals(f(SourceFormat.Codec.MJPEG, 1920, 1080, 60.0), cool.linker.cam.model.FormatChoice.best(formats))
    }

    @Test
    fun uncompressedWinsATie() {
        val formats = listOf(
            f(SourceFormat.Codec.MJPEG, 1920, 1080, 60.0),
            f(SourceFormat.Codec.YUYV, 1920, 1080, 60.0),
        )
        assertEquals(SourceFormat.Codec.YUYV, cool.linker.cam.model.FormatChoice.best(formats)?.codec)
    }

    @Test
    fun takesTheSmallestWhenEverythingIsLarger() {
        val formats = listOf(f(SourceFormat.Codec.MJPEG, 3840, 2160, 30.0), f(SourceFormat.Codec.MJPEG, 2560, 1440, 30.0))
        assertEquals(2560, cool.linker.cam.model.FormatChoice.best(formats)?.width)
    }
}
