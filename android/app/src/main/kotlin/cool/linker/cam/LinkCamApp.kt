package cool.linker.cam

import android.app.Application
import cool.linker.cam.camera.CameraLink
import cool.linker.cam.services.SettingsStore
import cool.linker.cam.services.UsbService
import cool.linker.cam.source.SourceManager
import cool.linker.cam.tools.ToolsManager

/**
 * Holds the managers for the life of the process, so a live USB connection
 * survives rotation and the activity being recreated.
 */
class LinkCamApp : Application() {
    lateinit var sources: SourceManager
        private set
    lateinit var tools: ToolsManager
        private set
    lateinit var camera: CameraLink
        private set

    override fun onCreate() {
        super.onCreate()
        sources = SourceManager(this, UsbService(this))
        val store = SettingsStore(this)
        tools = ToolsManager(store)
        camera = CameraLink(this, store)
    }
}
