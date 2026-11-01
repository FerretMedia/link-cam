package cool.linker.cam.ui.arrival

import android.hardware.usb.UsbDevice
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cool.linker.cam.services.displayName
import cool.linker.cam.source.Banner
import cool.linker.cam.ui.components.BrandSquares
import cool.linker.cam.ui.components.StatusBanner
import cool.linker.cam.ui.components.Wordmark
import cool.linker.cam.ui.theme.LcColors
import cool.linker.cam.ui.theme.LcSize
import cool.linker.cam.ui.theme.LcType

/**
 * Where people arrive: nothing is live yet. It lists any capture device that's
 * plugged in, or explains how to plug one in, and offers the test pattern.
 *
 * Held sideways it's two columns, what this is on the left and what to do on
 * the right, so both fit a phone's short height without scrolling; upright
 * they stack.
 */
@Composable
fun ArrivalScreen(
    devices: List<UsbDevice>,
    banner: Banner?,
    connectingTo: String?,
    onConnect: (UsbDevice) -> Unit,
    onTestPattern: () -> Unit,
) {
    val state = when {
        connectingTo != null -> ArrivalState.Connecting(connectingTo)
        devices.isEmpty() -> ArrivalState.Waiting
        else -> ArrivalState.Ready(devices)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideways = maxWidth > maxHeight && maxWidth >= 600.dp
        BrandSquares()
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (sideways) {
                Row(
                    Modifier.widthIn(max = 920.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Intro(state, Modifier.weight(1f))
                    Actions(state, banner, onConnect, onTestPattern, Modifier.weight(1f))
                }
            } else {
                Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    Intro(state)
                    Actions(state, banner, onConnect, onTestPattern)
                }
            }
        }
    }
}

private sealed interface ArrivalState {
    data object Waiting : ArrivalState
    data class Ready(val devices: List<UsbDevice>) : ArrivalState
    data class Connecting(val name: String) : ArrivalState
}

@Composable
private fun Intro(state: ArrivalState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Wordmark(size = 28.dp)
        Spacer(Modifier.height(36.dp))
        when (state) {
            ArrivalState.Waiting -> StatusLine("Waiting for a capture device", lit = false)
            is ArrivalState.Ready -> StatusLine(
                if (state.devices.size == 1) "Capture device found" else "${state.devices.size} capture devices found",
                lit = true,
            )
            is ArrivalState.Connecting -> StatusLine("Connecting", lit = true, pulse = true)
        }
        Spacer(Modifier.height(12.dp))
        BasicText(
            when (state) {
                ArrivalState.Waiting -> "Plug in your camera"
                is ArrivalState.Ready -> "Ready to monitor"
                is ArrivalState.Connecting -> "Opening ${state.name}…"
            },
            style = LcType.Display,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        BasicText(
            when (state) {
                is ArrivalState.Connecting -> "If Android asks, allow Link Cam to use it."
                else -> "Your camera's HDMI picture on this screen, with exposure and focus tools over it. " +
                    "Nothing here changes the recording."
            },
            style = LcType.Body,
        )
    }
}

/** A small caps line with a dot: brand blue when something is there, grey while waiting. */
@Composable
private fun StatusLine(text: String, lit: Boolean, pulse: Boolean = false) {
    val dot = if (pulse) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
            label = "dot",
        )
        a
    } else {
        1f
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(dot)
                .clip(CircleShape)
                .background(if (lit) LcColors.BrandBright else LcColors.Faint),
        )
        BasicText(text.uppercase(), style = LcType.Legend.copy(color = if (lit) LcColors.Paper else LcColors.Muted))
    }
}

@Composable
private fun Actions(
    state: ArrivalState,
    banner: Banner?,
    onConnect: (UsbDevice) -> Unit,
    onTestPattern: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            when (state) {
                ArrivalState.Waiting -> {
                    Step(1, "HDMI out of the camera into a capture dongle")
                    Step(2, "The dongle into this phone's USB-C port")
                    Step(3, "Link Cam finds it by itself")
                }
                is ArrivalState.Ready -> for (device in state.devices) {
                    ActionRow(
                        title = device.displayName,
                        detail = "USB video capture",
                        icon = { CaptureIcon() },
                        onClick = { onConnect(device) },
                        trailing = { ConnectPill() },
                    )
                }
                is ArrivalState.Connecting -> ActionRow(
                    title = state.name,
                    detail = "Opening the picture…",
                    icon = { CaptureIcon() },
                    onClick = null,
                    trailing = { Spinner() },
                )
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(1.dp).background(LcColors.InkLine))
            ActionRow(
                title = "Test pattern",
                detail = "1080p60 bars, to try the tools without a camera",
                icon = { BarsIcon() },
                onClick = onTestPattern,
                trailing = { Chevron() },
            )
        }
        banner?.let { StatusBanner(it, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LcColors.InkRaised.copy(alpha = 0.94f))
            .border(1.dp, LcColors.InkLine, shape)
            .padding(6.dp),
    ) { content() }
}

/** One thing to tap: the whole row is the target, the trailing mark only says what it does. */
@Composable
private fun ActionRow(
    title: String,
    detail: String,
    icon: @Composable () -> Unit,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .clip(RoundedCornerShape(LcSize.Radius))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = LcType.Label.copy(fontSize = LcType.Body.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(detail, style = LcType.Readout.copy(color = LcColors.Muted), maxLines = 2)
        }
        trailing()
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile { BasicText(number.toString(), style = LcType.CameraValue) }
        BasicText(text, style = LcType.Label.copy(color = LcColors.Paper), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun IconTile(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.size(44.dp).clip(shape).background(LcColors.Ink).border(1.dp, LcColors.InkLine, shape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ConnectPill() {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(LcColors.Paper)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        BasicText("Connect", style = LcType.Label.copy(color = LcColors.Ink))
    }
}

// --- glyphs, on a 24-unit grid like the app's own ------------------------------

/** A capture dongle: the body, its HDMI end and the USB tail. */
@Composable
private fun CaptureIcon(color: Color = LcColors.Paper) {
    Canvas(Modifier.size(22.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(color, Offset(5f * u, 7f * u), Size(12f * u, 10f * u), CornerRadius(2.5f * u), style = stroke)
        drawRoundRect(color, Offset(17f * u, 9.5f * u), Size(4f * u, 5f * u), CornerRadius(1f * u), style = stroke)
        drawLine(color, Offset(1.5f * u, 12f * u), Offset(5f * u, 12f * u), strokeWidth = 1.8f * u, cap = StrokeCap.Round)
        drawCircle(LcColors.BrandBright, radius = 1.3f * u, center = Offset(8.5f * u, 12f * u))
    }
}

/** Test bars, in greys: the chrome stays neutral. */
@Composable
private fun BarsIcon() {
    Canvas(Modifier.size(22.dp)) {
        val u = size.width / 24f
        val greys = listOf(LcColors.Paper, LcColors.Muted, LcColors.Faint, LcColors.InkLine)
        val barWidth = 4f * u
        greys.forEachIndexed { i, grey ->
            drawRoundRect(grey, Offset((3f + i * 4.6f) * u, 5f * u), Size(barWidth, 14f * u), CornerRadius(0.8f * u))
        }
    }
}

@Composable
private fun Chevron() {
    Canvas(Modifier.size(20.dp)) {
        val u = size.width / 24f
        val path = Path().apply {
            moveTo(9f * u, 6f * u)
            lineTo(15f * u, 12f * u)
            lineTo(9f * u, 18f * u)
        }
        drawPath(path, LcColors.Muted, style = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun Spinner() {
    val transition = rememberInfiniteTransition(label = "spin")
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "turn",
    )
    Canvas(Modifier.size(22.dp)) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        drawArc(LcColors.InkLine, 0f, 360f, useCenter = false, style = stroke)
        drawArc(LcColors.Paper, turn, 90f, useCenter = false, style = stroke)
    }
}
