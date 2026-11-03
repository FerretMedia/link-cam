import AVFoundation
import Observation
import UIKit

/// The app's state, owned in one place: which capture devices are plugged in,
/// what's live, and how the tools are set. Views render it and send it
/// events; it talks to AVFoundation and the renderer.
@MainActor
@Observable
final class AppModel {
    /// What's on screen now.
    struct Live {
        let source: FrameSource
        var format: SourceFormatInfo
        /// The user's choice, when the source's own interpretation looks wrong.
        var colorOverride: ColorSpecChoice?
        /// Why the picture isn't moving, while it isn't.
        var problem: String?

        var name: String { source.name }
        var colorSpec: ColorSpecChoice { colorOverride ?? source.defaultColorSpec }
    }

    struct Banner: Equatable {
        let text: String
        /// Offer a button to the app's page in Settings.
        var opensSettings = false
    }

    var tools: ToolSettings {
        didSet {
            guard tools != oldValue else { return }
            store.saveTools(tools)
            pushSettings()
        }
    }

    private(set) var live: Live?
    private(set) var devices: [AVCaptureDevice] = []
    /// The device waiting for camera permission, by name.
    private(set) var connecting: String?
    var banner: Banner?
    private(set) var scopes: ScopeReading?

    @ObservationIgnored let renderer = MonitorRenderer()
    @ObservationIgnored private let store = SettingsStore()
    @ObservationIgnored private let discovery = AVCaptureDevice.DiscoverySession(
        deviceTypes: [.external], mediaType: .video, position: .unspecified
    )
    @ObservationIgnored private var devicesObservation: NSKeyValueObservation?
    @ObservationIgnored private var unplugObserver: NSObjectProtocol?

    init() {
        tools = store.loadTools()
        renderer.onScopes = { [weak self] reading in
            MainActor.assumeIsolated { self?.scopes = reading }
        }
        devicesObservation = discovery.observe(\.devices, options: [.initial, .new]) { [weak self] _, _ in
            Task { @MainActor in self?.refreshDevices() }
        }
        unplugObserver = NotificationCenter.default.addObserver(
            forName: AVCaptureDevice.wasDisconnectedNotification, object: nil, queue: .main
        ) { [weak self] note in
            guard let device = note.object as? AVCaptureDevice else { return }
            MainActor.assumeIsolated { self?.unplugged(device) }
        }
        // iPadOS asks once. Asking up front, on the screen that says what
        // Link Cam is for, means plugging in later just works.
        if AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined {
            AVCaptureDevice.requestAccess(for: .video) { _ in
                Task { @MainActor [weak self] in self?.refreshDevices() }
            }
        }
        pushSettings()
    }

    // MARK: - Sources

    func open(_ device: AVCaptureDevice) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            start(CaptureSource(device: device))
        case .notDetermined:
            connecting = device.localizedName
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    connecting = nil
                    if granted { start(CaptureSource(device: device)) } else { banner = Self.cameraOff }
                }
            }
        default:
            banner = Self.cameraOff
        }
    }

    func openTestPattern() {
        start(TestPatternSource())
    }

    func close() {
        live?.source.stop()
        live = nil
        scopes = nil
        renderer.reset()
        UIApplication.shared.isIdleTimerDisabled = false
    }

    func select(_ format: SourceFormatInfo) {
        guard var current = live else { return }
        current.source.select(format)
        current.format = format
        live = current
        renderer.reset()
        pushSettings()
    }

    /// Nil goes back to the source's own interpretation.
    func setColorSpec(_ spec: ColorSpecChoice?) {
        live?.colorOverride = spec
        pushSettings()
    }

    private func start(_ source: FrameSource) {
        close()
        live = Live(source: source, format: source.format)
        banner = nil
        pushSettings()
        let renderer = renderer
        source.start(
            onFrame: { renderer.submit($0) },
            onProblem: { [weak self] problem in
                MainActor.assumeIsolated { self?.live?.problem = problem }
            }
        )
        // A monitor that dims and locks mid-take is no use.
        UIApplication.shared.isIdleTimerDisabled = true
    }

    private func refreshDevices() {
        let before = Set(devices.map(\.uniqueID))
        devices = discovery.devices
        // Plugging a dongle in is the whole setup: open it when nothing else is live.
        if live == nil, connecting == nil,
           AVCaptureDevice.authorizationStatus(for: .video) == .authorized,
           let added = devices.first(where: { !before.contains($0.uniqueID) }) {
            open(added)
        }
    }

    private func unplugged(_ device: AVCaptureDevice) {
        guard let capture = live?.source as? CaptureSource, capture.device.uniqueID == device.uniqueID else { return }
        let name = capture.name
        close()
        banner = Banner(text: "\(name) was unplugged.")
    }

    private func pushSettings() {
        renderer.update(MonitorRenderer.Settings(
            tools: tools,
            colorSpec: live?.colorSpec ?? .hdVideo,
            aspect: live?.format.aspect ?? 16 / 9
        ))
    }

    private static let cameraOff = Banner(
        text: "Link Cam can't use capture devices until camera access is on. It never opens the iPad's own cameras.",
        opensSettings: true
    )
}
