package cool.linker.cam.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cool.linker.cam.R
import cool.linker.cam.ui.theme.LcType

/** The glyph and the name, side by side, as Linker draws its own wordmark. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_glyph), contentDescription = null, modifier = Modifier.size(size))
        BasicText(stringResource(R.string.app_name), style = LcType.Title.copy(fontSize = (size.value * 0.7f).sp))
    }
}
