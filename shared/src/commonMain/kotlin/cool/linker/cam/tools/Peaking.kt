package cool.linker.cam.tools

import kotlin.math.sqrt

/**
 * Focus peaking: a 3×3 Sobel on luma. An edge is marked where the gradient
 * magnitude reaches the threshold of the chosen [PeakingSensitivity].
 */
object Peaking {
    /** [l] is a 3×3 neighbourhood of normalised luma, row by row. */
    fun magnitude(l: FloatArray): Float {
        val gx = (l[2] + 2 * l[5] + l[8]) - (l[0] + 2 * l[3] + l[6])
        val gy = (l[6] + 2 * l[7] + l[8]) - (l[0] + 2 * l[1] + l[2])
        return sqrt(gx * gx + gy * gy)
    }

    fun fires(l: FloatArray, sensitivity: PeakingSensitivity): Boolean =
        magnitude(l) >= sensitivity.threshold
}
