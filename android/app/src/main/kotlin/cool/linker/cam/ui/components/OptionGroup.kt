package cool.linker.cam.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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

/**
 * Pick one of a fixed set, as a row of segments. The same control as
 * linker-app's OptionGroup: a radio group to accessibility services, with the
 * legend naming it.
 */
@Composable
fun <T> OptionGroup(
    legend: String,
    options: List<Pair<T, String>>,
    value: T,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(legend.uppercase(), style = LcType.Legend)
        FlowRow(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((option, label) in options) {
                val selected = option == value
                val shape = RoundedCornerShape(LcSize.RadiusChip)
                Box(
                    Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 40.dp)
                        .clip(shape)
                        .border(1.dp, if (selected) LcColors.BrandBright else LcColors.InkLine, shape)
                        .selectable(selected = selected, role = Role.RadioButton) { onChange(option) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(label, style = LcType.Label.copy(color = if (selected) LcColors.BrandBright else LcColors.Paper))
                }
            }
        }
    }
}
