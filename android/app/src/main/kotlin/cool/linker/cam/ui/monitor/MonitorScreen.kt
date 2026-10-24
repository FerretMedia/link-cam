package cool.linker.cam.ui.monitor

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import cool.linker.cam.model.ColorSpec
import cool.linker.cam.model.SourceFormat
import cool.linker.cam.render.MonitorRenderer
import cool.linker.cam.source.FrameSignal
import cool.linker.cam.source.SourceState
import cool.linker.cam.tools.GuideAspect
import cool.linker.cam.tools.PeakingColor
import cool.linker.cam.tools.PeakingSensitivity
import cool.linker.cam.tools.ToolState
import cool.linker.cam.tools.ToolsManager
import cool.linker.cam.ui.components.LcButton
import cool.linker.cam.ui.components.OptionGroup
import cool.linker.cam.ui.components.RailDivider
import cool.linker.cam.ui.components.ToolSheet
import cool.linker.cam.ui.components.ToolToggle
import cool.linker.cam.ui.theme.LcColors
import cool.linker.cam.ui.theme.LcSize
import cool.linker.cam.ui.theme.LcType
import kotlinx.coroutines.delay

private enum class Sheet { ZEBRAS, PEAKING, GUIDES, SOURCE }

/**
 * One screen: the picture edge to edge, a slim rail of tools, and a status
 * strip. Tap the picture to hide or show everything else.
 */
@Composable
fun MonitorScreen(
    live: SourceState.Live,
    tools: ToolState,
    toolsManager: ToolsManager,
    frames: FrameSignal,
    onSelectFormat: (SourceFormat) -> Unit,
    onColorSpec: (ColorSpec) -> Unit,
    onDisconnect: () -> Unit,
) {
    val view = LocalView.current
    val renderer = remember { MonitorRenderer(view.context.assets) }
    var chrome by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    BackHandler {
        if (sheet != null) sheet = null else onDisconnect()
    }
    Immersive()

    BoxWithConstraints(Modifier.fillMaxSize().background(LcColors.Surround)) {
        val landscape = maxWidth > maxHeight
        val tapToToggle = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = "Show or hide controls",
        ) {
            if (sheet != null) sheet = null else chrome = !chrome
        }

        MonitorSurface(live.source, live.colorSpec, tools, frames, renderer, Modifier.fillMaxSize())
        GuidesOverlay(live.source.format.aspect, tools, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().then(tapToToggle))

        if (chrome) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                StatusStrip(live, renderer, Modifier.align(Alignment.TopStart).padding(8.dp))
                ToolRail(
                    tools = tools,
                    manager = toolsManager,
                    vertical = landscape,
                    onSheet = { sheet = if (sheet == it) null else it },
                    modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter),
                )
                sheet?.let { open ->
                    Box(
                        Modifier
                            .align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
                            .padding(
                                end = if (landscape) LcSize.Rail + 8.dp else 0.dp,
                                bottom = if (landscape) 0.dp else LcSize.Rail + 8.dp,
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
                            Sheet.GUIDES -> ToolSheet("Frame lines") {
                                OptionGroup("Aspect", GuideAspect.entries.map { it to it.label }, tools.guideAspect, toolsManager::setGuideAspect)
                            }
                            Sheet.SOURCE -> SourceSheet(live, onSelectFormat, onColorSpec, onDisconnect)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRail(
    tools: ToolState,
    manager: ToolsManager,
    vertical: Boolean,
    onSheet: (Sheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items: @Composable () -> Unit = {
        ToolToggle("FC", "False color", tools.falseColor, manager::toggleFalseColor)
        ToolToggle("ZEB", "Zebras", tools.zebras, manager::toggleZebras, onSettings = { onSheet(Sheet.ZEBRAS) })
        ToolToggle("PK", "Focus peaking", tools.peaking, manager::togglePeaking, onSettings = { onSheet(Sheet.PEAKING) })
        RailDivider(vertical)
        ToolToggle("GDE", "Frame lines", tools.guides, manager::toggleGuides, onSettings = { onSheet(Sheet.GUIDES) })
        ToolToggle("SAFE", "Safe areas", tools.safeAreas, manager::toggleSafeAreas)
        ToolToggle("2×", "Punch in", tools.punchIn, manager::togglePunchIn)
        RailDivider(vertical)
        ToolToggle("SRC", "Source and format", false, onToggle = { onSheet(Sheet.SOURCE) }, isSwitch = false)
    }
    val rail = modifier.background(LcColors.InkRaised)
    if (vertical) {
        Column(
            rail.width(LcSize.Rail).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { items() }
    } else {
        Row(
            rail.height(LcSize.Rail).fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) { items() }
    }
}

/** Format · colour interpretation · what the pipeline is doing, refreshed twice a second. */
@Composable
private fun StatusStrip(live: SourceState.Live, renderer: MonitorRenderer, modifier: Modifier = Modifier) {
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
        BasicText("${live.name} · ${live.source.format.label} · ${live.colorSpec.label}", style = LcType.Readout)
        BasicText(readout, style = LcType.Readout.copy(color = LcColors.Muted))
    }
}

@Composable
private fun SourceSheet(
    live: SourceState.Live,
    onSelectFormat: (SourceFormat) -> Unit,
    onColorSpec: (ColorSpec) -> Unit,
    onDisconnect: () -> Unit,
) {
    ToolSheet("Source") {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (live.formats.size > 1) {
                OptionGroup(
                    legend = "Format",
                    options = live.formats.distinct().map { it to it.label },
                    value = live.source.format,
                    onChange = onSelectFormat,
                )
            }
            OptionGroup(
                legend = "Matrix",
                options = ColorSpec.Matrix.entries.map { it to it.label },
                value = live.colorSpec.matrix,
                onChange = { onColorSpec(live.colorSpec.copy(matrix = it)) },
            )
            OptionGroup(
                legend = "Range",
                options = ColorSpec.Range.entries.map { it to it.label },
                value = live.colorSpec.range,
                onChange = { onColorSpec(live.colorSpec.copy(range = it)) },
            )
            LcButton("Disconnect", onClick = onDisconnect, modifier = Modifier.fillMaxWidth())
        }
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
