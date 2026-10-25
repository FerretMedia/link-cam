package cool.linker.cam.services

import android.content.Context
import cool.linker.cam.camera.SavedCamera
import cool.linker.cam.tools.ToolState
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.util.UUID

/** Keeps the tool settings and the paired cameras between launches, in SharedPreferences. */
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

    /**
     * The cameras this phone has paired with, Wi-Fi passwords included. Kept
     * in the app's private storage, like any Wi-Fi password the phone knows.
     */
    fun loadCameras(): List<SavedCamera> =
        prefs.getString(KEY_CAMERAS, null)
            ?.let { runCatching { json.decodeFromString<List<SavedCamera>>(it) }.getOrNull() }
            .orEmpty()

    fun saveCameras(cameras: List<SavedCamera>) {
        prefs.edit().putString(KEY_CAMERAS, json.encodeToString(cameras)).apply()
    }

    /** False once the user disconnects: Link Cam then leaves cameras alone until asked to connect. */
    fun loadCameraAutoConnect(): Boolean = prefs.getBoolean(KEY_AUTO_CONNECT, true)

    fun saveCameraAutoConnect(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, on).apply()
    }

    /**
     * Who this phone is to a camera, made once and kept: a camera remembers
     * the devices it has said OK to by this, so it must not change.
     */
    fun clientGuid(): ByteArray {
        prefs.getString(KEY_GUID, null)?.let { hex -> return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
        val uuid = UUID.randomUUID()
        val bytes = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        prefs.edit().putString(KEY_GUID, bytes.joinToString("") { "%02x".format(it) }).apply()
        return bytes
    }

    private companion object {
        const val KEY_TOOLS = "tools.v1"
        const val KEY_CAMERAS = "cameras.v1"
        const val KEY_AUTO_CONNECT = "cameras.autoConnect"
        const val KEY_GUID = "camera.clientGuid"
    }
}
