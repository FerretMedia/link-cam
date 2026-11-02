package cool.linker.cam.camera

/**
 * What the camera body says it's set to, in the camera's own words. Null for
 * anything it hasn't reported, or doesn't have in the current mode.
 */
data class CameraSettings(
    val iso: String? = null,
    val aperture: String? = null,
    val shutter: String? = null,
    val exposureComp: String? = null,
    /** How full the camera's battery is, 0 to 100. */
    val battery: Int? = null,
    /** Which of the camera's controls turns each setting, as its Customize dials menu has it. */
    val controls: Controls = Controls(),
) {
    val isEmpty: Boolean get() = iso == null && aperture == null && shutter == null && exposureComp == null

    /** The controls on a camera body and lens that can be set to change a setting. */
    enum class Control(val label: String) {
        /** The dial on top, by the shutter button. */
        MAIN_DIAL("main dial"),

        /** The wheel on the back. */
        QUICK_DIAL("rear dial"),

        /** The ring on an RF lens. */
        CONTROL_RING("lens control ring"),
    }

    /** What turns shutter speed, aperture and ISO. Two dials can turn the same setting. */
    data class Controls(
        val shutter: List<Control> = emptyList(),
        val aperture: List<Control> = emptyList(),
        val iso: List<Control> = emptyList(),
    )

    /** The readout, in the order a camera's own info bar shows it. */
    fun readout(): List<String> = listOfNotNull(
        shutter?.let(::shutterLabel),
        aperture?.let(::apertureLabel),
        iso?.let(::isoLabel),
    )

    companion object {
        /** Canon writes "1/50" for fractions, `0"5` for half a second and `30"` for thirty. */
        fun shutterLabel(v: String): String {
            if (v.contains('/') || !v.first().isDigit()) return v
            val seconds = v.substringBefore('"')
            val tenths = v.substringAfter('"', "")
            return if (tenths.isEmpty()) "$seconds″" else "$seconds.$tenths″"
        }

        fun apertureLabel(v: String): String = v.removePrefix("f").removePrefix("F").let { "f/$it" }

        fun isoLabel(v: String): String = if (v.equals("auto", ignoreCase = true)) "ISO AUTO" else "ISO $v"

        /** Canon writes thirds as "+1_1/3"; zero comes as "+0.0", "0" or "±0". */
        fun exposureLabel(v: String): String {
            val t = v.replace('_', ' ').trim()
            val zero = t.trimStart('+', '-', '±').let { it == "0" || it == "0.0" }
            return if (zero) "±0" else t
        }
    }
}
