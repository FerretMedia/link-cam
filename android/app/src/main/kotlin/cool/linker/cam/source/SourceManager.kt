package cool.linker.cam.source

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.util.Log
import androidx.core.content.ContextCompat
import cool.linker.cam.model.ColorSpec
import cool.linker.cam.model.FormatChoice
import cool.linker.cam.model.SourceFormat
import cool.linker.cam.services.UsbService
import cool.linker.cam.services.displayName
import cool.linker.cam.uvc.UvcDevice
import cool.linker.cam.uvc.UvcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A message for the top of a screen. */
data class Banner(val message: String, val isError: Boolean = false)

sealed interface SourceState {
    /** Nothing live: the arrival screen, listing any capture devices plugged in. */
    data class Idle(val devices: List<UsbDevice> = emptyList(), val banner: Banner? = null) : SourceState

    /** Android grants USB video devices only to apps with the camera permission; the UI asks. */
    data class NeedsCameraPermission(val device: UsbDevice) : SourceState

    data class Connecting(val name: String) : SourceState

    data class Live(
        val source: FrameSource,
        val name: String,
        val formats: List<SourceFormat>,
        val colorSpec: ColorSpec,
        /** The USB device path, to recognise it when it's unplugged. Null for the test pattern. */
        val usbDevice: String? = null,
    ) : SourceState
}

/**
 * Owns which source is live: finding capture devices, the two permissions
 * they need, opening them, switching formats, and tearing down when one is
 * unplugged. The renderer only ever sees the [FrameSource] in [SourceState.Live].
 */
class SourceManager(private val context: Context, private val usb: UsbService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<SourceState>(SourceState.Idle(usb.videoDevices()))
    val state: StateFlow<SourceState> = _state.asStateFlow()

    /** Wakes the renderer when a frame arrives. The monitor screen sets the listener. */
    val frames = FrameSignal()

    init {
        usb.watch(
            onAttached = { refreshDevices() },
            onDetached = { device -> onDetached(device) },
        )
    }

    fun refreshDevices() {
        _state.update { if (it is SourceState.Idle) it.copy(devices = usb.videoDevices()) else it }
    }

    fun startTestPattern() {
        disconnect()
        val source = TestPatternSource()
        source.start(frames::fire)
        _state.value = SourceState.Live(source, "Test pattern", listOf(source.format), source.colorSpec)
    }

    fun connect(device: UsbDevice) {
        if (_state.value is SourceState.Live) disconnect()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            _state.value = SourceState.NeedsCameraPermission(device)
            return
        }
        if (!usb.hasPermission(device)) {
            _state.value = SourceState.Connecting(device.displayName)
            usb.requestPermission(device) { granted ->
                if (granted) open(device)
                else idle(Banner("USB access was declined, so Link Cam can't read ${device.displayName}.", isError = true))
            }
            return
        }
        open(device)
    }

    fun onCameraPermissionResult(device: UsbDevice, granted: Boolean) {
        if (granted) {
            connect(device)
        } else {
            idle(
                Banner(
                    "Android only lets apps with camera access use a USB capture device. " +
                        "Link Cam doesn't use your phone's cameras.",
                    isError = true,
                ),
            )
        }
    }

    private fun open(device: UsbDevice) {
        val name = device.displayName
        _state.value = SourceState.Connecting(name)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = usb.open(device) ?: throw UvcException("Android wouldn't open the device")
                    val uvc = try {
                        UvcDevice.open(connection.fileDescriptor)
                    } catch (e: Throwable) {
                        connection.close()
                        throw e
                    }
                    val formats = uvc.formats()
                    val format = FormatChoice.best(formats) ?: run {
                        uvc.close()
                        connection.close()
                        throw UvcException("the device offers no format Link Cam can show yet")
                    }
                    val source = UvcSource(uvc, connection, format)
                    try {
                        source.start(frames::fire)
                    } catch (e: Throwable) {
                        source.close()
                        throw e
                    }
                    SourceState.Live(source, name, formats, source.colorSpec, device.deviceName)
                }
            }
            result
                .onSuccess { _state.value = it }
                .onFailure {
                    Log.w(TAG, "couldn't open $name", it)
                    idle(Banner("Couldn't start $name: ${it.message}", isError = true))
                }
        }
    }

    fun selectFormat(format: SourceFormat) {
        val live = _state.value as? SourceState.Live ?: return
        val current = live.source as? UvcSource ?: return
        if (current.format == format) return
        val next = current.switchTo(format)
        try {
            next.start(frames::fire)
            _state.value = live.copy(source = next, colorSpec = next.colorSpec)
        } catch (e: UvcException) {
            next.close()
            idle(Banner("${live.name} refused ${format.label}: ${e.message}", isError = true))
        }
    }

    fun setColorSpec(spec: ColorSpec) {
        _state.update { if (it is SourceState.Live) it.copy(colorSpec = spec) else it }
    }

    fun disconnect() {
        (_state.value as? SourceState.Live)?.source?.close()
        idle(null)
    }

    private fun onDetached(device: UsbDevice) {
        val live = _state.value as? SourceState.Live
        if (live != null && live.usbDevice == device.deviceName) {
            live.source.close()
            idle(Banner("${device.displayName} was unplugged."))
        } else {
            refreshDevices()
        }
    }

    private fun idle(banner: Banner?) {
        _state.value = SourceState.Idle(usb.videoDevices(), banner)
    }

    private companion object {
        const val TAG = "LinkCamSource"
    }
}

/** Forwards "a frame arrived" from whichever producer thread to whoever is drawing. */
class FrameSignal {
    @Volatile var listener: (() -> Unit)? = null

    fun fire() {
        listener?.invoke()
    }
}
