package cool.linker.cam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cool.linker.cam.ui.theme.LcColors
import cool.linker.cam.ui.theme.LcSize
import cool.linker.cam.ui.theme.LcType

/**
 * A tool's settings, one level deep. Opaque, never translucent: anything see-
 * through over the picture would tint how it's judged.
 */
@Composable
fun ToolSheet(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(LcSize.Radius)
    Column(
        modifier
            .widthIn(max = 360.dp)
            .clip(shape)
            .background(LcColors.InkRaised)
            .border(1.dp, LcColors.InkLine, shape)
            // Swallow taps so they don't fall through to the picture and hide the chrome.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BasicText(title, style = LcType.Label)
        content()
    }
}
