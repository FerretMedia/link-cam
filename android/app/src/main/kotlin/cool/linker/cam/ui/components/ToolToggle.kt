package cool.linker.cam.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cool.linker.cam.ui.theme.LcColors
import cool.linker.cam.ui.theme.LcSize
import cool.linker.cam.ui.theme.LcType

/**
 * A tool on the rail: a short label that turns blue with an underline when the
 * tool is on. Tap toggles; long-press opens the tool's settings, when it has
 * any.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolToggle(
    label: String,
    description: String,
    active: Boolean,
    onToggle: () -> Unit,
    onSettings: (() -> Unit)? = null,
    /** False for a rail button that opens something rather than switching a tool. */
    isSwitch: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .size(LcSize.HitTarget)
            .semantics {
                contentDescription = description
                if (isSwitch) stateDescription = if (active) "On" else "Off"
            }
            .combinedClickable(
                role = if (isSwitch) Role.Switch else Role.Button,
                onClick = {
                    haptics.performHapticFeedback(
                        when {
                            !isSwitch -> HapticFeedbackType.ContextClick
                            active -> HapticFeedbackType.ToggleOff
                            else -> HapticFeedbackType.ToggleOn
                        },
                    )
                    onToggle()
                },
                onLongClickLabel = if (onSettings != null) "$description settings" else null,
                onLongClick = onSettings?.let { open ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        open()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = LcType.Toggle.copy(color = if (active) LcColors.BrandBright else LcColors.Muted))
        if (active) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(20.dp)
                    .height(2.dp)
                    .background(LcColors.BrandBright),
            )
        }
    }
}

/** The rail's divider between groups of tools. */
@Composable
fun RailDivider(vertical: Boolean) {
    Box(
        (if (vertical) Modifier.width(24.dp).height(1.dp) else Modifier.height(24.dp).width(1.dp))
            .background(LcColors.InkLine),
    )
}
