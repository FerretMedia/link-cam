package cool.linker.cam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cool.linker.cam.ui.theme.LcColors
import cool.linker.cam.ui.theme.LcSize
import cool.linker.cam.ui.theme.LcType

/**
 * A tool on the rail: a short label that turns blue with an underline when the
 * tool is on. Tap toggles; long-press opens the tool's settings, when it has
 * any.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolToggle(
    label: String,
    description: String,
    active: Boolean,
    onToggle: () -> Unit,
    onSettings: (() -> Unit)? = null,
    /** False for a rail button that opens something rather than switching a tool. */
    isSwitch: Boolean = true,
    /** Drawn instead of [label], in the colour given. */
    glyph: (@Composable (Color) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .size(LcSize.HitTarget, LcSize.RailItem)
            .semantics {
                contentDescription = description
                if (isSwitch) stateDescription = if (active) "On" else "Off"
            }
            .combinedClickable(
                role = if (isSwitch) Role.Switch else Role.Button,
                onClick = {
                    haptics.performHapticFeedback(
                        when {
                            !isSwitch -> HapticFeedbackType.ContextClick
                            active -> HapticFeedbackType.ToggleOff
                            else -> HapticFeedbackType.ToggleOn
                        },
                    )
                    onToggle()
                },
                onLongClickLabel = if (onSettings != null) "$description settings" else null,
                onLongClick = onSettings?.let { open ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        open()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val color = if (active) LcColors.BrandBright else LcColors.Muted
        if (glyph != null) glyph(color) else BasicText(label, style = LcType.Toggle.copy(color = color))
        if (active) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(20.dp)
                    .height(2.dp)
                    .background(LcColors.BrandBright),
            )
        }
    }
}

/** The rail's divider between groups of tools. */
@Composable
fun RailDivider(vertical: Boolean) {
    Box(
        (
            if (vertical) Modifier.padding(vertical = 3.dp).width(24.dp).height(1.dp)
            else Modifier.padding(horizontal = 3.dp).height(24.dp).width(1.dp)
        ).background(LcColors.InkLine),
    )
}

/** A cog: the settings button's mark. Teeth around a ring, drawn so it matches the rail's labels in weight. */
@Composable
fun CogGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val r = size.minDimension / 2
        val tooth = Size(r * 0.42f, r * 0.5f)
        repeat(TEETH) { i ->
            rotate(i * 360f / TEETH) {
                drawRoundRect(
                    color,
                    topLeft = Offset(center.x - tooth.width / 2, center.y - r),
                    size = tooth,
                    cornerRadius = CornerRadius(r * 0.08f),
                )
            }
        }
        // A thick ring: its inside is the cog's hole.
        drawCircle(color, radius = r * 0.56f, style = Stroke(width = r * 0.34f))
    }
}

private const val TEETH = 8
