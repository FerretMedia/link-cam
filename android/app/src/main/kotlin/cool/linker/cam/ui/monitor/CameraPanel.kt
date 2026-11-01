package cool.linker.cam.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
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
import cool.linker.cam.ui.theme.LcColors
import cool.linker.cam.ui.theme.LcSize
import cool.linker.cam.ui.theme.LcType

/**
 * The camera's own settings, along the bottom of the picture like the camera's
 * info bar. Greyed out while the link is lost, so stale numbers never pass for
 * live ones.
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
    val items = settings.readout()
    if (items.isEmpty()) return
    Row(
        modifier
            .clip(RoundedCornerShape(LcSize.Radius))
            .background(LcColors.InkRaised.copy(alpha = 0.6f))
            .semantics { contentDescription = items.joinToString(", ") + if (live) "" else ", camera not connected" }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        for (item in items) {
            BasicText(item, style = LcType.CameraValue.copy(color = if (live) LcColors.Paper else LcColors.Faint))
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
