package cool.linker.cam.model

/**
 * Which of a device's formats to start with, before the user picks one.
 *
 * For a monitor, smooth motion beats resolution past what a phone screen
 * shows: take the largest size up to 1080p, then its fastest rate. When
 * codecs tie, uncompressed wins, since it skips the JPEG decode and carries no
 * compression artefacts.
 */
object FormatChoice {
    private const val MAX_PIXELS = 1920 * 1080

    fun best(formats: List<SourceFormat>): SourceFormat? {
        val area = { f: SourceFormat -> f.width * f.height }
        val fitting = formats.filter { area(it) <= MAX_PIXELS }
        if (fitting.isNotEmpty()) {
            return fitting.maxWith(
                compareBy(area).thenBy { it.fps }.thenBy { codecPreference(it.codec) },
            )
        }
        // Everything is bigger than 1080p: the smallest is the lightest to decode.
        return formats.minWithOrNull(
            compareBy(area).thenByDescending { it.fps }.thenByDescending { codecPreference(it.codec) },
        )
    }

    private fun codecPreference(codec: SourceFormat.Codec): Int = when (codec) {
        SourceFormat.Codec.YUYV -> 3
        SourceFormat.Codec.NV12 -> 2
        SourceFormat.Codec.I420 -> 1
        SourceFormat.Codec.MJPEG -> 0
    }
}
