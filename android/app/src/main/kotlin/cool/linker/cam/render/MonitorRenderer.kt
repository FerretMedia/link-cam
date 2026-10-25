package cool.linker.cam.render

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import cool.linker.cam.model.ColorSpec
import cool.linker.cam.model.Transfer
import cool.linker.cam.source.FrameInfo
import cool.linker.cam.source.FrameSource
import cool.linker.cam.tools.FalseColor
import cool.linker.cam.tools.Guides
import cool.linker.cam.tools.ScopeKind
import cool.linker.cam.tools.Scopes
import cool.linker.cam.tools.ToolState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the current source with the monitoring tools applied.
 *
 * It renders only when a frame arrives (the source wakes it), uploads that
 * frame once, and draws it through `monitor.frag`. The picture is fitted to
 * the view with a black surround; the Compose overlays use the same fit from
 * [Guides.fit], so the frame lines land on the picture.
 *
 * When the scope is on, it also samples the frame's luma a few times a second
 * for [scopes]. The read goes through a pixel buffer and is collected one tick
 * later, by which time the GPU has long finished it, so the draw never waits.
 */
class MonitorRenderer(private val assets: AssetManager) : GLSurfaceView.Renderer {
    @Volatile var source: FrameSource? = null
    @Volatile var tools: ToolState = ToolState()
    @Volatile var colorSpec: ColorSpec = ColorSpec.HD_VIDEO
    val stats = RenderStats()

    private val _scopes = MutableStateFlow<ScopeReading?>(null)
    val scopes: StateFlow<ScopeReading?> = _scopes.asStateFlow()

    private var program = 0
    private val planes = IntArray(3)
    private val planeSize = Array(3) { IntArray(2) }
    private var ramp = 0
    private var viewWidth = 0
    private var viewHeight = 0
    private var hasFrame = false
    private val frame = FrameInfo()
    private val startNanos = System.nanoTime()

    private val u = Uniforms()

    private var scopeProgram = 0
    private var scopeY = 0
    private var scopeGrid = 0
    private var scopeFbo = 0
    private val scopePbos = IntArray(2)
    private var scopePending = -1
    private var scopeNext = 0
    private var scopeLastNanos = 0L
    private val scopeCodes = ByteArray(Scopes.GRID_WIDTH * Scopes.GRID_HEIGHT)

    @Volatile private var snapshotRequest: CompletableDeferred<Luma?>? = null
    private var snapshotFbo = 0
    private var snapshotTexture = 0
    private val snapshotSize = IntArray(2)

    // One-second accumulators for [stats].
    private var windowStart = 0L
    private var windowFrames = 0
    private var windowLatencyNanos = 0L
    private var windowDecodeMicros = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(readAsset("shaders/monitor.vert"), readAsset("shaders/monitor.frag"))
        u.locate(program)

        GLES30.glGenTextures(3, planes, 0)
        for (t in planes) {
            configureTexture(t, GLES30.GL_LINEAR)
        }
        planeSize.forEach { it[0] = 0; it[1] = 0 }
        hasFrame = false

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        ramp = ids[0]
        configureTexture(ramp, GLES30.GL_NEAREST)
        val rampData = ByteBuffer.allocateDirect(FalseColor.RAMP_SIZE * 4).put(FalseColor.ramp())
        rampData.position(0)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, FalseColor.RAMP_SIZE, 1, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rampData,
        )

        // The triangle is generated in the vertex shader, but GLES 3 still
        // wants a vertex array bound to draw.
        val vao = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])

        createScope()
        snapshotFbo = 0
        snapshotSize.fill(0)
    }

    private fun createScope() {
        scopeProgram = buildProgram(readAsset("shaders/monitor.vert"), readAsset("shaders/scope.frag"))
        scopeY = GLES30.glGetUniformLocation(scopeProgram, "uY")
        scopeGrid = GLES30.glGetUniformLocation(scopeProgram, "uGrid")

        val texture = IntArray(1)
        GLES30.glGenTextures(1, texture, 0)
        configureTexture(texture[0], GLES30.GL_NEAREST)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, Scopes.GRID_WIDTH / 4, Scopes.GRID_HEIGHT, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        scopeFbo = fbo[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, scopeFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture[0], 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) Log.e(TAG, "scope framebuffer incomplete: 0x%x".format(status))
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        GLES30.glGenBuffers(2, scopePbos, 0)
        for (pbo in scopePbos) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo)
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, scopeCodes.size, null, GLES30.GL_STREAM_READ)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        scopePending = -1
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val src = source
        var fresh = false
        if (src != null && src.acquire(frame)) {
            for (c in 0..2) ensureSize(c, frame.planeWidth[c], frame.planeHeight[c])
            src.upload(planes[0], planes[1], planes[2])
            hasFrame = true
            fresh = true
            account(frame)
        }

        GLES30.glViewport(0, 0, viewWidth, viewHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (src == null || !hasFrame) return

        val aspect = src.format.aspect
        val fit = Guides.fit(viewWidth.toFloat(), viewHeight.toFloat(), aspect)
        // GL's viewport origin is bottom-left; Guides' is top-left.
        GLES30.glViewport(
            fit.left.toInt(), (viewHeight - fit.bottom).toInt(), fit.width.toInt(), fit.height.toInt(),
        )

        val t = tools
        val c = effectiveSpec(t).shaderConstants()
        GLES30.glUseProgram(program)
        bind(0, planes[0], u.y)
        bind(1, planes[1], u.cb)
        bind(2, planes[2], u.cr)
        bind(3, ramp, u.ramp)
        GLES30.glUniformMatrix3fv(u.yuvToRgb, 1, false, c.matrix, 0)
        GLES30.glUniform3fv(u.offset, 1, c.offset, 0)
        GLES30.glUniform3fv(u.scale, 1, c.scale, 0)

        // Punch-in: show the middle half of the picture at 2×.
        if (t.punchIn) GLES30.glUniform4f(u.crop, 0.25f, 0.25f, 0.5f, 0.5f)
        else GLES30.glUniform4f(u.crop, 0f, 0f, 1f, 1f)
        GLES30.glUniform2f(u.lumaTexel, 1f / planeSize[0][0], 1f / planeSize[0][1])
        GLES30.glUniform2f(u.chromaTexel, 1f / planeSize[1][0], 1f / planeSize[1][1])
        GLES30.glUniform1i(u.transfer, t.signal.ordinal)
        GLES30.glUniform1f(u.denoise, t.noiseReduction.rangeWeight)

        GLES30.glUniform1i(u.falseColor, if (t.falseColor) 1 else 0)
        GLES30.glUniform1f(u.zebraLevel, if (t.zebras) t.zebraLevel / 100f else 2f)
        GLES30.glUniform1f(u.peakThreshold, if (t.peaking) t.peakingSensitivity.threshold else -1f)
        val pc = t.peakingColor.rgb
        GLES30.glUniform3f(u.peakColor, (pc shr 16 and 0xFF) / 255f, (pc shr 8 and 0xFF) / 255f, (pc and 0xFF) / 255f)
        GLES30.glUniform1f(u.time, ((System.nanoTime() - startNanos) % 60_000_000_000L) / 1e9f)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        if (!t.scope) {
            scopePending = -1
            _scopes.value = null
        } else if (fresh && System.nanoTime() - scopeLastNanos >= SCOPE_PERIOD_NANOS) {
            scopeLastNanos = System.nanoTime()
            scopePass(t.scopeKind)
        }
        snapshotRequest?.let { request ->
            snapshotRequest = null
            request.complete(snapshotPass())
        }
    }

    /**
     * The newest frame's luma, top row first, for reading what's on the
     * camera's screen. Waits for the next draw; null without a picture.
     */
    suspend fun snapshot(): Luma? {
        val request = CompletableDeferred<Luma?>()
        snapshotRequest = request
        return withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { request.await() }
    }

    /** A rare, synchronous full read: only asked for every second or so, while pairing. */
    private fun snapshotPass(): Luma? {
        if (!hasFrame) return null
        // Four codes to an RGBA pixel, so the width is kept to a multiple of four.
        val width = planeSize[0][0] and 3.inv()
        val height = planeSize[0][1]
        if (width <= 0 || height <= 0) return null
        if (snapshotFbo == 0 || snapshotSize[0] != width || snapshotSize[1] != height) {
            if (snapshotFbo != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(snapshotFbo), 0)
                GLES30.glDeleteTextures(1, intArrayOf(snapshotTexture), 0)
            }
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            snapshotTexture = ids[0]
            configureTexture(snapshotTexture, GLES30.GL_NEAREST)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width / 4, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
            GLES30.glGenFramebuffers(1, ids, 0)
            snapshotFbo = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, snapshotFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, snapshotTexture, 0)
            snapshotSize[0] = width
            snapshotSize[1] = height
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, snapshotFbo)
        GLES30.glViewport(0, 0, width / 4, height)
        GLES30.glUseProgram(scopeProgram)
        bind(0, planes[0], scopeY)
        GLES30.glUniform2i(scopeGrid, width, height)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        val pixels = ByteBuffer.allocateDirect(width * height)
        GLES30.glReadPixels(0, 0, width / 4, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return Luma(width, height, ByteArray(width * height).also { pixels.get(it) })
    }

    /** Starts this tick's luma read, then bins the one started last tick. */
    private fun scopePass(kind: ScopeKind) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, scopeFbo)
        GLES30.glViewport(0, 0, Scopes.GRID_WIDTH / 4, Scopes.GRID_HEIGHT)
        GLES30.glUseProgram(scopeProgram)
        bind(0, planes[0], scopeY)
        GLES30.glUniform2i(scopeGrid, Scopes.GRID_WIDTH, Scopes.GRID_HEIGHT)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, scopePbos[scopeNext])
        GLES30.glReadPixels(0, 0, Scopes.GRID_WIDTH / 4, Scopes.GRID_HEIGHT, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        val previous = scopePending
        scopePending = scopeNext
        scopeNext = 1 - scopeNext
        if (previous >= 0) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, scopePbos[previous])
            val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, scopeCodes.size, GLES30.GL_MAP_READ_BIT) as ByteBuffer?
            if (mapped != null) {
                mapped.get(scopeCodes)
                GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                val c = effectiveSpec(tools).shaderConstants()
                val ire = scopeIre(c.offset[0], c.scale[0], tools.signal)
                _scopes.value = when (kind) {
                    ScopeKind.HISTOGRAM -> ScopeReading(Scopes.histogram(scopeCodes, ire), null)
                    ScopeKind.WAVEFORM -> ScopeReading(null, Scopes.waveform(scopeCodes, Scopes.GRID_WIDTH, Scopes.GRID_HEIGHT, ire))
                }
            }
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
    }

    private fun account(f: FrameInfo) {
        val now = System.nanoTime()
        if (windowStart == 0L) windowStart = now
        windowFrames++
        windowLatencyNanos += now - f.captureNanos
        windowDecodeMicros += f.decodeMicros
        val elapsed = now - windowStart
        if (elapsed >= 1_000_000_000L) {
            stats.presentedFps = windowFrames * 1e9f / elapsed
            stats.latencyMs = windowLatencyNanos / 1e6f / windowFrames
            stats.decodeMs = windowDecodeMicros / 1e3f / windowFrames
            windowStart = now
            windowFrames = 0
            windowLatencyNanos = 0
            windowDecodeMicros = 0
        }
    }

    private fun ensureSize(plane: Int, width: Int, height: Int) {
        val size = planeSize[plane]
        if (size[0] == width && size[1] == height) return
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, planes[plane])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, width, height, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null,
        )
        size[0] = width
        size[1] = height
    }

    private fun configureTexture(id: Int, filter: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun bind(unit: Int, texture: Int, location: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(location, unit)
    }

    private fun readAsset(path: String) = assets.open(path).bufferedReader().use { it.readText() }

    /** HDR is BT.2100, whose Y'CbCr matrix is always BT.2020's, whatever the dongle labels it. */
    private fun effectiveSpec(t: ToolState): ColorSpec =
        if (t.signal == Transfer.SDR) colorSpec else colorSpec.copy(matrix = ColorSpec.Matrix.BT2020)

    private var ireKey: Triple<Float, Float, Transfer>? = null
    private var ireCache = FloatArray(0)

    /** The scope's code-to-IRE table, built again only when the signal or its range changes. */
    private fun scopeIre(offset: Float, scale: Float, transfer: Transfer): FloatArray {
        val key = Triple(offset, scale, transfer)
        if (key != ireKey) {
            ireCache = Scopes.ireTable(offset, scale, transfer)
            ireKey = key
        }
        return ireCache
    }

    /** One 8-bit luma code per pixel, row by row from the top. */
    class Luma(val width: Int, val height: Int, val codes: ByteArray)

    private class Uniforms {
        var y = 0; var cb = 0; var cr = 0; var ramp = 0
        var yuvToRgb = 0; var offset = 0; var scale = 0
        var crop = 0; var lumaTexel = 0; var chromaTexel = 0; var transfer = 0; var denoise = 0
        var falseColor = 0; var zebraLevel = 0; var peakThreshold = 0; var peakColor = 0; var time = 0

        fun locate(p: Int) {
            fun at(name: String) = GLES30.glGetUniformLocation(p, name)
            y = at("uY"); cb = at("uCb"); cr = at("uCr"); ramp = at("uRamp")
            yuvToRgb = at("uYuvToRgb"); offset = at("uOffset"); scale = at("uScale")
            crop = at("uCrop"); lumaTexel = at("uLumaTexel"); chromaTexel = at("uChromaTexel")
            transfer = at("uTransfer"); denoise = at("uDenoise")
            falseColor = at("uFalseColor"); zebraLevel = at("uZebraLevel")
            peakThreshold = at("uPeakThreshold"); peakColor = at("uPeakColor"); time = at("uTime")
        }
    }

    private companion object {
        const val TAG = "LinkCamRender"

        /** The scopes update about 15 times a second: smooth to read, and a quarter of the frames at 60p. */
        const val SCOPE_PERIOD_NANOS = 66_000_000L

        /** A live source draws 25 to 60 times a second; longer than this means no picture. */
        const val SNAPSHOT_TIMEOUT_MS = 500L

        fun buildProgram(vertex: String, fragment: String): Int {
            val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, vs)
            GLES30.glAttachShader(p, fs)
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "link failed: ${GLES30.glGetProgramInfoLog(p)}" }
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return p
        }

        fun compile(type: Int, source: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, source)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(s)
                Log.e(TAG, "shader compile failed: $log")
                error("shader compile failed: $log")
            }
            return s
        }
    }
}
