package cool.linker.cam.render

/**
 * What the renderer measured over the last second, for the status strip and
 * the Phase 0 numbers. Written by the GL thread, read by the UI.
 */
class RenderStats {
    @Volatile var presentedFps = 0f
        internal set

    /** Average time from a frame arriving in the app to its draw being submitted. */
    @Volatile var latencyMs = 0f
        internal set

    @Volatile var decodeMs = 0f
        internal set
}
