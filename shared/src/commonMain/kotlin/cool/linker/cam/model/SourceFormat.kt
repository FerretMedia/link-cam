package cool.linker.cam.model

import kotlin.math.abs
import kotlin.math.roundToInt

/** What a source delivers: codec, size and frame rate, as the device describes it. */
data class SourceFormat(
    val codec: Codec,
    val width: Int,
    val height: Int,
    val fps: Double,
) {
    enum class Codec(val label: String) {
        MJPEG("MJPEG"),
        YUYV("YUY2"),
        NV12("NV12"),
        /** Planar 4:2:0, produced by the test pattern. */
        I420("I420"),
    }

    /** The interpretation to assume until the user says otherwise. */
    val defaultColorSpec: ColorSpec
        get() = when (codec) {
            Codec.MJPEG -> ColorSpec.JFIF
            Codec.YUYV, Codec.NV12, Codec.I420 -> ColorSpec.HD_VIDEO
        }

    val aspect: Float get() = width.toFloat() / height

    /** "1920×1080 · 59.94p · MJPEG" */
    val label: String get() = "$width×$height · ${fpsLabel(fps)}p · ${codec.label}"

    companion object {
        /** UVC frame intervals are in 100 ns units. */
        fun fpsFromInterval(interval100ns: Int): Double =
            if (interval100ns <= 0) 0.0 else 10_000_000.0 / interval100ns

        /** 59.94 stays 59.94, 60.0 becomes 60, 29.97 stays 29.97. */
        fun fpsLabel(fps: Double): String {
            val whole = fps.roundToInt()
            if (abs(fps - whole) < 0.005) return whole.toString()
            val hundredths = (fps * 100).roundToInt()
            return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
        }
    }
}
