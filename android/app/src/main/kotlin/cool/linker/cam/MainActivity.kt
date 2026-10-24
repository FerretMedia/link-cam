package cool.linker.cam

import android.Manifest
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cool.linker.cam.source.SourceManager
import cool.linker.cam.source.SourceState
import cool.linker.cam.tools.ToolsManager
import cool.linker.cam.ui.arrival.ArrivalScreen
import cool.linker.cam.ui.monitor.MonitorScreen
import cool.linker.cam.ui.theme.LinkCamTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as LinkCamApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleUsbIntent(intent)
        setContent {
            LinkCamTheme {
                LinkCam(app.sources, app.tools)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        app.sources.refreshDevices()
    }

    /** Opened because a capture device was plugged in: go straight to it. */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            ?.let(app.sources::connect)
    }
}

@Composable
private fun LinkCam(sources: SourceManager, toolsManager: ToolsManager) {
    val state by sources.state.collectAsStateWithLifecycle()
    val tools by toolsManager.state.collectAsStateWithLifecycle()

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        (sources.state.value as? SourceState.NeedsCameraPermission)?.let {
            sources.onCameraPermissionResult(it.device, granted)
        }
    }

    when (val s = state) {
        is SourceState.Live -> MonitorScreen(
            live = s,
            tools = tools,
            toolsManager = toolsManager,
            frames = sources.frames,
            onSelectFormat = sources::selectFormat,
            onColorSpec = sources::setColorSpec,
            onDisconnect = sources::disconnect,
        )
        is SourceState.Idle -> ArrivalScreen(s.devices, s.banner, null, sources::connect, sources::startTestPattern)
        is SourceState.Connecting -> ArrivalScreen(emptyList(), null, s.name, sources::connect, sources::startTestPattern)
        is SourceState.NeedsCameraPermission -> {
            LaunchedEffect(s.device) { cameraPermission.launch(Manifest.permission.CAMERA) }
            ArrivalScreen(emptyList(), null, s.device.productName ?: "the capture device", sources::connect, sources::startTestPattern)
        }
    }
}
