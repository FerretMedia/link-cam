package cool.linker.cam.ui.settings

import android.text.format.DateUtils
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cool.linker.cam.camera.CameraLink
import cool.linker.cam.camera.CameraLinkState
import cool.linker.cam.camera.CameraWifi
import cool.linker.cam.camera.LinkStep
import cool.linker.cam.camera.SavedCamera
import cool.linker.cam.camera.pairing
import cool.linker.cam.ui.components.LcButton
import cool.linker.cam.ui.monitor.CameraReadout
import cool.linker.cam.ui.monitor.SheetField
import cool.linker.cam.ui.theme.LcColors
import cool.linker.cam.ui.theme.LcSize
import cool.linker.cam.ui.theme.LcType

private val ErrorRed = Color(0xFFF87171)
private val Hint = LcType.Body.copy(color = LcColors.Muted, fontSize = LcType.Readout.fontSize * 1.1f, lineHeight = LcType.Readout.fontSize * 1.7f)

/**
 * The camera link: the paired cameras, each with where it stands and what
 * can be done with it, or the pairing guide while a camera is being added.
 */
@Composable
fun CameraSetup(link: CameraLinkState, camera: CameraLink) {
    val cameras by camera.cameras.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }
    // A pairing that's done puts its camera in the list, which is where to see it.
    val connected = link is CameraLinkState.Connected
    LaunchedEffect(connected) { if (connected) adding = false }

    if (cameras.isEmpty() || adding || link.pairing) {
        PairingGuide(
            link = link,
            camera = camera,
            onBack = if (cameras.isEmpty()) null else ({
                camera.cancelPairing()
                adding = false
            }),
        )
    } else {
        CameraList(cameras, link, camera, onPair = { adding = true })
    }
}

// --- the paired cameras ------------------------------------------------------

@Composable
private fun CameraList(cameras: List<SavedCamera>, link: CameraLinkState, camera: CameraLink, onPair: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            listHeadline(link, camera.autoConnect),
            style = LcType.Label,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        // A way out at every moment: while looking, connecting, connected or retrying.
        if (link !is CameraLinkState.Off) LcButton("Disconnect", onClick = camera::disconnect)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (saved in cameras) CameraRow(saved, link, camera)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LcButton("Pair a camera", onClick = onPair)
        BasicText(
            "Each camera pairs once. After that, Link Cam connects to whichever of them is on, " +
                "most recently used first, as soon as you open Remote control (EOS Utility) on it.",
            style = Hint,
        )
    }
}

@Composable
private fun CameraRow(saved: SavedCamera, link: CameraLinkState, camera: CameraLink) {
    val mine = link.camera == saved.id
    val busy = mine && link !is CameraLinkState.Failed
    var confirmForget by remember(saved.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LcSize.Radius))
            .background(LcColors.InkRaised)
            .border(1.dp, if (mine && link is CameraLinkState.Connected) LcColors.BrandBright else LcColors.InkLine, RoundedCornerShape(LcSize.Radius))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(saved.name, style = LcType.Label)
                BasicText(
                    status(saved, link.takeIf { mine }),
                    style = Hint.copy(color = if (mine && link is CameraLinkState.Failed) ErrorRed else LcColors.Muted),
                )
            }
            if (!busy) LcButton("Connect", onClick = { camera.connect(saved) }, primary = mine || link is CameraLinkState.Off)
            // Forgetting means pairing again on the camera, so it asks twice.
            LcButton(
                if (confirmForget) "Tap again to forget" else "Forget",
                onClick = { if (confirmForget) camera.forget(saved) else confirmForget = true },
            )
        }
        if (mine && (link is CameraLinkState.Connected || link is CameraLinkState.Reconnecting)) CameraReadout(link)
    }
}

private fun status(saved: SavedCamera, link: CameraLinkState?): String = when (link) {
    is CameraLinkState.Connected -> "Connected" + (link.settings.battery?.let { " · battery $it%" } ?: "")
    is CameraLinkState.Reconnecting -> "${link.reason} Trying again…"
    is CameraLinkState.Searching -> link.hint
    is CameraLinkState.Connecting -> link.hint ?: "Connecting…"
    is CameraLinkState.Failed -> link.reason
    else -> buildString {
        if (saved.lastConnected > 0) {
            append("Last connected ")
            append(DateUtils.getRelativeTimeSpanString(saved.lastConnected, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
        }
        if (!saved.reconnectsQuietly) {
            if (isNotEmpty()) append(". ")
            append("Android asks before each connection; pair it again from its screen to stop that.")
        }
    }
}

private fun listHeadline(link: CameraLinkState, autoConnect: Boolean): String = when (link) {
    CameraLinkState.Off ->
        if (autoConnect) "Not connected." else "Disconnected. Link Cam leaves your cameras alone until you press Connect."
    is CameraLinkState.Waiting -> link.hint
    is CameraLinkState.Searching -> link.hint
    is CameraLinkState.Connecting -> link.hint ?: "Connecting…"
    is CameraLinkState.Connected -> "Connected to ${link.name}."
    is CameraLinkState.Reconnecting -> "Lost ${link.name}. Trying again…"
    is CameraLinkState.Failed -> "Not connected."
}

// --- pairing a camera --------------------------------------------------------

/** One step of pairing, in the order the user meets them. */
private enum class Step(val title: String) {
    PAIRING("Put the camera in pairing mode"),
    WIFI("Join the camera's Wi-Fi"),
    FIND("Find the camera"),
    HELLO("Connect"),
}

private enum class Mark { TODO, NOW, DONE, FAILED }

/**
 * Pairing as a guide: each step lights up as the link gets to it, says what
 * to do on the camera at that moment, and ticks off once done. A failure
 * shows on the step it happened at, with what to try. [onBack] leads back to
 * the paired cameras, when there are any.
 */
@Composable
private fun PairingGuide(link: CameraLinkState, camera: CameraLink, onBack: (() -> Unit)?) {
    val pairing = link.pairing
    val (current, failed) = if (pairing) position(link) else Step.PAIRING to false
    val reason = (link as? CameraLinkState.Failed)?.takeIf { pairing && !it.retrying }?.reason

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            guideHeadline(link),
            style = LcType.Label,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (onBack != null) LcButton("Back to your cameras", onClick = onBack)
        else if (pairing && !(link is CameraLinkState.Failed && !link.retrying)) LcButton("Stop", onClick = camera::cancelPairing)
    }

    Column {
        for (step in Step.entries) {
            val mark = when {
                failed && step == current -> Mark.FAILED
                step == current -> Mark.NOW
                step < current -> Mark.DONE
                else -> Mark.TODO
            }
            StepRow(step, mark, last = step == Step.entries.last()) {
                // Instructions show for the step in hand; the first two show together before
                // anything has started, since the user does them back to back.
                val open = mark == Mark.NOW || mark == Mark.FAILED || (!pairing && step <= Step.WIFI)
                if (open) StepBody(step, link, camera, reason.takeIf { mark == Mark.FAILED })
            }
        }
    }
}

/** Which step a pairing is at, and whether it's stuck there. */
private fun position(link: CameraLinkState): Pair<Step, Boolean> = when (link) {
    is CameraLinkState.Searching -> link.step.toStep() to false
    is CameraLinkState.Connecting -> link.step.toStep() to false
    // Retrying is the step still in hand, not stuck on it.
    is CameraLinkState.Failed -> link.step.toStep() to !link.retrying
    else -> Step.PAIRING to false
}

private fun LinkStep.toStep(): Step = when (this) {
    LinkStep.WIFI -> Step.WIFI
    LinkStep.FIND -> Step.FIND
    LinkStep.HELLO, LinkStep.LIVE -> Step.HELLO
}

private fun guideHeadline(link: CameraLinkState): String = when {
    !link.pairing -> "Pair a Canon camera once; after that Link Cam connects to it on its own."
    link is CameraLinkState.Searching -> link.hint
    link is CameraLinkState.Connecting -> link.hint ?: "Connecting…"
    link is CameraLinkState.Failed && link.retrying -> "${link.reason} Trying again…"
    else -> "Not connected yet."
}

@Composable
private fun StepBody(step: Step, link: CameraLinkState, camera: CameraLink, failure: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        failure?.let { BasicText(it, style = Hint.copy(color = ErrorRed)) }
        when (step) {
            Step.PAIRING -> BasicText(
                "On the camera: MENU → Wi-Fi function → Remote control (EOS Utility) → Pair a device. " +
                    "Choose Camera access point mode, then Easy connection. The camera then shows a Wi-Fi name and password, " +
                    "which Link Cam reads off the HDMI picture by itself.",
                style = Hint,
            )
            Step.WIFI -> WifiStep(link, camera)
            Step.FIND -> {
                if (link is CameraLinkState.Failed) {
                    var address by rememberSaveable { mutableStateOf("") }
                    BasicText("Or type the camera's address, which it shows under Remote control (EOS Utility):", style = Hint)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SheetField(
                            value = address,
                            onChange = { address = it },
                            onGo = { camera.connectTo(address) },
                            placeholder = "192.168.1.2",
                            description = "Camera address",
                            modifier = Modifier.weight(1f),
                        )
                        LcButton("Connect", onClick = { camera.connectTo(address) })
                    }
                    LcButton("Search again", onClick = camera::pairOnThisWifi, primary = true)
                } else {
                    BasicText("Link Cam is looking for the camera on its Wi-Fi.", style = Hint)
                }
            }
            Step.HELLO -> BasicText(
                "If the camera shows \"Start EOS Utility on the computer\", press Cancel on the camera. " +
                    "Link Cam connects on its next try, and the camera remembers it from then on.",
                style = Hint,
            )
        }
    }
}

@Composable
private fun WifiStep(link: CameraLinkState, camera: CameraLink) {
    if (link is CameraLinkState.Searching && link.step == LinkStep.WIFI) {
        BasicText("Android asks whether Link Cam may join the camera's Wi-Fi. Choose the camera's network.", style = Hint)
        return
    }
    var ssid by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val join = {
        if (password.isNotBlank()) camera.pair(CameraWifi(ssid.trim().ifEmpty { null }, password.trim()))
    }
    BasicText(
        "Nothing to type while the camera's Wi-Fi details are on the HDMI picture. Otherwise, type what the camera shows. " +
            "With the Wi-Fi name too, Link Cam later reconnects without Android asking. " +
            "Your phone keeps its internet over mobile data meanwhile.",
        style = Hint,
    )
    SheetField(
        value = ssid,
        onChange = { ssid = it },
        onGo = join,
        placeholder = "Wi-Fi name, like EOSR10_…_Canon0A",
        description = "Camera Wi-Fi name",
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SheetField(
            value = password,
            onChange = { password = it },
            onGo = join,
            placeholder = "Password",
            description = "Camera Wi-Fi password",
            secret = true,
            modifier = Modifier.weight(1f),
        )
        LcButton("Join", onClick = join, primary = true)
    }
    LcButton("The phone is already on the camera's Wi-Fi", onClick = camera::pairOnThisWifi)
}

/** A step: its mark on a line that runs down to the next one, then the title and, when open, the body. */
@Composable
private fun StepRow(step: Step, mark: Mark, last: Boolean, body: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StepMark(step.ordinal + 1, mark)
            if (!last) {
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .size(width = 2.dp, height = 18.dp)
                        .background(if (mark == Mark.DONE) LcColors.BrandBright else LcColors.InkLine),
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 2.dp, bottom = if (last) 0.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(
                step.title,
                style = LcType.Label.copy(
                    color = when (mark) {
                        Mark.TODO -> LcColors.Faint
                        Mark.FAILED -> ErrorRed
                        else -> LcColors.Paper
                    },
                ),
                modifier = Modifier.semantics {
                    stateDescription = when (mark) {
                        Mark.TODO -> "To do"
                        Mark.NOW -> "In progress"
                        Mark.DONE -> "Done"
                        Mark.FAILED -> "Needs attention"
                    }
                },
            )
            body()
        }
    }
}

@Composable
private fun StepMark(number: Int, mark: Mark) {
    val pulse by rememberInfiniteTransition(label = "step").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    val ring = when (mark) {
        Mark.TODO -> LcColors.InkLine
        Mark.NOW, Mark.DONE -> LcColors.BrandBright
        Mark.FAILED -> ErrorRed
    }
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (mark == Mark.DONE) LcColors.BrandBright else LcColors.Ink)
            .border(1.5.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (mark) {
            Mark.DONE -> BasicText("✓", style = LcType.Readout.copy(color = LcColors.Ink))
            Mark.FAILED -> BasicText("!", style = LcType.Readout.copy(color = ErrorRed))
            Mark.NOW -> Box(Modifier.size(8.dp).alpha(pulse).clip(CircleShape).background(LcColors.BrandBright))
            Mark.TODO -> BasicText("$number", style = LcType.Readout.copy(color = LcColors.Faint))
        }
    }
}
