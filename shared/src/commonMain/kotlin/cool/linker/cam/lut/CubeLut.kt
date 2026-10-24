package cool.linker.cam.lut

/**
 * A 3D LUT read from an Adobe/Resolve `.cube` file.
 *
 * [data] holds `size³` RGB triples with red changing fastest, which is both
 * the order the file lists them in and the order a 3D texture upload expects.
 */
class CubeLut(
    val title: String?,
    val size: Int,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    val data: FloatArray,
) {
    init {
        require(data.size == size * size * size * 3)
    }

    /** RGBA with alpha 1, the layout an RGBA16F texture upload takes. */
    fun toRgba(): FloatArray {
        val out = FloatArray(size * size * size * 4)
        for (i in 0 until size * size * size) {
            out[i * 4] = data[i * 3]
            out[i * 4 + 1] = data[i * 3 + 1]
            out[i * 4 + 2] = data[i * 3 + 2]
            out[i * 4 + 3] = 1f
        }
        return out
    }
}

class CubeParseException(val line: Int, message: String) : Exception("line $line: $message")

object CubeParser {
    /** Grading LUTs are 17, 33 or 65 points; anything past this is a broken file. */
    private const val MAX_SIZE = 129

    fun parse(text: String): CubeLut {
        var title: String? = null
        var size = 0
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        var data: FloatArray? = null
        var count = 0

        text.lineSequence().forEachIndexed { index, raw ->
            val lineNumber = index + 1
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed

            val keyword = line.substringBefore(' ').substringBefore('\t')
            when {
                keyword == "TITLE" -> title = line.removePrefix("TITLE").trim().trim('"')
                keyword == "LUT_1D_SIZE" -> throw CubeParseException(
                    lineNumber, "this is a 1D LUT; only 3D LUTs are supported",
                )
                keyword == "LUT_3D_SIZE" -> {
                    if (data != null) throw CubeParseException(lineNumber, "LUT_3D_SIZE given twice")
                    val n = line.removePrefix("LUT_3D_SIZE").trim().toIntOrNull()
                        ?: throw CubeParseException(lineNumber, "LUT_3D_SIZE needs a whole number")
                    if (n < 2 || n > MAX_SIZE) {
                        throw CubeParseException(lineNumber, "LUT_3D_SIZE $n is outside 2..$MAX_SIZE")
                    }
                    size = n
                    data = FloatArray(n * n * n * 3)
                }
                keyword == "DOMAIN_MIN" -> domainMin = floats(line.removePrefix("DOMAIN_MIN"), lineNumber)
                keyword == "DOMAIN_MAX" -> domainMax = floats(line.removePrefix("DOMAIN_MAX"), lineNumber)
                keyword.first().isLetter() -> Unit // Vendor keywords we don't use (e.g. LUT_IN_VIDEO_RANGE).
                else -> {
                    val values = data
                        ?: throw CubeParseException(lineNumber, "values before LUT_3D_SIZE")
                    if (count >= size * size * size) {
                        throw CubeParseException(lineNumber, "more than $size³ entries")
                    }
                    floats(line, lineNumber).copyInto(values, count * 3)
                    count++
                }
            }
        }

        val values = data ?: throw CubeParseException(0, "no LUT_3D_SIZE")
        if (count != size * size * size) {
            throw CubeParseException(0, "expected ${size * size * size} entries, found $count")
        }
        for (c in 0 until 3) {
            if (domainMax[c] <= domainMin[c]) throw CubeParseException(0, "DOMAIN_MAX must exceed DOMAIN_MIN")
        }
        return CubeLut(title, size, domainMin, domainMax, values)
    }

    private fun floats(s: String, line: Int): FloatArray {
        val parts = s.trim().split(' ', '\t').filter { it.isNotEmpty() }
        if (parts.size != 3) throw CubeParseException(line, "expected 3 numbers, found ${parts.size}")
        return FloatArray(3) { i ->
            parts[i].toFloatOrNull() ?: throw CubeParseException(line, "\"${parts[i]}\" is not a number")
        }
    }
}
