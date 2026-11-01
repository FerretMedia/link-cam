package cool.linker.cam.ui.monitor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cool.linker.cam.render.ScopeReading
import cool.linker.cam.tools.Exposure
import cool.linker.cam.tools.FalseColor
import cool.linker.cam.tools.Histogram
import cool.linker.cam.tools.ScopeKind
import cool.linker.cam.tools.Scopes
import cool.linker.cam.tools.Waveform
import cool.linker.cam.ui.theme.LcColors
import cool.linker.cam.ui.theme.LcSize
import cool.linker.cam.ui.theme.LcType
import kotlin.math.min
import kotlin.math.sqrt

private val ClipRed = Color(0xFF000000 or FalseColor.rgb(100f).toLong())
private val CrushPurple = Color(0xFF000000 or FalseColor.rgb(0f).toLong())

/**
 * The exposure graph, floating over a corner of the picture. See-through, so
 * the picture still shows behind it, and drawn in the chrome's greys with
 * false color's clip red and crush purple at the ends, so the graph and the
 * false color overlay agree on what "clipped" means.
 */
@Composable
fun ScopePanel(reading: ScopeReading?, kind: ScopeKind, graphHeight: Dp, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(LcSize.Radius))
            .background(LcColors.InkRaised.copy(alpha = 0.55f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(kind.label.uppercase(), style = LcType.Legend)
        val graph = Modifier.fillMaxWidth().height(graphHeight).background(LcColors.Surround.copy(alpha = 0.35f))
        when {
            reading?.histogram != null -> HistogramGraph(reading.histogram, graph)
            reading?.waveform != null -> WaveformGraph(reading.waveform, graph)
            else -> Canvas(graph) { referenceLines(vertical = kind == ScopeKind.HISTOGRAM) }
        }
        reading?.histogram?.let { ClipReadout(it) }
    }
}

/** How much of the picture is clipped and crushed, coloured once it's more than a speck. */
@Composable
private fun ClipReadout(h: Histogram) {
    val clip = h.clippedFraction
    val crush = h.crushedFraction
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        BasicText(
            "▼ ${percent(crush)}",
            style = LcType.Readout.copy(color = if (crush >= SPECK) CrushPurple else LcColors.Faint),
            modifier = Modifier.semantics { contentDescription = "Crushed ${percent(crush)}" },
        )
        BasicText(
            "▲ ${percent(clip)}",
            style = LcType.Readout.copy(color = if (clip >= SPECK) ClipRed else LcColors.Faint),
            modifier = Modifier.semantics { contentDescription = "Clipped ${percent(clip)}" },
        )
    }
}

@Composable
private fun HistogramGraph(h: Histogram, modifier: Modifier) {
    Canvas(modifier) {
        referenceLines(vertical = true)
        val bins = h.bins
        val peak = h.peak.toFloat()
        val barWidth = size.width / bins.size
        for (i in bins.indices) {
            if (bins[i] == 0) continue
            val ire = Scopes.ireOf(i, bins.size)
            val color = when {
                ire < Exposure.CRUSH_IRE -> CrushPurple
                ire >= Exposure.CLIP_IRE -> ClipRed
                else -> LcColors.Paper.copy(alpha = 0.8f)
            }
            // At least a pixel, so a level that's there never reads as empty.
            val height = (min(1f, bins[i] / peak) * size.height).coerceAtLeast(1f)
            drawRect(color, Offset(i * barWidth, size.height - height), Size(barWidth + 0.5f, height))
        }
    }
}

@Composable
private fun WaveformGraph(w: Waveform, modifier: Modifier) {
    val image = remember(w) { waveformImage(w) }
    Canvas(modifier) {
        drawImage(
            image,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            dstOffset = IntOffset.Zero,
            filterQuality = FilterQuality.Low,
        )
        referenceLines(vertical = false)
    }
}

/**
 * One pixel per column and level, brighter where more of the column sits at
 * that level. The square root lifts thin traces so a small bright object
 * still shows.
 */
private fun waveformImage(w: Waveform): ImageBitmap {
    val cols = Scopes.WAVEFORM_COLUMNS
    val levels = Scopes.WAVEFORM_LEVELS
    val pixels = IntArray(cols * levels)
    val gain = 1f / (w.samplesPerColumn * 0.04f)
    for (level in 0 until levels) {
        val clipped = Scopes.ireOf(level, levels) >= Exposure.CLIP_IRE
        val row = (levels - 1 - level) * cols
        for (col in 0 until cols) {
            val n = w.count(col, level)
            if (n == 0) continue
            val alpha = (min(1f, sqrt(n * gain)) * 255f).toInt().coerceAtLeast(48)
            val rgb = if (clipped) FalseColor.rgb(100f) else 0xFAFAFA
            pixels[row + col] = (alpha shl 24) or rgb
        }
    }
    return Bitmap.createBitmap(pixels, cols, levels, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Faint lines at 0, 25, 50, 75 and 100 IRE: across the graph for a waveform, up it for a histogram. */
private fun DrawScope.referenceLines(vertical: Boolean) {
    val stroke = 1.dp.toPx()
    for (ire in listOf(0f, 25f, 50f, 75f, 100f)) {
        val color = if (ire == 50f) LcColors.InkLine.copy(alpha = 1f) else LcColors.InkLine.copy(alpha = 0.7f)
        if (vertical) {
            val x = ire / 100f * (size.width - stroke) + stroke / 2
            drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
        } else {
            val y = (1f - ire / 100f) * (size.height - stroke) + stroke / 2
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
        }
    }
}

/** Under one in a thousand samples is noise, not something to act on. */
private const val SPECK = 0.001f

private fun percent(f: Float): String = when {
    f <= 0f -> "0%"
    f < 0.001f -> "<0.1%"
    f < 0.1f -> "%.1f%%".format(f * 100f)
    else -> "%.0f%%".format(f * 100f)
}
