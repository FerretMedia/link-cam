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

/**
 * How hard to smooth the picture's grain, in time first and space second.
 *
 * [keep] is how much of the previous cleaned frame a still pixel keeps, so it
 * averages over about (1 + keep) / (1 − keep) frames. [noise] is the grain's
 * expected size in signal levels (0..1): differences within a few times it are
 * grain, larger ones are movement, which gets the spatial filter instead.
 */
@Serializable
enum class NoiseReduction(val label: String, val keep: Float, val noise: Float) {
    OFF("Off", 0f, 0f),
    LOW("Low", 0.6f, 3f / 255f),
    HIGH("High", 0.8f, 5f / 255f),
}

/**
 * Shows an HDR signal the way DaVinci Resolve's standard conversion to
 * Rec.709 does: the reference for the monitor shader's HDR path, which tests
 * check this against.
 *
 * Steps:
 * 1. Undo the transfer function, to light in nits. PQ is absolute (ST 2084);
 *    HLG goes through its BT.2100 OOTF for a 1000-nit display.
 * 2. Scale so 100 nits is 1.0. Resolve's Rec.709 output is display-referred:
 *    100 nits of PQ is 100% of SDR, not BT.2408's 203.
 * 3. Convert BT.2020 primaries to BT.709 in linear light.
 * 4. Tone-map luminance with the BT.2390 EETF, from [SOURCE_PEAK_NITS] down
 *    to 100 nits: one to one below about 28 nits, a smooth roll-off above.
 *    Colours keep their hue, and any that land out of gamut are pulled toward
 *    their own grey, so very bright colours go to white rather than clipping
 *    per channel into a flat blob.
 * 5. Encode for an SDR display, gamma 2.4 as in BT.1886 and Resolve's
 *    "Rec.709 Gamma 2.4".
 */
object Hdr {
    /** What 1.0 of SDR is, in nits. */
    const val SDR_WHITE_NITS = 100f

    /** The brightest the camera's PQ is taken to go; brighter still clips. Resolve's usual max input. */
    const val SOURCE_PEAK_NITS = 1000f

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

    /** ST 2084's other direction: nits to a PQ signal value, 0..1. */
    fun nitsToPq(nits: Float): Float {
        val y = (nits / 10_000f).coerceIn(0f, 1f).pow(M1)
        return ((C1 + C2 * y) / (1f + C3 * y)).pow(M2)
    }

    /** HLG's inverse OETF: a signal value, 0..1, to scene light, 0..1. */
    fun hlgToScene(e: Float): Float {
        val v = e.coerceIn(0f, 1f)
        return if (v <= 0.5f) v * v / 3f else (exp((v - HLG_C) / HLG_A) + HLG_B) / 12f
    }

    private val PQ_PEAK = nitsToPq(SOURCE_PEAK_NITS)
    private val MAX_LUM = nitsToPq(SDR_WHITE_NITS) / PQ_PEAK

    /** Where the EETF starts to bend, as a fraction of [PQ_PEAK]. */
    private val KS = 1.5f * MAX_LUM - 0.5f

    /** Light below this, relative to SDR white, is shown as it is. About 0.28. */
    val KNEE: Float = pqToNits(KS * PQ_PEAK) / SDR_WHITE_NITS

    /**
     * BT.2390's EETF on light relative to SDR white: identity up to [KNEE],
     * then a Hermite spline in PQ that lands [SOURCE_PEAK_NITS] exactly on 1.0.
     */
    fun toneMap(x: Float): Float {
        if (x <= KNEE) return max(x, 0f)
        val e = min(nitsToPq(x * SDR_WHITE_NITS) / PQ_PEAK, 1f)
        val t = (e - KS) / (1f - KS)
        val t2 = t * t
        val t3 = t2 * t
        val p = (2f * t3 - 3f * t2 + 1f) * KS + (t3 - 2f * t2 + t) * (1f - KS) + (-2f * t3 + 3f * t2) * MAX_LUM
        return min(pqToNits(p * PQ_PEAK) / SDR_WHITE_NITS, 1f)
    }

    /**
     * R'G'B' of an HDR signal (BT.2020, 0..1) to R'G'B' for an SDR display
     * (BT.709, 0..1). [Transfer.SDR] passes through.
     */
    fun toSdr(r: Float, g: Float, b: Float, transfer: Transfer): FloatArray {
        if (transfer == Transfer.SDR) return floatArrayOf(r, g, b)
        // 1–2. Light, relative to SDR white.
        var lin = when (transfer) {
            Transfer.PQ -> floatArrayOf(pqToNits(r), pqToNits(g), pqToNits(b))
            else -> {
                val scene = floatArrayOf(hlgToScene(r), hlgToScene(g), hlgToScene(b))
                val ys = 0.2627f * scene[0] + 0.6780f * scene[1] + 0.0593f * scene[2]
                val gain = HLG_PEAK_NITS * if (ys > 0f) ys.pow(HLG_GAMMA - 1f) else 0f
                FloatArray(3) { scene[it] * gain }
            }
        }.map { it / SDR_WHITE_NITS }.toFloatArray()
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
