package cool.linker.cam.ui.arrival

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cool.linker.cam.services.displayName
import cool.linker.cam.source.Banner
import cool.linker.cam.ui.components.BrandSquares
import cool.linker.cam.ui.components.LcButton
import cool.linker.cam.ui.components.StatusBanner
import cool.linker.cam.ui.components.Wordmark
import cool.linker.cam.ui.theme.LcType

/**
 * Where people arrive: nothing is live yet. It lists any capture device that's
 * plugged in, or explains how to plug one in, and offers the test pattern.
 */
@Composable
fun ArrivalScreen(
    devices: List<UsbDevice>,
    banner: Banner?,
    connectingTo: String?,
    onConnect: (UsbDevice) -> Unit,
    onTestPattern: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        BrandSquares()
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Wordmark()
                Spacer(Modifier.height(8.dp))

                when {
                    connectingTo != null -> {
                        BasicText("Connecting to $connectingTo…", style = LcType.Title, modifier = Modifier.semantics { heading() })
                        BasicText("If Android asks, allow Link Cam to use the device.", style = LcType.Body)
                    }
                    devices.isEmpty() -> {
                        BasicText("Connect a capture device", style = LcType.Title, modifier = Modifier.semantics { heading() })
                        BasicText(
                            "Plug an HDMI capture dongle into this device's USB-C port, then feed it from your camera.",
                            style = LcType.Body,
                        )
                    }
                    else -> {
                        BasicText("Ready to monitor", style = LcType.Title, modifier = Modifier.semantics { heading() })
                        for (device in devices) {
                            LcButton(
                                "Connect ${device.displayName}",
                                onClick = { onConnect(device) },
                                primary = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                banner?.let { StatusBanner(it, Modifier.fillMaxWidth()) }

                LcButton("Test pattern", onClick = onTestPattern, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
