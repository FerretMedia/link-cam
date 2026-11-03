import AVFoundation
import CoreVideo

/// A USB video capture device, through AVFoundation's external camera
/// support (iPadOS 17 and later). iPadOS drives the device itself, so unlike
/// on Android there's no USB driver here: AVFoundation hands over decoded
/// frames, and Link Cam asks for them in the layout closest to what the
/// dongle sends, so nothing is converted that doesn't have to be.
final class CaptureSource: NSObject, FrameSource, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    let device: AVCaptureDevice
    let formats: [SourceFormatInfo]
    private(set) var format: SourceFormatInfo
    private(set) var defaultColorSpec: ColorSpecChoice

    private let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    /// Session setup and start, which block, happen here rather than on the main thread.
    private let sessionQueue = DispatchQueue(label: "cool.linker.cam.session")
    private let frameQueue = DispatchQueue(label: "cool.linker.cam.capture", qos: .userInteractive)
    private let counters = FrameCounters()
    private var onFrame: ((CVPixelBuffer) -> Void)?
    private var onProblem: ((String?) -> Void)?
    private var observers: [NSObjectProtocol] = []
    private var configured = false

    var name: String { device.localizedName }

    init(device: AVCaptureDevice) {
        self.device = device
        let formats = Self.listFormats(device)
        self.formats = formats
        let chosen = SourceFormatInfo.best(formats)
            ?? SourceFormatInfo(width: 1920, height: 1080, fps: 30, codec: "NV12")
        format = chosen
        defaultColorSpec = Self.colorSpec(for: chosen)
        super.init()
    }

    func start(onFrame: @escaping (CVPixelBuffer) -> Void, onProblem: @escaping (String?) -> Void) {
        self.onFrame = onFrame
        self.onProblem = onProblem
        watchSession()
        sessionQueue.async { [self] in
            configure()
            session.startRunning()
        }
    }

    func stop() {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers = []
        sessionQueue.async { [self] in
            session.stopRunning()
            output.setSampleBufferDelegate(nil, queue: nil)
        }
        onFrame = nil
    }

    func select(_ format: SourceFormatInfo) {
        guard format != self.format else { return }
        self.format = format
        defaultColorSpec = Self.colorSpec(for: format)
        sessionQueue.async { [self] in
            session.beginConfiguration()
            applyFormat()
            session.commitConfiguration()
        }
    }

    func stats() -> SourceStats { counters.snapshot }

    // MARK: - Session

    private func configure() {
        guard !configured else { return }
        configured = true
        session.beginConfiguration()
        // Keep the device's own format rather than a preset's idea of one.
        session.sessionPreset = .inputPriority
        do {
            let input = try AVCaptureDeviceInput(device: device)
            if session.canAddInput(input) { session.addInput(input) }
        } catch {
            report("Couldn't open \(name): \(error.localizedDescription)")
        }
        // A monitor shows the newest frame; one that's late is worth nothing.
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: frameQueue)
        if session.canAddOutput(output) { session.addOutput(output) }
        applyFormat()
        session.commitConfiguration()
    }

    /// Selects [format] on the device and asks for frames in the layout that
    /// suits it. Called inside a configuration block.
    private func applyFormat() {
        let pixelFormat = Self.outputPixelFormat(for: format, available: output.availableVideoPixelFormatTypes)
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: pixelFormat,
            kCVPixelBufferMetalCompatibilityKey as String: true,
        ]
        guard let deviceFormat = format.device else { return }
        do {
            try device.lockForConfiguration()
            device.activeFormat = deviceFormat
            if format.frameDuration.isValid {
                device.activeVideoMinFrameDuration = format.frameDuration
                device.activeVideoMaxFrameDuration = format.frameDuration
            }
            device.unlockForConfiguration()
        } catch {
            report("Couldn't set \(format.label): \(error.localizedDescription)")
        }
    }

    private func watchSession() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: nil) { [weak self] note in
            guard let self else { return }
            let error = note.userInfo?[AVCaptureSessionErrorKey] as? AVError
            report("The capture stopped: \(error?.localizedDescription ?? "unknown error"). Restarting…")
            sessionQueue.async { self.session.startRunning() }
        })
        observers.append(center.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: session, queue: nil) { [weak self] note in
            let reason = (note.userInfo?[AVCaptureSessionInterruptionReasonKey] as? Int)
                .flatMap(AVCaptureSession.InterruptionReason.init(rawValue:))
            self?.report(Self.describe(reason))
        })
        observers.append(center.addObserver(forName: AVCaptureSession.interruptionEndedNotification, object: session, queue: nil) { [weak self] _ in
            self?.report(nil)
        })
    }

    private func report(_ problem: String?) {
        let callback = onProblem
        DispatchQueue.main.async { callback?(problem) }
    }

    private static func describe(_ reason: AVCaptureSession.InterruptionReason?) -> String {
        switch reason {
        case .videoDeviceNotAvailableWithMultipleForegroundApps:
            return "The picture pauses while Link Cam shares the screen with another app."
        case .videoDeviceInUseByAnotherClient:
            return "Another app is using the capture device."
        case .videoDeviceNotAvailableDueToSystemPressure:
            return "The iPad is too hot to capture right now."
        default:
            return "The picture is paused."
        }
    }

    // MARK: - Frames

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        counters.received()
        onFrame?(buffer)
    }

    func captureOutput(_ output: AVCaptureOutput, didDrop sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        counters.dropped()
    }

    // MARK: - Formats

    /// Every size, codec and frame rate the device offers. A UVC device
    /// usually lists its rates as separate fixed ranges, so each becomes its
    /// own choice.
    static func listFormats(_ device: AVCaptureDevice) -> [SourceFormatInfo] {
        var seen = Set<String>()
        var out: [SourceFormatInfo] = []
        for f in device.formats {
            let size = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
            let codec = codecName(CMFormatDescriptionGetMediaSubType(f.formatDescription))
            for range in f.videoSupportedFrameRateRanges {
                let info = SourceFormatInfo(
                    width: Int(size.width),
                    height: Int(size.height),
                    fps: range.maxFrameRate,
                    codec: codec,
                    device: f,
                    frameDuration: range.minFrameDuration
                )
                if seen.insert(info.id).inserted { out.append(info) }
            }
        }
        return out.sorted { ($0.width * $0.height, $0.fps) > ($1.width * $1.height, $1.fps) }
    }

    /// The shared module's name for what the device sends.
    static func codecName(_ subtype: FourCharCode) -> String {
        switch subtype {
        case kCMVideoCodecType_JPEG, kCMVideoCodecType_JPEG_OpenDML: return "MJPEG"
        case kCVPixelFormatType_422YpCbCr8_yuvs, kCVPixelFormatType_422YpCbCr8: return "YUYV"
        default: return "NV12"
        }
    }

    /// Full range for JPEG, which is JFIF and uses every code; video range
    /// for anything uncompressed unless the device itself says full. That
    /// way iPadOS hands the codes over as the dongle sent them, which is what
    /// the exposure tools need to read.
    static func outputPixelFormat(for format: SourceFormatInfo, available: [OSType]) -> OSType {
        let full = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        let video = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        let native = format.device.map { CMFormatDescriptionGetMediaSubType($0.formatDescription) }
        let wantFull = native == full || format.codec == "MJPEG"
        let wanted = wantFull ? full : video
        if available.isEmpty || available.contains(wanted) { return wanted }
        return available.contains(video) ? video : full
    }

    private static func colorSpec(for format: SourceFormatInfo) -> ColorSpecChoice {
        let full = outputPixelFormat(for: format, available: []) == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        return ColorSpecChoice(matrix: format.defaultColorSpec.matrix, fullRange: full)
    }
}
