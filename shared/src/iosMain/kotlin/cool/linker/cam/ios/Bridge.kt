@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package cool.linker.cam.ios

import cool.linker.cam.design.Tokens
import cool.linker.cam.model.ColorSpec
import cool.linker.cam.model.FormatChoice
import cool.linker.cam.model.NoiseReduction
import cool.linker.cam.model.SourceFormat
import cool.linker.cam.model.Transfer
import cool.linker.cam.pattern.TestPattern
import cool.linker.cam.tools.Exposure
import cool.linker.cam.tools.FalseColor
import cool.linker.cam.tools.GuideAspect
import cool.linker.cam.tools.Guides
import cool.linker.cam.tools.Histogram
import cool.linker.cam.tools.PeakingColor
import cool.linker.cam.tools.PeakingSensitivity
import cool.linker.cam.tools.Rect
import cool.linker.cam.tools.ScopeKind
import cool.linker.cam.tools.Scopes
import cool.linker.cam.tools.ToolState
import cool.linker.cam.tools.Waveform
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * The shared module as the iPad app reaches it.
 *
 * Swift sees Kotlin through an Objective-C header, where nested types,
 * companion objects and primitive arrays all come out awkward. This keeps
 * every call plain: enums are looked up by the name the settings are saved
 * under, arrays cross as `Data`, and results are simple values. The numbers
 * themselves stay where they are, so both apps read the same ones.
 */
object Bridge {
    // --- Settings' enums, by name ------------------------------------------

    fun transfer(name: String): Transfer = Transfer.valueOf(name)
    fun noiseReduction(name: String): NoiseReduction = NoiseReduction.valueOf(name)
    fun peakingColor(name: String): PeakingColor = PeakingColor.valueOf(name)
    fun peakingSensitivity(name: String): PeakingSensitivity = PeakingSensitivity.valueOf(name)
    fun scopeKind(name: String): ScopeKind = ScopeKind.valueOf(name)
    fun guideAspect(name: String): GuideAspect = GuideAspect.valueOf(name)

    val zebraLevels: List<Int> get() = ToolState.ZEBRA_LEVELS

    // --- Colour --------------------------------------------------------------

    private fun spec(matrix: String, fullRange: Boolean) =
        ColorSpec(ColorSpec.Matrix.valueOf(matrix), if (fullRange) ColorSpec.Range.FULL else ColorSpec.Range.LIMITED)

    /**
     * Fifteen floats for the shader: the column-major 3×3 Y'CbCr-to-R'G'B'
     * matrix, then the offset, then the scale. [matrix] is a
     * `ColorSpec.Matrix` name: BT601, BT709 or BT2020.
     */
    fun shaderConstants(matrix: String, fullRange: Boolean): NSData {
        val c = spec(matrix, fullRange).shaderConstants()
        return (c.matrix + c.offset + c.scale).toNSData()
    }

    /** "601/full", as the status strip shows it. */
    fun colorSpecLabel(matrix: String, fullRange: Boolean): String = spec(matrix, fullRange).label

    fun matrixLabel(matrix: String): String = ColorSpec.Matrix.valueOf(matrix).label

    // --- Exposure tools ------------------------------------------------------

    /** `FalseColor.ramp()`: 256 RGBA8 entries, for a 256×1 texture. */
    fun falseColorRamp(): NSData = FalseColor.ramp().toNSData()

    /** 0xRRGGBB. */
    fun falseColorRgb(ire: Float): Int = FalseColor.rgb(ire)

    val crushIre: Float get() = Exposure.CRUSH_IRE
    val clipIre: Float get() = Exposure.CLIP_IRE

    // --- Frame lines ---------------------------------------------------------

    fun fit(width: Float, height: Float, aspect: Float): Rect = Guides.fit(width, height, aspect)

    fun frameLine(picture: Rect, guide: String): Rect = Guides.frameLine(picture, GuideAspect.valueOf(guide))

    val actionSafe: Float get() = Guides.ACTION_SAFE
    val titleSafe: Float get() = Guides.TITLE_SAFE

    // --- Scopes --------------------------------------------------------------

    val scopeGridWidth: Int get() = Scopes.GRID_WIDTH
    val scopeGridHeight: Int get() = Scopes.GRID_HEIGHT
    val histogramBins: Int get() = Scopes.HISTOGRAM_BINS
    val waveformColumns: Int get() = Scopes.WAVEFORM_COLUMNS
    val waveformLevels: Int get() = Scopes.WAVEFORM_LEVELS

    /** Opaque to Swift: built when the signal changes, then handed back to [histogram] and [waveform]. */
    fun ireTable(lumaOffset: Float, lumaScale: Float, transfer: String): FloatArray =
        Scopes.ireTable(lumaOffset, lumaScale, Transfer.valueOf(transfer))

    /** [codes] is the scope grid's luma codes, [scopeGridWidth] × [scopeGridHeight], row by row. */
    fun histogram(codes: NSData, ire: FloatArray): Histogram = Scopes.histogram(codes.toByteArray(), ire)

    fun waveform(codes: NSData, width: Int, height: Int, ire: FloatArray): Waveform =
        Scopes.waveform(codes.toByteArray(), width, height, ire)

    /** The histogram's bins as Int32s. */
    fun bins(histogram: Histogram): NSData = histogram.bins.toNSData()

    /** The waveform's cells as Int32s, level by level from 0 IRE, [waveformColumns] to a level. */
    fun cells(waveform: Waveform): NSData = waveform.cells.toNSData()

    fun ireOf(step: Int, steps: Int): Float = Scopes.ireOf(step, steps)

    // --- Formats -------------------------------------------------------------

    /** [codec] is a `SourceFormat.Codec` name: MJPEG, YUYV, NV12 or I420. */
    fun sourceFormat(codec: String, width: Int, height: Int, fps: Double): SourceFormat =
        SourceFormat(SourceFormat.Codec.valueOf(codec), width, height, fps)

    /** Which of [formats] to start with, by `FormatChoice`; -1 for none. */
    fun bestFormat(formats: List<SourceFormat>): Int = FormatChoice.best(formats)?.let(formats::indexOf) ?: -1

    fun fpsLabel(fps: Double): String = SourceFormat.fpsLabel(fps)

    /** True for a codec the dongle sends full range unless it says otherwise: JPEG is JFIF. */
    fun defaultsToFullRange(codec: String): Boolean =
        sourceFormat(codec, 1, 1, 0.0).defaultColorSpec.range == ColorSpec.Range.FULL

    // --- Test pattern --------------------------------------------------------

    val testPatternWidth: Int get() = TestPattern.WIDTH
    val testPatternHeight: Int get() = TestPattern.HEIGHT
    val testPatternFps: Int get() = TestPattern.FPS
    val testPatternSquare: Int get() = TestPattern.SQUARE
    val testPatternSquareTop: Int get() = TestPattern.SQUARE_TOP

    fun testPatternSquareLeft(frame: Int): Int = TestPattern.squareLeft(frame)

    /** The pattern's luma plane, [testPatternWidth] bytes to a row. */
    fun testPatternLuma(): NSData = TestPattern.base().y.toNSData()

    /** The pattern's chroma as one interleaved Cb, Cr plane at half size, as NV12 lays it out. */
    fun testPatternChroma(): NSData {
        val planes = TestPattern.base()
        val out = ByteArray(planes.cb.size * 2)
        for (i in planes.cb.indices) {
            out[2 * i] = planes.cb[i]
            out[2 * i + 1] = planes.cr[i]
        }
        return out.toNSData()
    }

    /** The moving square's Y, Cb and Cr codes. */
    val testPatternWhite: List<Int> get() = TestPattern.white.toList()

    // --- Design tokens -------------------------------------------------------

    val colorSurround: Long get() = Tokens.Color.SURROUND
    val colorInk: Long get() = Tokens.Color.INK
    val colorInkRaised: Long get() = Tokens.Color.INK_RAISED
    val colorInkLine: Long get() = Tokens.Color.INK_LINE
    val colorPaper: Long get() = Tokens.Color.PAPER
    val colorMuted: Long get() = Tokens.Color.MUTED
    val colorFaint: Long get() = Tokens.Color.FAINT
    val colorBrand: Long get() = Tokens.Color.BRAND
    val colorCrimson: Long get() = Tokens.Color.CRIMSON
    val colorBrandBright: Long get() = Tokens.Color.BRAND_BRIGHT
    val colorCrimsonBright: Long get() = Tokens.Color.CRIMSON_BRIGHT

    val sizeRadius: Float get() = Tokens.Size.RADIUS
    val sizeRadiusChip: Float get() = Tokens.Size.RADIUS_CHIP
    val sizeHitTarget: Float get() = Tokens.Size.HIT_TARGET
    val sizeRail: Float get() = Tokens.Size.RAIL
    val sizeRailItem: Float get() = Tokens.Size.RAIL_ITEM

    val driftPeriodsMs: List<Int> get() = Tokens.Motion.DRIFT_PERIODS_MS
    val driftDelaysMs: List<Int> get() = Tokens.Motion.DRIFT_DELAYS_MS
    val driftX: Float get() = Tokens.Motion.DRIFT_X
    val driftY: Float get() = Tokens.Motion.DRIFT_Y
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

private fun FloatArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = (size * 4).convert()) }

private fun IntArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = (size * 4).convert()) }

private fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
