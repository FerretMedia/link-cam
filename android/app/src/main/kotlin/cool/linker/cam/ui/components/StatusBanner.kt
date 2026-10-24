package cool.linker.cam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cool.linker.cam.source.Banner
import cool.linker.cam.ui.theme.LcColors
import cool.linker.cam.ui.theme.LcSize
import cool.linker.cam.ui.theme.LcType

private val ErrorRed = Color(0xFFF87171)

/** A one-line message about what just happened, announced to screen readers. */
@Composable
fun StatusBanner(banner: Banner, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(LcSize.Radius)
    Box(
        modifier
            .clip(shape)
            .background(LcColors.InkRaised)
            .border(1.dp, if (banner.isError) ErrorRed.copy(alpha = 0.5f) else LcColors.InkLine, shape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        BasicText(banner.message, style = LcType.Body.copy(color = if (banner.isError) ErrorRed else LcColors.Paper))
    }
}
