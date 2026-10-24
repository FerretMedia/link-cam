package cool.linker.cam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cool.linker.cam.ui.theme.LcColors
import cool.linker.cam.ui.theme.LcSize
import cool.linker.cam.ui.theme.LcType

/** Paper on ink for the one thing to do on a screen; outlined for the rest. */
@Composable
fun LcButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val shape = RoundedCornerShape(LcSize.Radius)
    Box(
        modifier
            .defaultMinSize(minHeight = LcSize.HitTarget)
            .clip(shape)
            .then(
                if (primary) Modifier.background(LcColors.Paper)
                else Modifier.background(LcColors.InkRaised).border(1.dp, LcColors.InkLine, shape),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = LcType.Label.copy(color = if (primary) LcColors.Ink else LcColors.Paper))
    }
}
