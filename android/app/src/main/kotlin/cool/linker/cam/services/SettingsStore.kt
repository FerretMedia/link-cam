package cool.linker.cam.services

import android.content.Context
import cool.linker.cam.tools.ToolState
import kotlinx.serialization.json.Json

/** Keeps the tool settings between launches, as JSON in SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun loadTools(): ToolState =
        prefs.getString(KEY_TOOLS, null)
            ?.let { runCatching { json.decodeFromString<ToolState>(it) }.getOrNull() }
            ?: ToolState()

    fun saveTools(state: ToolState) {
        prefs.edit().putString(KEY_TOOLS, json.encodeToString(state)).apply()
    }

    private companion object {
        const val KEY_TOOLS = "tools.v1"
    }
}
