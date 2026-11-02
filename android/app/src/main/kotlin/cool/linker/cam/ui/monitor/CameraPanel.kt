package cool.linker.cam.ui.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cool.linker.cam.camera.CameraLinkState
import cool.linker.cam.camera.CameraSettings
import cool.linker.cam.ui.theme.LcColors
import cool.linker.cam.ui.theme.LcSize
import cool.linker.cam.ui.theme.LcType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The camera's own settings, along the bottom of the picture like the camera's
 * info bar: shutter speed, aperture and ISO, large enough to read at a glance,
 * each with the dial or ring that turns it, as the camera marks them on its
 * own screen. Greyed out while the link is lost, so stale numbers never pass
 * for live ones.
 */
@Composable
fun CameraReadout(state: CameraLinkState, modifier: Modifier = Modifier) {
    val (settings, live) = when (state) {
        is CameraLinkState.Connected -> state.settings to true
        is CameraLinkState.Reconnecting -> state.settings to false
        // Pairing can start from the picture alone, so say what's happening; looking for
        // the saved cameras in the background isn't worth a word over the picture.
        is CameraLinkState.Searching -> return if (state.background) Unit else LinkNote(state.hint, modifier)
        is CameraLinkState.Connecting -> return LinkNote(state.hint ?: "Connecting to the camera…", modifier)
        else -> return
    }
    val controls = settings.controls
    val items = listOfNotNull(
        settings.shutter?.let { CameraSettings.shutterLabel(it) to controls.shutter },
        settings.aperture?.let { CameraSettings.apertureLabel(it) to controls.aperture },
        settings.iso?.let { CameraSettings.isoLabel(it) to controls.iso },
    )
    if (items.isEmpty()) return
    val ink = if (live) LcColors.Paper else LcColors.Faint
    val described = items.joinToString(", ") { (value, on) ->
        value + on.joinToString("") { " on the ${it.label}" }
    }
    Row(
        modifier
            .clip(RoundedCornerShape(LcSize.Radius))
            .background(LcColors.InkRaised.copy(alpha = 0.6f))
            .semantics { contentDescription = described + if (live) "" else ", camera not connected" }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((value, on) in items) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (control in on) ControlGlyph(control, if (live) LcColors.Muted else LcColors.Faint)
                BasicText(value, style = LcType.CameraValueLarge.copy(color = ink))
            }
        }
    }
}

/**
 * A camera control, drawn the way Canon marks it: the top dial as the arc
 * of its ridged edge, the rear dial as a ridged wheel, the lens ring as a
 * ridged band round a barrel.
 */
@Composable
private fun ControlGlyph(control: CameraSettings.Control, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.6.dp.toPx()
        val c = center
        val r = size.minDimension / 2 - stroke
        fun ridges(from: Double, to: Double, count: Int, inner: Float, outer: Float, around: Offset) {
            for (i in 0..count) {
                val a = from + (to - from) * i / count
                val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
                drawLine(color, around + dir * inner, around + dir * outer, strokeWidth = stroke * 0.8f)
            }
        }
        when (control) {
            CameraSettings.Control.MAIN_DIAL -> {
                // The top dial's edge, seen from behind the camera.
                val around = Offset(c.x, c.y + r * 0.45f)
                drawArc(
                    color, 180f, 180f, useCenter = false,
                    topLeft = around - Offset(r, r * 0.85f), size = Size(r * 2, r * 1.7f), style = Stroke(stroke),
                )
                ridges(PI * 1.1, PI * 1.9, 4, r * 0.85f, r * 1.15f, around)
            }
            CameraSettings.Control.QUICK_DIAL -> {
                drawCircle(color, radius = r * 0.7f, center = c, style = Stroke(stroke))
                ridges(0.0, PI * 2 * 11 / 12, 11, r * 0.78f, r * 1.05f, c)
            }
            CameraSettings.Control.CONTROL_RING -> {
                // A band round the lens barrel, its grip lines running along the lens.
                val band = Size(r * 1.3f, r * 2)
                val left = c.x - band.width / 2
                drawRoundRect(color, Offset(left, c.y - r), band, CornerRadius(r * 0.5f, r * 0.5f), style = Stroke(stroke))
                for (i in 1..3) {
                    val x = left + band.width * i / 4
                    drawLine(color, Offset(x, c.y - r * 0.7f), Offset(x, c.y + r * 0.7f), strokeWidth = stroke * 0.8f)
                }
            }
        }
    }
}

@Composable
private fun LinkNote(text: String, modifier: Modifier) {
    BasicText(
        text,
        style = LcType.Readout.copy(color = LcColors.Paper),
        modifier = modifier
            .clip(RoundedCornerShape(LcSize.Radius))
            .background(LcColors.InkRaised.copy(alpha = 0.8f))
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** A one-line text field in the settings' style. [secret] hides what's typed, for passwords. */
@Composable
fun SheetField(
    value: String,
    onChange: (String) -> Unit,
    onGo: () -> Unit,
    placeholder: String,
    description: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
) {
    val shape = RoundedCornerShape(LcSize.RadiusChip)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = LcType.Label,
        cursorBrush = SolidColor(LcColors.BrandBright),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
            imeAction = ImeAction.Go,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = modifier.semantics { contentDescription = description },
        decorationBox = { field ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .border(1.dp, LcColors.InkLine, shape)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) BasicText(placeholder, style = LcType.Label.copy(color = LcColors.Faint))
                field()
            }
        },
    )
}
