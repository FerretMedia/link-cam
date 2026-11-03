import Foundation
import LinkCamShared

/// Everything the monitoring tools are set to, as one value: the iPad's copy
/// of the shared module's `ToolState`.
///
/// It's saved as JSON under the same field names and enum names as the
/// Android app's, so a preset could move between the two. The enums hold only
/// names; their labels and numbers come from the shared module through
/// `Bridge`, so both apps read the same thresholds and colours.
struct ToolSettings: Codable, Equatable {
    var falseColor = false
    var zebras = false
    /// Stripes appear at and above this level, in IRE.
    var zebraLevel = 95
    var peaking = false
    var peakingColor = PeakColor.red
    var peakingSensitivity = PeakSensitivity.medium
    var scope = false
    var scopeKind = ScopeGraph.histogram
    var guides = false
    var guideAspect = FrameLine.scope
    var safeAreas = false
    var punchIn = false
    /// What the source's signal is, which decides how it's shown.
    var signal = Signal.sdr
    var noiseReduction = Denoise.off

    static var zebraLevels: [Int] { Bridge.shared.zebraLevels.map { $0.intValue } }

    init() {}

    // Missing keys keep their defaults, so settings saved by an older build still load.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = ToolSettings()
        falseColor = try c.decodeIfPresent(Bool.self, forKey: .falseColor) ?? d.falseColor
        zebras = try c.decodeIfPresent(Bool.self, forKey: .zebras) ?? d.zebras
        zebraLevel = try c.decodeIfPresent(Int.self, forKey: .zebraLevel) ?? d.zebraLevel
        peaking = try c.decodeIfPresent(Bool.self, forKey: .peaking) ?? d.peaking
        peakingColor = (try? c.decodeIfPresent(PeakColor.self, forKey: .peakingColor)) ?? d.peakingColor
        peakingSensitivity = (try? c.decodeIfPresent(PeakSensitivity.self, forKey: .peakingSensitivity)) ?? d.peakingSensitivity
        scope = try c.decodeIfPresent(Bool.self, forKey: .scope) ?? d.scope
        scopeKind = (try? c.decodeIfPresent(ScopeGraph.self, forKey: .scopeKind)) ?? d.scopeKind
        guides = try c.decodeIfPresent(Bool.self, forKey: .guides) ?? d.guides
        guideAspect = (try? c.decodeIfPresent(FrameLine.self, forKey: .guideAspect)) ?? d.guideAspect
        safeAreas = try c.decodeIfPresent(Bool.self, forKey: .safeAreas) ?? d.safeAreas
        punchIn = try c.decodeIfPresent(Bool.self, forKey: .punchIn) ?? d.punchIn
        signal = (try? c.decodeIfPresent(Signal.self, forKey: .signal)) ?? d.signal
        noiseReduction = (try? c.decodeIfPresent(Denoise.self, forKey: .noiseReduction)) ?? d.noiseReduction
    }
}

/// `PeakingColor`.
enum PeakColor: String, Codable, CaseIterable {
    case red = "RED", green = "GREEN", blue = "BLUE", white = "WHITE"

    private var shared: PeakingColor { Bridge.shared.peakingColor(name: rawValue) }
    var label: String { shared.label }
    /// 0xRRGGBB.
    var rgb: Int { Int(shared.rgb) }
}

/// `PeakingSensitivity`: how strong an edge must be before it's marked.
enum PeakSensitivity: String, Codable, CaseIterable {
    case low = "LOW", medium = "MEDIUM", high = "HIGH"

    private var shared: PeakingSensitivity { Bridge.shared.peakingSensitivity(name: rawValue) }
    var label: String { shared.label }
    /// A Sobel magnitude on normalised luma.
    var threshold: Float { shared.threshold }
}

/// `ScopeKind`.
enum ScopeGraph: String, Codable, CaseIterable {
    case histogram = "HISTOGRAM", waveform = "WAVEFORM"

    private var shared: ScopeKind { Bridge.shared.scopeKind(name: rawValue) }
    var label: String { shared.label }
    /// Its label on the rail.
    var short: String { shared.short_ }
}

/// `GuideAspect`.
enum FrameLine: String, Codable, CaseIterable {
    case scope = "SCOPE", flat = "FLAT", portrait = "PORTRAIT_4_5", vertical = "VERTICAL", square = "SQUARE"

    var label: String { Bridge.shared.guideAspect(name: rawValue).label }
}

/// `Transfer`: the transfer function the camera's signal was made with.
enum Signal: String, Codable, CaseIterable {
    case sdr = "SDR", pq = "PQ", hlg = "HLG"

    private var shared: Transfer { Bridge.shared.transfer(name: rawValue) }
    var label: String { shared.label }
    var short: String { shared.short_ }
    /// The shader's `transfer` uniform: 0 SDR, 1 PQ, 2 HLG.
    var ordinal: Int32 { shared.ordinal }
}

/// `NoiseReduction`.
enum Denoise: String, Codable, CaseIterable {
    case off = "OFF", low = "LOW", high = "HIGH"

    private var shared: NoiseReduction { Bridge.shared.noiseReduction(name: rawValue) }
    var label: String { shared.label }
    /// How much of the previous cleaned frame a still pixel keeps.
    var keep: Float { shared.keep }
    /// The grain's expected size in signal levels, 0...1.
    var noise: Float { shared.noise }
}
