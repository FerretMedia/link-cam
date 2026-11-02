package cool.linker.cam.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import cool.linker.cam.design.Tokens

/** The design tokens from the shared module, as Compose values. */
object LcColors {
    val Surround = Color(Tokens.Color.SURROUND)
    val Ink = Color(Tokens.Color.INK)
    val InkRaised = Color(Tokens.Color.INK_RAISED)
    val InkLine = Color(Tokens.Color.INK_LINE)
    val Paper = Color(Tokens.Color.PAPER)
    val Muted = Color(Tokens.Color.MUTED)
    val Faint = Color(Tokens.Color.FAINT)
    val Brand = Color(Tokens.Color.BRAND)
    val Crimson = Color(Tokens.Color.CRIMSON)
    val BrandBright = Color(Tokens.Color.BRAND_BRIGHT)
    val CrimsonBright = Color(Tokens.Color.CRIMSON_BRIGHT)
}

object LcSize {
    val Radius = Tokens.Size.RADIUS.dp
    val RadiusChip = Tokens.Size.RADIUS_CHIP.dp
    val HitTarget = Tokens.Size.HIT_TARGET.dp
    val Rail = Tokens.Size.RAIL.dp
    val RailItem = Tokens.Size.RAIL_ITEM.dp
}

/**
 * Type in the device's own font, like linker.cool. Every number is set in
 * tabular figures so readouts don't jitter as they change.
 */
object LcType {
    private const val TABULAR = "tnum"

    /** The one big line on the arrival screen. */
    val Display = TextStyle(color = LcColors.Paper, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.9).sp)
    val Title = TextStyle(color = LcColors.Paper, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    val Body = TextStyle(color = LcColors.Muted, fontSize = 15.sp, lineHeight = 22.sp)
    val Label = TextStyle(color = LcColors.Paper, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val Toggle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp, fontFeatureSettings = TABULAR)
    val Readout = TextStyle(color = LcColors.Paper, fontSize = 12.sp, fontFeatureSettings = TABULAR)
    val CameraValue = TextStyle(color = LcColors.Paper, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR)

    /** Shutter speed and aperture, the two numbers read at a glance while shooting. */
    val CameraValueLarge = TextStyle(color = LcColors.Paper, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR)
    val Legend = TextStyle(color = LcColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
}

@Composable
fun LinkCamTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(LcColors.BrandBright, LcColors.BrandBright.copy(alpha = 0.3f)),
        content = content,
    )
}
