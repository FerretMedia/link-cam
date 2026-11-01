package cool.linker.cam.ui.monitor

import android.app.Activity
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cool.linker.cam.camera.CameraLink
import cool.linker.cam.camera.CameraLinkState
import cool.linker.cam.camera.ScreenReader
import cool.linker.cam.camera.WifiScreen
import cool.linker.cam.camera.cameraSettings
import cool.linker.cam.camera.open
import cool.linker.cam.model.ColorSpec
import cool.linker.cam.model.NoiseReduction
import cool.linker.cam.model.SourceFormat
import cool.linker.cam.render.MonitorRenderer
import cool.linker.cam.source.FrameSignal
import cool.linker.cam.source.SourceState
import cool.linker.cam.tools.GuideAspect
import cool.linker.cam.tools.Guides
import cool.linker.cam.tools.PeakingColor
import cool.linker.cam.tools.PeakingSensitivity
import cool.linker.cam.tools.ScopeKind
import cool.linker.cam.tools.ToolState
import cool.linker.cam.tools.ToolsManager
import cool.linker.cam.ui.components.CogGlyph
import cool.linker.cam.ui.components.OptionGroup
import cool.linker.cam.ui.components.RailDivider
import cool.linker.cam.ui.components.ToolSheet
import cool.linker.cam.ui.components.ToolToggle
import cool.linker.cam.ui.settings.SettingsScreen
import cool.linker.cam.ui.theme.LcColors
import cool.linker.cam.ui.theme.LcSize
import cool.linker.cam.ui.theme.LcType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private enum class Sheet { ZEBRAS, PEAKING, SCOPE, GUIDES }

/**
 * One screen: the picture, a slim rail of tools beside it, and a status strip.
 * The rail sits next to the picture, never over it, so nothing hides the edges
 * of frame. The exposure graph and the camera's settings float over the
 * picture, see-through, like a camera's own info display. Tap the picture to
 * hide everything else and give it the whole screen.
 */
@Composable
fun MonitorScreen(
    live: SourceState.Live,
    tools: ToolState,
    toolsManager: ToolsManager,
    camera: CameraLink,
    frames: FrameSignal,
    onSelectFormat: (SourceFormat) -> Unit,
    onColorSpec: (ColorSpec) -> Unit,
    onDisconnect: () -> Unit,
) {
    val view = LocalView.current
    val renderer = remember { MonitorRenderer(view.context.assets) }
    var chrome by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var settings by remember { mutableStateOf(false) }

    // Off while settings are open: they close on Back themselves, and which of two
    // live handlers wins isn't something to leave to chance when losing means disconnecting.
    BackHandler(enabled = !settings) {
        if (sheet != null) sheet = null else onDisconnect()
    }
    Immersive()

    BoxWithConstraints(Modifier.fillMaxSize().background(LcColors.Surround)) {
        val landscape = maxWidth > maxHeight
        val scopes by renderer.scopes.collectAsState()
        val link by camera.state.collectAsState()
        val phoneBattery = rememberPhoneBattery()
        WatchForCameraWifi(renderer, camera, link, eager = settings)
        val tapToToggle = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = "Show or hide controls",
        ) {
            if (sheet != null) sheet = null else chrome = !chrome
        }

        // The picture keeps its place in the tree whatever the chrome does, so
        // the GL surface is resized rather than rebuilt.
        val pictureArea = if (!chrome) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(end = if (landscape) LcSize.Rail else 0.dp, bottom = if (landscape) 0.dp else LcSize.Rail)
        }
        Box(pictureArea) {
            MonitorSurface(live.source, live.colorSpec, tools, frames, renderer, Modifier.fillMaxSize())
            GuidesOverlay(live.source.format.aspect, tools, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().then(tapToToggle))
            // A tap hides the rail only: the scope, the camera's settings and the batteries are
            // what's being watched, so they stay.
            OnPicture(live.source.format.aspect) {
                if (tools.scope) {
                    ScopePanel(
                        reading = scopes,
                        kind = tools.scopeKind,
                        graphHeight = ScopeGraphHeight,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).width(ScopeWidth),
                    )
                }
                CameraReadout(link, Modifier.align(Alignment.BottomCenter).padding(8.dp))
                PowerReadout(
                    phone = phoneBattery,
                    camera = link.cameraSettings?.battery,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }
        }

        if (chrome) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                StatusStrip(live, tools, renderer, Modifier.align(Alignment.TopStart).padding(8.dp))
                ToolRail(
                    tools = tools,
                    manager = toolsManager,
                    onSettings = {
                        sheet = null
                        settings = true
                    },
                    vertical = landscape,
                    onSheet = { sheet = if (sheet == it) null else it },
                    modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter),
                )
                sheet?.let { open ->
                    Box(
                        Modifier
                            .align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
                            .padding(
                                end = if (landscape) LcSize.Rail else 0.dp,
                                bottom = if (landscape) 0.dp else LcSize.Rail,
                            )
                            .padding(8.dp),
                    ) {
                        when (open) {
                            Sheet.ZEBRAS -> ToolSheet("Zebras") {
                                OptionGroup(
                                    legend = "Level (IRE)",
                                    options = ToolState.ZEBRA_LEVELS.map { it to "$it" },
                                    value = tools.zebraLevel,
                                    onChange = toolsManager::setZebraLevel,
                                )
                            }
                            Sheet.PEAKING -> ToolSheet("Focus peaking") {
                                OptionGroup("Colour", PeakingColor.entries.map { it to it.label }, tools.peakingColor, toolsManager::setPeakingColor)
                                OptionGroup("Sensitivity", PeakingSensitivity.entries.map { it to it.label }, tools.peakingSensitivity, toolsManager::setPeakingSensitivity)
                            }
                            Sheet.SCOPE -> ToolSheet("Exposure scope") {
                                OptionGroup("Graph", ScopeKind.entries.map { it to it.label }, tools.scopeKind, toolsManager::setScopeKind)
                            }
                            Sheet.GUIDES -> ToolSheet("Frame lines") {
                                OptionGroup("Aspect", GuideAspect.entries.map { it to it.label }, tools.guideAspect, toolsManager::setGuideAspect)
                            }
                        }
                    }
                }
            }
        }

        if (settings) {
            SettingsScreen(
                live = live,
                tools = tools,
                toolsManager = toolsManager,
                camera = camera,
                link = link,
                onSelectFormat = onSelectFormat,
                onColorSpec = onColorSpec,
                onDisconnectSource = onDisconnect,
                onClose = { settings = false },
            )
        }
    }
}

@Composable
private fun ToolRail(
    tools: ToolState,
    manager: ToolsManager,
    onSettings: () -> Unit,
    vertical: Boolean,
    onSheet: (Sheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items: @Composable () -> Unit = {
        ToolToggle("FC", "False color", tools.falseColor, manager::toggleFalseColor)
        ToolToggle("ZEB", "Zebras", tools.zebras, manager::toggleZebras, onSettings = { onSheet(Sheet.ZEBRAS) })
        ToolToggle("PK", "Focus peaking", tools.peaking, manager::togglePeaking, onSettings = { onSheet(Sheet.PEAKING) })
        ToolToggle(tools.scopeKind.short, "Exposure scope", tools.scope, manager::toggleScope, onSettings = { onSheet(Sheet.SCOPE) })
        RailDivider(vertical)
        ToolToggle("GDE", "Frame lines", tools.guides, manager::toggleGuides, onSettings = { onSheet(Sheet.GUIDES) })
        ToolToggle("SAFE", "Safe areas", tools.safeAreas, manager::toggleSafeAreas)
        ToolToggle("2×", "Punch in", tools.punchIn, manager::togglePunchIn)
        RailDivider(vertical)
        ToolToggle(
            "SET",
            "Settings",
            active = false,
            onToggle = onSettings,
            isSwitch = false,
            glyph = { CogGlyph(it) },
        )
    }
    val rail = modifier.background(LcColors.InkRaised)
    if (vertical) {
        Column(
            rail.width(LcSize.Rail).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { items() }
    } else {
        Row(
            rail.height(LcSize.Rail).fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) { items() }
    }
}

private val ScopeWidth = 184.dp
private val ScopeGraphHeight = 84.dp

/** Lays [content] out over the picture itself, not the black around it. */
@Composable
private fun OnPicture(aspect: Float, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fit = Guides.fit(maxWidth.value, maxHeight.value, aspect)
        Box(
            Modifier
                .offset(fit.left.dp, fit.top.dp)
                .size(fit.width.dp, fit.height.dp),
            content = content,
        )
    }
}

/** Format · colour interpretation · what the pipeline is doing, refreshed twice a second. */
@Composable
private fun StatusStrip(live: SourceState.Live, tools: ToolState, renderer: MonitorRenderer, modifier: Modifier = Modifier) {
    var readout by remember { mutableStateOf("") }
    LaunchedEffect(live.source, renderer) {
        while (true) {
            val s = renderer.stats
            val src = live.source.stats()
            readout = buildString {
                append("%.1f fps".format(s.presentedFps))
                append(" · %.1f ms".format(s.latencyMs))
                if (s.decodeMs > 0f) append(" · decode %.1f ms".format(s.decodeMs))
                append(" · dropped ${src.dropped}")
                if (src.errors > 0) append(" · errors ${src.errors}")
            }
            delay(500)
        }
    }
    Column(
        modifier.background(LcColors.InkRaised).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        BasicText(
            "${live.name} · ${live.source.format.label} · ${live.colorSpec.label} · ${tools.signal.short}" +
                if (tools.noiseReduction != NoiseReduction.OFF) " · NR ${tools.noiseReduction.label.lowercase()}" else "",
            style = LcType.Readout,
        )
        BasicText(readout, style = LcType.Readout.copy(color = LcColors.Muted))
    }
}

/** Hides the system bars and keeps the screen on while the monitor is up. */
@Composable
private fun Immersive() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
}

/**
 * While no camera is linked, looks at the picture for the camera's Wi-Fi
 * screen and pairs from it, so pairing is only a matter of opening that
 * screen on the camera. Reads often while [eager], with the camera setup
 * open; otherwise every few seconds, to spare the battery. Always at full
 * size: at half, a password's letters run together and pick up stray marks.
 */
@Composable
private fun WatchForCameraWifi(renderer: MonitorRenderer, camera: CameraLink, link: CameraLinkState, eager: Boolean) {
    val reader = remember { ScreenReader(renderer::snapshot) }
    DisposableEffect(reader) { onDispose { reader.close() } }
    val open = link.open
    LaunchedEffect(open, eager) {
        if (!open) return@LaunchedEffect
        var previous: WifiScreen.Credentials? = null
        while (true) {
            val read = try {
                reader.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LinkCamCamera", "couldn't read the picture", e)
                null
            }
            // The same reading twice over, so a half-drawn menu or a misread letter doesn't count.
            if (read != null && read == previous) camera.offerFromScreen(read)
            previous = read
            delay(if (eager) 700 else 3_000)
        }
    }
}
