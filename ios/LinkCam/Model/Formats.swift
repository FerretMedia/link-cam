import AVFoundation
import LinkCamShared
import simd

/// How to turn a source's Y'CbCr into R'G'B': the shared module's `ColorSpec`.
///
/// Every source declares one, because the same bytes mean different colours
/// depending on it, and the user can override both halves when a source gets
/// it wrong.
struct ColorSpecChoice: Equatable {
    enum Matrix: String, CaseIterable {
        case bt601 = "BT601", bt709 = "BT709", bt2020 = "BT2020"

        var label: String { Bridge.shared.matrixLabel(matrix: rawValue) }
    }

    var matrix: Matrix
    var fullRange: Bool

    static let jfif = ColorSpecChoice(matrix: .bt601, fullRange: true)
    static let hdVideo = ColorSpecChoice(matrix: .bt709, fullRange: false)

    /// "601/full".
    var label: String { Bridge.shared.colorSpecLabel(matrix: matrix.rawValue, fullRange: fullRange) }

    /// HDR is BT.2100, whose Y'CbCr matrix is always BT.2020's, whatever the source labels it.
    func effective(for signal: Signal) -> ColorSpecChoice {
        signal == .sdr ? self : ColorSpecChoice(matrix: .bt2020, fullRange: fullRange)
    }

    /// The shader's half of the conversion: `rgb = matrix * ((yuv - offset) * scale)`.
    struct Constants {
        var matrix: simd_float3x3
        var offset: SIMD3<Float>
        var scale: SIMD3<Float>
    }

    var constants: Constants {
        let data = Bridge.shared.shaderConstants(matrix: matrix.rawValue, fullRange: fullRange)
        let f: [Float] = data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
        return Constants(
            matrix: simd_float3x3(columns: (
                SIMD3(f[0], f[1], f[2]),
                SIMD3(f[3], f[4], f[5]),
                SIMD3(f[6], f[7], f[8])
            )),
            offset: SIMD3(f[9], f[10], f[11]),
            scale: SIMD3(f[12], f[13], f[14])
        )
    }
}

/// One way a source can deliver its picture: size, rate and codec, plus, for
/// a capture device, the AVFoundation format to select for it.
struct SourceFormatInfo: Identifiable, Equatable {
    let id: String
    let width: Int
    let height: Int
    let fps: Double
    /// A `SourceFormat.Codec` name: MJPEG, YUYV, NV12 or I420.
    let codec: String
    let device: AVCaptureDevice.Format?
    /// The frame duration that gives [fps] on [device].
    let frameDuration: CMTime

    init(width: Int, height: Int, fps: Double, codec: String, device: AVCaptureDevice.Format? = nil, frameDuration: CMTime = .invalid) {
        self.width = width
        self.height = height
        self.fps = fps
        self.codec = codec
        self.device = device
        self.frameDuration = frameDuration
        id = "\(width)x\(height)@\(fps)/\(codec)"
    }

    var aspect: Float { height > 0 ? Float(width) / Float(height) : 16 / 9 }

    var shared: SourceFormat {
        Bridge.shared.sourceFormat(codec: codec, width: Int32(width), height: Int32(height), fps: fps)
    }

    /// "1920×1080 · 59.94p · MJPEG".
    var label: String { shared.label }

    /// The interpretation to assume until the user says otherwise: JPEG is
    /// JFIF, anything uncompressed from a dongle is HD video.
    var defaultColorSpec: ColorSpecChoice {
        Bridge.shared.defaultsToFullRange(codec: codec) ? .jfif : .hdVideo
    }

    static func == (a: SourceFormatInfo, b: SourceFormatInfo) -> Bool { a.id == b.id }

    /// Which of [formats] to start with, by the shared module's `FormatChoice`.
    static func best(_ formats: [SourceFormatInfo]) -> SourceFormatInfo? {
        let i = Int(Bridge.shared.bestFormat(formats: formats.map(\.shared)))
        return formats.indices.contains(i) ? formats[i] : formats.first
    }
}
