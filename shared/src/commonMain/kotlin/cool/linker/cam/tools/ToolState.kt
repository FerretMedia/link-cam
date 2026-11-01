package cool.linker.cam.tools

import cool.linker.cam.model.NoiseReduction
import cool.linker.cam.model.Transfer
import kotlinx.serialization.Serializable

/**
 * Everything the monitoring tools are set to, as one immutable value.
 *
 * One value rather than a manager per tool, so a preset is just a saved
 * `ToolState` and switching presets is a single assignment.
 */
@Serializable
data class ToolState(
    val falseColor: Boolean = false,
    val zebras: Boolean = false,
    /** Stripes appear at and above this level, in IRE. */
    val zebraLevel: Int = 95,
    val peaking: Boolean = false,
    val peakingColor: PeakingColor = PeakingColor.RED,
    val peakingSensitivity: PeakingSensitivity = PeakingSensitivity.MEDIUM,
    val scope: Boolean = false,
    val scopeKind: ScopeKind = ScopeKind.HISTOGRAM,
    val guides: Boolean = false,
    val guideAspect: GuideAspect = GuideAspect.SCOPE,
    val safeAreas: Boolean = false,
    val punchIn: Boolean = false,
    /** What the source's signal is, which decides how it's shown. Kept, since a camera stays set to one. */
    val signal: Transfer = Transfer.SDR,
    val noiseReduction: NoiseReduction = NoiseReduction.OFF,
) {
    companion object {
        val ZEBRA_LEVELS = listOf(70, 80, 90, 95, 100)
    }
}

@Serializable
enum class PeakingColor(val label: String, val rgb: Int) {
    RED("Red", 0xFF2A2A),
    GREEN("Green", 0x2AFF4A),
    BLUE("Blue", 0x2A8CFF),
    WHITE("White", 0xFFFFFF),
}

/** Which exposure graph the scope panel shows. [short] is its label on the rail. */
@Serializable
enum class ScopeKind(val label: String, val short: String) {
    HISTOGRAM("Histogram", "HIST"),
    WAVEFORM("Waveform", "WFM"),
}

/**
 * How strong an edge must be before it is marked. The threshold is a Sobel
 * magnitude on normalised luma.
 */
@Serializable
enum class PeakingSensitivity(val label: String, val threshold: Float) {
    LOW("Low", 0.45f),
    MEDIUM("Medium", 0.3f),
    HIGH("High", 0.18f),
}
