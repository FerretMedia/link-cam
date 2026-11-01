package cool.linker.cam.ui.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cool.linker.cam.ui.theme.LcColors
import cool.linker.cam.ui.theme.LcSize
import cool.linker.cam.ui.theme.LcType

/** The phone's battery, as Android last reported it. */
data class PhoneBattery(val percent: Int, val charging: Boolean)

/** Follows the phone's battery while on screen. Android sends the level whenever it changes. */
@Composable
fun rememberPhoneBattery(): PhoneBattery? {
    val context = LocalContext.current
    var battery by remember { mutableStateOf<PhoneBattery?>(null) }
    DisposableEffect(context) {
        fun read(intent: Intent): PhoneBattery? {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            return PhoneBattery(level * 100 / scale, charging)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                read(intent)?.let { battery = it }
            }
        }
        // The battery broadcast is sticky: registering hands back the current level at once.
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let { sticky -> read(sticky)?.let { battery = it } }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return battery
}

/** The phone's battery and, when one is linked, the camera's: the two things that end a shoot early. */
@Composable
fun PowerReadout(phone: PhoneBattery?, camera: Int?, modifier: Modifier = Modifier) {
    if (phone == null && camera == null) return
    Row(
        modifier
            .clip(RoundedCornerShape(LcSize.Radius))
            .background(LcColors.InkRaised.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        phone?.let { Level("PHONE", it.percent, it.charging, "Phone battery") }
        camera?.let { Level("CAM", it, charging = false, "Camera battery") }
    }
}

@Composable
private fun Level(label: String, percent: Int, charging: Boolean, description: String) {
    val low = percent <= LOW && !charging
    Row(
        Modifier.semantics { contentDescription = "$description $percent percent" + if (charging) ", charging" else "" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = LcType.Legend.copy(color = LcColors.Muted))
        BatteryGlyph(percent, if (low) Low else if (charging) LcColors.BrandBright else LcColors.Paper)
        BasicText("$percent%", style = LcType.CameraValue.copy(color = if (low) Low else LcColors.Paper))
    }
}

/** A battery outline filled to [percent]. */
@Composable
private fun BatteryGlyph(percent: Int, color: Color) {
    Canvas(Modifier.size(width = 20.dp, height = 10.dp)) {
        val stroke = 1.2.dp.toPx()
        val nub = 2.dp.toPx()
        val body = Size(size.width - nub, size.height)
        drawRoundRect(color, Offset.Zero, body, CornerRadius(2.dp.toPx()), style = Stroke(stroke))
        drawRect(color, Offset(body.width, size.height * 0.3f), Size(nub, size.height * 0.4f))
        val inset = stroke * 1.8f
        val full = body.width - inset * 2
        drawRect(color, Offset(inset, inset), Size(full * percent.coerceIn(0, 100) / 100f, body.height - inset * 2))
    }
}

private const val LOW = 15
private val Low = Color(0xFFF87171)
