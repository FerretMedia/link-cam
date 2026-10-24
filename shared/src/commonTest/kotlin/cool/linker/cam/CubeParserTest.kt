package cool.linker.cam

import cool.linker.cam.lut.CubeParseException
import cool.linker.cam.lut.CubeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CubeParserTest {
    /** An identity LUT the way Resolve writes one: header comments, a title, CRLF. */
    private fun identity(size: Int, extraHeader: String = ""): String = buildString {
        append("# Created by a test\r\n")
        append("TITLE \"Identity\"\r\n")
        append(extraHeader)
        append("LUT_3D_SIZE $size\r\n\r\n")
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val d = (size - 1).toFloat()
            append("${r / d} ${g / d} ${b / d}\r\n")
        }
    }

    @Test
    fun readsAnIdentityLutWithRedFastest() {
        val lut = CubeParser.parse(identity(3))
        assertEquals("Identity", lut.title)
        assertEquals(3, lut.size)
        // Entry 1 is r=1/2, g=0, b=0.
        assertEquals(0.5f, lut.data[3])
        assertEquals(0f, lut.data[4])
        // The last entry is white.
        assertEquals(1f, lut.data[lut.data.size - 1])
    }

    @Test
    fun acceptsDomainAndVendorKeywords() {
        val lut = CubeParser.parse(
            identity(2, "DOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\nLUT_IN_VIDEO_RANGE\n"),
        )
        assertEquals(2, lut.size)
    }

    @Test
    fun rejectsA1dLutWithAReason() {
        val e = assertFailsWith<CubeParseException> { CubeParser.parse("LUT_1D_SIZE 1024\n") }
        assertTrue(e.message!!.contains("1D"))
    }

    @Test
    fun pointsAtTheBrokenLine() {
        val text = "LUT_3D_SIZE 2\n0 0 0\n1 0\n"
        val e = assertFailsWith<CubeParseException> { CubeParser.parse(text) }
        assertEquals(3, e.line)
    }

    @Test
    fun rejectsATruncatedFile() {
        assertFailsWith<CubeParseException> { CubeParser.parse("LUT_3D_SIZE 2\n0 0 0\n") }
    }

    @Test
    fun rgbaHasOpaqueAlpha() {
        val rgba = CubeParser.parse(identity(2)).toRgba()
        assertEquals(2 * 2 * 2 * 4, rgba.size)
        assertEquals(1f, rgba[3])
    }
}
