package cool.linker.cam.tools

import cool.linker.cam.services.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Owns the monitoring tools' settings. Every change is saved straight away. */
class ToolsManager(private val store: SettingsStore) {
    private val _state = MutableStateFlow(store.loadTools())
    val state: StateFlow<ToolState> = _state.asStateFlow()

    fun toggleFalseColor() = change { it.copy(falseColor = !it.falseColor) }
    fun toggleZebras() = change { it.copy(zebras = !it.zebras) }
    fun togglePeaking() = change { it.copy(peaking = !it.peaking) }
    fun toggleGuides() = change { it.copy(guides = !it.guides) }
    fun toggleSafeAreas() = change { it.copy(safeAreas = !it.safeAreas) }
    fun togglePunchIn() = change { it.copy(punchIn = !it.punchIn) }

    fun setZebraLevel(level: Int) = change { it.copy(zebraLevel = level, zebras = true) }
    fun setPeakingColor(color: PeakingColor) = change { it.copy(peakingColor = color, peaking = true) }
    fun setPeakingSensitivity(s: PeakingSensitivity) = change { it.copy(peakingSensitivity = s, peaking = true) }
    fun setGuideAspect(aspect: GuideAspect) = change { it.copy(guideAspect = aspect, guides = true) }

    private fun change(transform: (ToolState) -> ToolState) {
        _state.update(transform)
        store.saveTools(_state.value)
    }
}
