package cool.linker.cam.ui.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import cool.linker.cam.tools.Guides
import cool.linker.cam.tools.Rect
import cool.linker.cam.tools.ToolState

/**
 * Frame lines and safe areas, drawn over the picture as vector lines rather
 * than in the shader: they stay sharp, cost nothing on the GPU, and can never
 * end up in a recording.
 */
@Composable
fun GuidesOverlay(pictureAspect: Float, tools: ToolState, modifier: Modifier = Modifier) {
    if (!tools.guides && !tools.safeAreas) return
    Canvas(modifier) {
        // The same fit the renderer uses, so the lines land on the picture.
        val picture = Guides.fit(size.width, size.height, pictureAspect)
        val stroke = 1.5.dp.toPx()

        if (tools.guides) {
            val frame = Guides.frameLine(picture, tools.guideAspect)
            // Darken what falls outside the frame line, then draw the line.
            shade(picture, frame)
            rect(frame, Color.White.copy(alpha = 0.85f), stroke)
        }
        if (tools.safeAreas) {
            rect(picture.inset(Guides.ACTION_SAFE), Color.White.copy(alpha = 0.5f), stroke)
            rect(picture.inset(Guides.TITLE_SAFE), Color.White.copy(alpha = 0.35f), stroke)
            val c = Offset(picture.left + picture.width / 2, picture.top + picture.height / 2)
            val arm = 12.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.7f), c.copy(x = c.x - arm), c.copy(x = c.x + arm), stroke)
            drawLine(Color.White.copy(alpha = 0.7f), c.copy(y = c.y - arm), c.copy(y = c.y + arm), stroke)
        }
    }
}

private fun DrawScope.rect(r: Rect, color: Color, stroke: Float) =
    drawRect(color, Offset(r.left, r.top), Size(r.width, r.height), style = Stroke(stroke))

private fun DrawScope.shade(outer: Rect, inner: Rect) {
    val mask = Color.Black.copy(alpha = 0.45f)
    drawRect(mask, Offset(outer.left, outer.top), Size(outer.width, inner.top - outer.top))
    drawRect(mask, Offset(outer.left, inner.bottom), Size(outer.width, outer.bottom - inner.bottom))
    drawRect(mask, Offset(outer.left, inner.top), Size(inner.left - outer.left, inner.height))
    drawRect(mask, Offset(inner.right, inner.top), Size(outer.right - inner.right, inner.height))
}
