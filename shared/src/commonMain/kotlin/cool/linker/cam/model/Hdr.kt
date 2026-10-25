package cool.linker.cam.model

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The transfer function a source's signal was made with. A capture dongle
 * can't say: it passes the camera's signal on as 8-bit video without the HDMI
 * HDR flag, so the user tells Link Cam once.
 */
@Serializable
enum class Transfer(val label: String, val short: String) {
    SDR("Normal (SDR)", "SDR"),
    PQ("HDR PQ", "PQ"),
    HLG("HDR HLG", "HLG"),
}

/** How hard to smooth the picture's grain. [rangeWeight] is the shader's 1/(2σ²) over luma codes as 0..1. */
@Serializable
enum class NoiseReduction(val label: String, val rangeWeight: Float) {
    OFF("Off", 0f),
    LOW("Low", 1f / (2f * 0.025f * 0.025f)),
    HIGH("High", 1f / (2f * 0.05f * 0.05f)),
}

/**
 * Shows an HDR signal the way normal footage looks: the reference for the
 * monitor shader's HDR path, which tests check this against.
 *
 * Steps, each from BT.2100 or BT.2408:
 * 1. Undo the transfer function, to light in nits. PQ is absolute; HLG goes
 *    through its OOTF for a 1000-nit display.
 * 2. Scale so HDR reference white, 203 nits, is 1.0: where normal footage
 *    puts white.
 * 3. Convert BT.2020 primaries to BT.709, which is what makes the colours
 *    full again.
 * 4. Tone-map luminance: untouched to [KNEE], then a soft shoulder that
 *    approaches 1.0 however bright the highlight. Colours keep their hue,
 *    and any that land out of gamut are desaturated toward their own grey
 *    rather than clipped per channel, which would shift hue.
 * 5. Encode for an SDR display, gamma 2.4 as in BT.1886.
 */
object Hdr {
    const val REFERENCE_WHITE_NITS = 203f

    /** Where the shoulder starts, in SDR linear light: everything below is shown as is. */
    const val KNEE = 0.6f

    private const val M1 = 0.1593017578125f
    private const val M2 = 78.84375f
    private const val C1 = 0.8359375f
    private const val C2 = 18.8515625f
    private const val C3 = 18.6875f

    private const val HLG_A = 0.17883277f
    private const val HLG_B = 0.28466892f
    private const val HLG_C = 0.55991073f
    private const val HLG_PEAK_NITS = 1000f
    private const val HLG_GAMMA = 1.2f

    /** ST 2084: a PQ signal value, 0..1, to nits. */
    fun pqToNits(e: Float): Float {
        val p = e.coerceIn(0f, 1f).pow(1f / M2)
        return (max(p - C1, 0f) / (C2 - C3 * p)).pow(1f / M1) * 10_000f
    }

    /** HLG's inverse OETF: a signal value, 0..1, to scene light, 0..1. */
    fun hlgToScene(e: Float): Float {
        val v = e.coerceIn(0f, 1f)
        return if (v <= 0.5f) v * v / 3f else (exp((v - HLG_C) / HLG_A) + HLG_B) / 12f
    }

    /** The shoulder: identity to [KNEE], then rising toward 1.0 with no kink. */
    fun toneMap(x: Float): Float {
        if (x <= KNEE) return max(x, 0f)
        val t = (x - KNEE) / (1f - KNEE)
        return KNEE + (1f - KNEE) * t / (1f + t)
    }

    /**
     * R'G'B' of an HDR signal (BT.2020, 0..1) to R'G'B' for an SDR display
     * (BT.709, 0..1). [Transfer.SDR] passes through.
     */
    fun toSdr(r: Float, g: Float, b: Float, transfer: Transfer): FloatArray {
        if (transfer == Transfer.SDR) return floatArrayOf(r, g, b)
        // 1–2. Light, relative to reference white.
        var lin = when (transfer) {
            Transfer.PQ -> floatArrayOf(pqToNits(r), pqToNits(g), pqToNits(b))
            else -> {
                val scene = floatArrayOf(hlgToScene(r), hlgToScene(g), hlgToScene(b))
                val ys = 0.2627f * scene[0] + 0.6780f * scene[1] + 0.0593f * scene[2]
                val gain = HLG_PEAK_NITS * if (ys > 0f) ys.pow(HLG_GAMMA - 1f) else 0f
                FloatArray(3) { scene[it] * gain }
            }
        }.map { it / REFERENCE_WHITE_NITS }.toFloatArray()
        // 3. BT.2020 to BT.709 primaries, in linear light.
        lin = floatArrayOf(
            1.6605f * lin[0] - 0.5876f * lin[1] - 0.0728f * lin[2],
            -0.1246f * lin[0] + 1.1329f * lin[1] - 0.0083f * lin[2],
            -0.0182f * lin[0] - 0.1006f * lin[1] + 1.1187f * lin[2],
        )
        // 4. Tone-map luminance and keep the colour's ratios.
        val y = 0.2126f * lin[0] + 0.7152f * lin[1] + 0.0722f * lin[2]
        if (y <= 0f) return floatArrayOf(0f, 0f, 0f)
        val yt = toneMap(y)
        var rgb = FloatArray(3) { lin[it] * yt / y }
        val hi = max(rgb[0], max(rgb[1], rgb[2]))
        val lo = min(rgb[0], min(rgb[1], rgb[2]))
        var keep = 1f
        if (hi > 1f) keep = min(keep, (1f - yt) / (hi - yt))
        if (lo < 0f) keep = min(keep, yt / (yt - lo))
        rgb = FloatArray(3) { yt + (rgb[it] - yt) * keep }
        // 5. Display encoding.
        return FloatArray(3) { rgb[it].coerceIn(0f, 1f).pow(1f / 2.4f) }
    }

    /** The display luma, 0..1, a neutral grey of signal luma [y] ends up at: what the exposure tools read in HDR. */
    fun displayLuma(y: Float, transfer: Transfer): Float = toSdr(y, y, y, transfer)[0]
}
