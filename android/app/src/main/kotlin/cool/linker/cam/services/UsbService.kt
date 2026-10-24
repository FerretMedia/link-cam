package cool.linker.cam.services

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Android's USB host API, reduced to what Link Cam needs: which video devices
 * are attached, asking for permission, and opening one. Knows nothing about
 * UVC; it hands back a connection whose file descriptor the driver takes.
 */
class UsbService(private val context: Context) {
    private val usb = context.getSystemService(UsbManager::class.java)

    /** Attached devices with a USB Video Class interface. */
    fun videoDevices(): List<UsbDevice> =
        usb?.deviceList?.values?.filter { it.isVideoDevice() }.orEmpty()

    fun hasPermission(device: UsbDevice): Boolean = usb?.hasPermission(device) == true

    /** Shows Android's "allow access" dialog; [onResult] runs on the main thread. */
    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        val manager = usb ?: return onResult(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                context.unregisterReceiver(this)
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Android fills in the result extras, so the intent must be mutable, and
        // a mutable PendingIntent must be explicit about who receives it.
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
        manager.requestPermission(device, pending)
    }

    /**
     * Opens [device] and claims its video interfaces with force, which detaches
     * any kernel driver Android bound to them. Without that, libusb can't
     * claim them from an unprivileged app.
     */
    fun open(device: UsbDevice): UsbDeviceConnection? {
        val connection = usb?.openDevice(device) ?: return null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO) connection.claimInterface(intf, true)
        }
        return connection
    }

    /** Calls back when a video device is plugged in or pulled out. Returns a function that stops listening. */
    fun watch(onAttached: (UsbDevice) -> Unit, onDetached: (UsbDevice) -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    ?: return
                if (!device.isVideoDevice()) return
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> onAttached(device)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached(device)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // These are system broadcasts, so the receiver must be exported to get them.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        return { context.unregisterReceiver(receiver) }
    }

    private companion object {
        const val ACTION_PERMISSION = "cool.linker.cam.USB_PERMISSION"
    }
}

fun UsbDevice.isVideoDevice(): Boolean =
    deviceClass == UsbConstants.USB_CLASS_VIDEO ||
        (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }

/** The name to show for a device: its product string, else its maker, else its IDs. */
val UsbDevice.displayName: String
    get() = productName?.takeIf { it.isNotBlank() }
        ?: manufacturerName?.takeIf { it.isNotBlank() }
        ?: "USB video %04x:%04x".format(vendorId, productId)
