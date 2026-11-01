package cool.linker.cam.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cool.linker.cam.camera.CameraLink
import cool.linker.cam.camera.CameraLinkState
import cool.linker.cam.camera.pairing
import cool.linker.cam.model.ColorSpec
import cool.linker.cam.model.NoiseReduction
import cool.linker.cam.model.Transfer
import cool.linker.cam.model.SourceFormat
import cool.linker.cam.source.SourceState
import cool.linker.cam.tools.GuideAspect
import cool.linker.cam.tools.PeakingColor
import cool.linker.cam.tools.PeakingSensitivity
import cool.linker.cam.tools.ScopeKind
import cool.linker.cam.tools.ToolState
import cool.linker.cam.tools.ToolsManager
import cool.linker.cam.ui.components.LcButton
import cool.linker.cam.ui.components.OptionGroup
import cool.linker.cam.ui.theme.LcColors
import cool.linker.cam.ui.theme.LcSize
import cool.linker.cam.ui.theme.LcType

private enum class Section(val title: String) { CAMERA("Camera"), TOOLS("Monitor tools"), SOURCE("Video source") }

/**
 * Everything that's set up rather than reached for while shooting: the
 * camera link, how the tools behave and the capture device's format. A large
 * panel over the picture, which shows dimmed around it; a tap outside or Back
 * closes it.
 */
@Composable
fun SettingsScreen(
    live: SourceState.Live,
    tools: ToolState,
    toolsManager: ToolsManager,
    camera: CameraLink,
    link: CameraLinkState,
    onSelectFormat: (SourceFormat) -> Unit,
    onColorSpec: (ColorSpec) -> Unit,
    onDisconnectSource: () -> Unit,
    onClose: () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(Section.CAMERA) }
    BackHandler(onBack = onClose)
    val panel = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(Scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Close settings",
                onClick = onClose,
            )
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .widthIn(max = 1100.dp)
                .clip(panel)
                .background(LcColors.Ink)
                .border(1.dp, LcColors.InkLine, panel)
                // Taps inside the panel stay in it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(
                Modifier.width(232.dp).fillMaxHeight().background(LcColors.InkRaised).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(LcSize.HitTarget)
                            .clip(RoundedCornerShape(LcSize.RadiusChip))
                            .clickable(role = Role.Button, onClickLabel = "Close settings", onClick = onClose)
                            .semantics { contentDescription = "Back to the monitor" },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText("←", style = LcType.Title.copy(fontSize = LcType.Label.fontSize * 1.4f))
                    }
                    BasicText("Settings", style = LcType.Title, modifier = Modifier.padding(start = 4.dp))
                }
                for (s in Section.entries) {
                    val summary = when (s) {
                        Section.CAMERA -> linkSummary(link)
                        Section.TOOLS -> toolsSummary(tools)
                        Section.SOURCE -> "${live.name} · ${tools.signal.short} · ${live.source.format.label}"
                    }
                    NavItem(s.title, summary, selected = s == section) { section = s }
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                BasicText(section.title, style = LcType.Title)
                Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    when (section) {
                        Section.CAMERA -> CameraSetup(link, camera)
                        Section.TOOLS -> ToolSettings(tools, toolsManager)
                        Section.SOURCE -> SourceSettings(live, tools, toolsManager, onSelectFormat, onColorSpec, onDisconnectSource)
                    }
                }
            }
        }
    }
}

private val Scrim = Color(0xB3000000)

private fun toolsSummary(tools: ToolState): String =
    listOfNotNull(
        "False colour".takeIf { tools.falseColor },
        "Zebras".takeIf { tools.zebras },
        "Peaking".takeIf { tools.peaking },
        tools.scopeKind.label.takeIf { tools.scope },
        "Frame lines".takeIf { tools.guides },
    ).joinToString(" · ").ifEmpty { "All off" }

/**
 * Each tool's settings, and whether it's on. The rail switches them while
 * shooting; this is the place to set them up.
 */
@Composable
private fun ToolSettings(tools: ToolState, manager: ToolsManager) {
    val onOff = listOf(false to "Off", true to "On")
    ToolBlock("Exposure scope", "A histogram or waveform over the picture, with how much is crushed or clipped.") {
        OptionGroup("Show", onOff, tools.scope, { on -> manager.update { it.copy(scope = on) } })
        OptionGroup("Graph", ScopeKind.entries.map { it to it.label }, tools.scopeKind, { k -> manager.update { it.copy(scopeKind = k) } })
    }
    ToolBlock("Zebras", "Stripes over everything brighter than the level.") {
        OptionGroup("Show", onOff, tools.zebras, { on -> manager.update { it.copy(zebras = on) } })
        OptionGroup("Level (IRE)", ToolState.ZEBRA_LEVELS.map { it to "$it" }, tools.zebraLevel, { l -> manager.update { it.copy(zebraLevel = l) } })
    }
    ToolBlock("Focus peaking", "Outlines what's in focus.") {
        OptionGroup("Show", onOff, tools.peaking, { on -> manager.update { it.copy(peaking = on) } })
        OptionGroup("Colour", PeakingColor.entries.map { it to it.label }, tools.peakingColor, { c -> manager.update { it.copy(peakingColor = c) } })
        OptionGroup("Sensitivity", PeakingSensitivity.entries.map { it to it.label }, tools.peakingSensitivity, { v -> manager.update { it.copy(peakingSensitivity = v) } })
    }
    ToolBlock("False colour", "Paints exposure levels in colours, from crushed blacks to clipped highlights.") {
        OptionGroup("Show", onOff, tools.falseColor, { on -> manager.update { it.copy(falseColor = on) } })
    }
    ToolBlock("Frame lines", "Where a crop for another aspect ratio falls, and the title and action safe areas.") {
        OptionGroup("Show", onOff, tools.guides, { on -> manager.update { it.copy(guides = on) } })
        OptionGroup("Aspect", GuideAspect.entries.map { it to it.label }, tools.guideAspect, { a -> manager.update { it.copy(guideAspect = a) } })
        OptionGroup("Safe areas", onOff, tools.safeAreas, { on -> manager.update { it.copy(safeAreas = on) } })
    }
}

@Composable
private fun ToolBlock(title: String, about: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LcSize.Radius))
            .background(LcColors.InkRaised)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = LcType.Label)
            BasicText(about, style = LcType.Body.copy(fontSize = LcType.Readout.fontSize * 1.1f))
        }
        content()
    }
}

@Composable
private fun NavItem(title: String, summary: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LcSize.RadiusChip))
            .background(if (selected) LcColors.InkLine else LcColors.InkRaised)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BasicText(title, style = LcType.Label.copy(color = if (selected) LcColors.Paper else LcColors.Muted))
        BasicText(summary, style = LcType.Readout.copy(color = LcColors.Faint), maxLines = 1)
    }
}

private fun linkSummary(link: CameraLinkState): String = when (link) {
    CameraLinkState.Off -> "Not connected"
    is CameraLinkState.Waiting -> "Waiting for a camera"
    is CameraLinkState.Searching -> if (link.pairing) "Pairing…" else "Connecting…"
    is CameraLinkState.Connecting -> "Connecting…"
    is CameraLinkState.Connected -> link.name
    is CameraLinkState.Reconnecting -> "Reconnecting to ${link.name}"
    is CameraLinkState.Failed -> "Not connected"
}

@Composable
private fun SourceSettings(
    live: SourceState.Live,
    tools: ToolState,
    toolsManager: ToolsManager,
    onSelectFormat: (SourceFormat) -> Unit,
    onColorSpec: (ColorSpec) -> Unit,
    onDisconnect: () -> Unit,
) {
    ToolBlock(
        "Picture",
        "Set Signal to what the camera records. In HDR PQ or HLG, Link Cam shows the footage the way DaVinci Resolve " +
            "turns it into Rec.709: 100 nits as white, highlights rolled off from 1000 nits. A capture dongle can't tell " +
            "Link Cam this itself. Noise reduction averages still parts of the picture over several frames and smooths " +
            "moving parts. Both settings change only what this screen shows, never the recording; leave noise reduction " +
            "off to judge how noisy the footage really is.",
    ) {
        OptionGroup("Signal", Transfer.entries.map { it to it.label }, tools.signal, { v -> toolsManager.update { it.copy(signal = v) } })
        OptionGroup(
            "Noise reduction",
            NoiseReduction.entries.map { it to it.label },
            tools.noiseReduction,
            { v -> toolsManager.update { it.copy(noiseReduction = v) } },
        )
    }
    BasicText(
        "${live.name} sends ${live.source.format.label}. Link Cam picks the best format on its own; " +
            "the colour settings only matter if the picture looks wrong." +
            if (tools.signal != Transfer.SDR) " In HDR the matrix is BT.2020's, as HDR video always uses." else "",
        style = LcType.Body,
    )
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
    LcButton("Disconnect ${live.name}", onClick = onDisconnect)
}
