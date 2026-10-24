package cool.linker.cam.model

/**
 * How to turn a source's Y'CbCr code values into R'G'B'.
 *
 * Every source declares one, because the same bytes mean different colours
 * depending on it. MJPEG from a capture dongle is JFIF, so BT.601 full range;
 * uncompressed YUY2 from the same dongle is usually BT.709 limited range. Read
 * one as the other and the picture shifts in level and hue, and every exposure
 * tool reads wrong with it. The user can override both fields when a source
 * gets it wrong.
 */
data class ColorSpec(val matrix: Matrix, val range: Range) {

    enum class Matrix(val kr: Float, val kb: Float, val label: String) {
        BT601(0.299f, 0.114f, "601"),
        BT709(0.2126f, 0.0722f, "709"),
        BT2020(0.2627f, 0.0593f, "2020");

        val kg: Float get() = 1f - kr - kb
    }

    enum class Range(val label: String) { FULL("full"), LIMITED("limited") }

    val label: String get() = "${matrix.label}/${range.label}"

    /**
     * The shader's half of the conversion: `rgb = matrix * ((yuv - offset) * scale)`,
     * with yuv sampled from 8-bit textures as 0..1.
     *
     * [matrix] is column-major, the order `glUniformMatrix3fv` takes without
     * transposing.
     */
    fun shaderConstants(): ShaderConstants {
        val kr = matrix.kr
        val kb = matrix.kb
        val kg = matrix.kg
        // R = Y + 2(1-Kr)·Cr, B = Y + 2(1-Kb)·Cb, and G solved from the luma equation.
        val crToR = 2f * (1f - kr)
        val cbToB = 2f * (1f - kb)
        val cbToG = -cbToB * kb / kg
        val crToG = -crToR * kr / kg
        val columns = floatArrayOf(
            1f, 1f, 1f,        // Y column
            0f, cbToG, cbToB,  // Cb column
            crToR, crToG, 0f,  // Cr column
        )
        return when (range) {
            Range.FULL -> ShaderConstants(
                matrix = columns,
                offset = floatArrayOf(0f, 128f / 255f, 128f / 255f),
                scale = floatArrayOf(1f, 1f, 1f),
            )
            Range.LIMITED -> ShaderConstants(
                matrix = columns,
                offset = floatArrayOf(16f / 255f, 128f / 255f, 128f / 255f),
                scale = floatArrayOf(255f / 219f, 255f / 224f, 255f / 224f),
            )
        }
    }

    /**
     * Normalised luma (0 = black, 1 = white) for an 8-bit Y code. Limited range
     * goes below 0 and above 1 for sub-blacks and super-whites, which the
     * exposure tools are meant to see.
     */
    fun luma(yCode: Int): Float = when (range) {
        Range.FULL -> yCode / 255f
        Range.LIMITED -> (yCode - 16) / 219f
    }

    /** The CPU reference for the shader's conversion, for tests and golden images. */
    fun toRgb(yCode: Int, cbCode: Int, crCode: Int): FloatArray {
        val c = shaderConstants()
        val y = (yCode / 255f - c.offset[0]) * c.scale[0]
        val cb = (cbCode / 255f - c.offset[1]) * c.scale[1]
        val cr = (crCode / 255f - c.offset[2]) * c.scale[2]
        val m = c.matrix
        return floatArrayOf(
            m[0] * y + m[3] * cb + m[6] * cr,
            m[1] * y + m[4] * cb + m[7] * cr,
            m[2] * y + m[5] * cb + m[8] * cr,
        )
    }

    /**
     * The other direction: 8-bit Y, Cb, Cr codes for an R'G'B' colour (0..1).
     * The test pattern is generated with this, so it carries exactly the
     * levels the tools are supposed to read back.
     */
    fun encode(r: Float, g: Float, b: Float): IntArray {
        val y = matrix.kr * r + matrix.kg * g + matrix.kb * b
        val cb = (b - y) / (2f * (1f - matrix.kb))
        val cr = (r - y) / (2f * (1f - matrix.kr))
        fun code(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)
        return when (range) {
            Range.FULL -> intArrayOf(code(y * 255f), code(128f + cb * 255f), code(128f + cr * 255f))
            Range.LIMITED -> intArrayOf(code(16f + y * 219f), code(128f + cb * 224f), code(128f + cr * 224f))
        }
    }

    class ShaderConstants(val matrix: FloatArray, val offset: FloatArray, val scale: FloatArray)

    companion object {
        val JFIF = ColorSpec(Matrix.BT601, Range.FULL)
        val HD_VIDEO = ColorSpec(Matrix.BT709, Range.LIMITED)
    }
}
