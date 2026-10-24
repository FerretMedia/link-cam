package cool.linker.cam.ui.monitor

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cool.linker.cam.model.ColorSpec
import cool.linker.cam.render.MonitorRenderer
import cool.linker.cam.source.FrameSignal
import cool.linker.cam.source.FrameSource
import cool.linker.cam.tools.ToolState

/**
 * The picture. A GLSurfaceView, which is a SurfaceView: it composites
 * straight to the display, a frame sooner than a TextureView would.
 */
@Composable
fun MonitorSurface(
    source: FrameSource,
    colorSpec: ColorSpec,
    tools: ToolState,
    frames: FrameSignal,
    renderer: MonitorRenderer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            // Draw when a frame arrives, not on a timer.
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
    }

    SideEffect {
        renderer.source = source
        renderer.colorSpec = colorSpec
        renderer.tools = tools
        view.requestRender()
    }

    DisposableEffect(view) {
        frames.listener = view::requestRender
        onDispose { frames.listener = null }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AndroidView(factory = { view }, modifier = modifier)
}
