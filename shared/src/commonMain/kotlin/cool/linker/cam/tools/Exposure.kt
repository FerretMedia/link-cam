package cool.linker.cam.tools

/**
 * The exposure tools' reference math. The GLSL in the Android app implements
 * the same rules; the numbers live here so there is one place to change them,
 * and so tests can check the shader against something.
 *
 * Levels are in IRE: 0 is black and 100 is white, on the source's own luma.
 */
object Exposure {
    fun ire(luma: Float): Float = luma * 100f
}

/**
 * False color, in the ARRI style most operators already read: grey for the
 * levels that need no attention, colour only where something is happening.
 *
 * The bands below come from ARRI's Rec.709 false color: purple and blue at the
 * bottom for crushed and nearly crushed blacks, green around 18% grey, pink one
 * stop above it where skin usually sits, yellow just under clipping and red at
 * clipping.
 */
object FalseColor {
    data class Band(val fromIre: Float, val toIre: Float, val rgb: Int, val name: String)

    val bands = listOf(
        Band(Float.NEGATIVE_INFINITY, 2.5f, 0x8A2BE2, "Black clip"),
        Band(2.5f, 4f, 0x2A6BFF, "Near black"),
        Band(38f, 42f, 0x27C93F, "18% grey"),
        Band(52f, 56f, 0xFF7EB6, "Skin, +1 stop"),
        Band(97f, 99f, 0xFFE11A, "Near clip"),
        Band(99f, Float.POSITIVE_INFINITY, 0xFF2A2A, "Clip"),
    )

    /** Colour for a level, as 0xRRGGBB. Outside the bands, a grey of the same level. */
    fun rgb(ire: Float): Int {
        bands.firstOrNull { ire >= it.fromIre && ire < it.toIre }?.let { return it.rgb }
        val v = (ire.coerceIn(0f, 100f) / 100f * 255f + 0.5f).toInt()
        return (v shl 16) or (v shl 8) or v
    }

    /** Ramp texture entries. */
    const val RAMP_SIZE = 256

    /**
     * A [RAMP_SIZE]×1 RGBA8 texture, entry i covering IRE i/(RAMP_SIZE-1)·100.
     * The shader looks luma up in this instead of carrying the bands itself.
     *
     * Entry 0 and the last entry are fixed to the clip colours, because with
     * clamp-to-edge sampling they are what anything below 0 or above 100 IRE
     * gets.
     */
    fun ramp(): ByteArray {
        val out = ByteArray(RAMP_SIZE * 4)
        for (i in 0 until RAMP_SIZE) {
            val ire = when (i) {
                0 -> -1f
                RAMP_SIZE - 1 -> 101f
                else -> i * 100f / (RAMP_SIZE - 1)
            }
            val c = rgb(ire)
            out[i * 4] = (c shr 16 and 0xFF).toByte()
            out[i * 4 + 1] = (c shr 8 and 0xFF).toByte()
            out[i * 4 + 2] = (c and 0xFF).toByte()
            out[i * 4 + 3] = 0xFF.toByte()
        }
        return out
    }
}

object Zebra {
    fun fires(ire: Float, level: Int): Boolean = ire >= level
}
