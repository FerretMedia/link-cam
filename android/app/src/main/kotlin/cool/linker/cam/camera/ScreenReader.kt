package cool.linker.cam.camera

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import cool.linker.cam.render.MonitorRenderer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads the camera's Wi-Fi name and password off its screen, as it comes in
 * over HDMI, so pairing needs nothing typed. Recognition runs on the phone;
 * the picture goes nowhere.
 */
class ScreenReader(private val snapshot: suspend () -> MonitorRenderer.Luma?) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** What the camera's screen shows now, or null if it isn't showing its Wi-Fi details. */
    suspend fun read(): WifiScreen.Credentials? {
        val luma = snapshot() ?: return null
        // NV21 with grey chroma: recognition only needs the luma, and this format takes it as it is.
        val nv21 = ByteArray(luma.width * luma.height * 3 / 2)
        luma.codes.copyInto(nv21)
        nv21.fill(GREY, luma.codes.size)
        val image = InputImage.fromByteArray(nv21, luma.width, luma.height, 0, InputImage.IMAGE_FORMAT_NV21)
        val text = suspendCancellableCoroutine { done ->
            recognizer.process(image)
                .addOnSuccessListener { done.resume(it) }
                .addOnFailureListener { done.resumeWithException(it) }
        }
        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            line.boundingBox?.let { WifiScreen.Line(line.text, it.top.toFloat(), it.bottom.toFloat(), it.left.toFloat()) }
        }
        return WifiScreen.read(WifiScreen.rows(lines))
    }

    fun close() = recognizer.close()

    private companion object {
        const val GREY = 128.toByte()
    }
}
