package cool.linker.cam.ui.components

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cool.linker.cam.design.Tokens
import cool.linker.cam.ui.theme.LcColors
import kotlin.random.Random

/**
 * Ferret Media's brand squares, as on linker.cool and in linker-app's
 * BrandBlobs: rounded blocks in the two brand colours at full strength,
 * lightly blurred, each drifting on its own period, with grain over them.
 *
 * Blue keeps to the left edge and crimson to the right with ink between,
 * because laid over each other the two go violet-grey. Only on the arrival
 * screen; the monitor itself carries no brand.
 */
@Composable
fun BrandSquares(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // "Remove animations" sets the animator scale to 0; the squares then stay put.
    val animate = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val grain = remember { grainBrush() }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // The grain's overlay blend needs the ink and the squares beneath it
            // in the same layer, or it blends against transparency and shows
            // everywhere. So the layer comes first and the ink is drawn inside it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .background(LcColors.Ink),
    ) {
        val w = maxWidth
        val h = maxHeight
        val wide = w > 600.dp
        // x, y, width, height and colour, after BrandBlobs. On a narrow screen
        // only a strip of one square shows at each edge.
        val squares = buildList {
            add(Square(-w * 0.12f, -h * 0.06f, if (wide) 380.dp else 190.dp, if (wide) 260.dp else 150.dp, LcColors.Brand, 0))
            if (wide) add(Square(-w * 0.14f, h * 0.62f, 260.dp, 300.dp, LcColors.Brand, 1))
            add(Square(w - (if (wide) 340.dp else 180.dp) + w * 0.07f, h * 0.14f, if (wide) 340.dp else 180.dp, if (wide) 220.dp else 140.dp, LcColors.Crimson, 2))
            if (wide) add(Square(w - 400.dp + w * 0.05f, h * 0.84f, 400.dp, 280.dp, LcColors.Crimson, 3))
        }
        val transition = rememberInfiniteTransition(label = "drift")
        for (s in squares) {
            val drift by transition.animateFloat(
                initialValue = 0f,
                targetValue = if (animate) 1f else 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(Tokens.Motion.DRIFT_PERIODS_MS[s.motion] / 2, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(Tokens.Motion.DRIFT_DELAYS_MS[s.motion]),
                ),
                label = "square${s.motion}",
            )
            Box(
                Modifier
                    .offset(s.x + (Tokens.Motion.DRIFT_X * drift).dp, s.y + (Tokens.Motion.DRIFT_Y * drift).dp)
                    .size(s.width, s.height)
                    // Unbounded, so the blur spreads past the square's own edges.
                    // Android 12+; earlier versions draw the squares sharp.
                    .blur(24.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    .clip(RoundedCornerShape(12.dp))
                    .background(s.color),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(grain, alpha = 0.7f, blendMode = BlendMode.Overlay)
        }
    }
}

private class Square(val x: Dp, val y: Dp, val width: Dp, val height: Dp, val color: Color, val motion: Int)

/** Grey noise, tiled: the grain ferretmedia's site lays over its squares. */
private fun grainBrush(): Brush {
    val size = 160
    val random = Random(7)
    val pixels = IntArray(size * size) {
        val v = random.nextInt(256)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    return ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}
