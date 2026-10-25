package cool.linker.cam.render

import cool.linker.cam.tools.Histogram
import cool.linker.cam.tools.Waveform

/** The latest exposure scope, for the scope panel. Only the selected kind is built. */
class ScopeReading(val histogram: Histogram?, val waveform: Waveform?)
