import LinkCamShared
import SwiftUI
import UIKit

private enum Sheet { case zebras, peaking, scope, guides }

/// One screen: the picture, a slim rail of tools beside it, and a status
/// strip. The rail sits next to the picture, never over it, so nothing hides
/// the edges of frame. The exposure graph floats over the picture,
/// see-through, like a camera's own info display. Tap the picture to hide
/// the rest and give it the whole screen.
struct MonitorScreen: View {
    @Environment(AppModel.self) private var model
    let live: AppModel.Live

    @State private var chrome = true
    @State private var sheet: Sheet?
    @State private var settings = false

    var body: some View {
        GeometryReader { geo in
            let landscape = geo.size.width > geo.size.height
            let layout = landscape ? AnyLayout(HStackLayout(spacing: 0)) : AnyLayout(VStackLayout(spacing: 0))
            ZStack(alignment: .topLeading) {
                LC.surround.ignoresSafeArea()

                // The picture keeps its place in the tree whatever the chrome
                // does, so the Metal view is resized rather than rebuilt.
                layout {
                    picture
                    if chrome {
                        ToolRail(vertical: landscape, onSheet: { sheet = sheet == $0 ? nil : $0 }, onSettings: {
                            sheet = nil
                            settings = true
                        })
                    }
                }
                .ignoresSafeArea(edges: chrome ? [] : .all)

                if chrome {
                    StatusStrip(live: live).padding(8)
                    if let open = sheet {
                        sheetView(open)
                            .padding(.trailing, landscape ? LC.rail : 0)
                            .padding(.bottom, landscape ? 0 : LC.rail)
                            .padding(8)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: landscape ? .trailing : .bottom)
                    }
                }
                if let problem = live.problem {
                    StatusBanner(banner: AppModel.Banner(text: problem)) { }
                        .frame(maxWidth: 520)
                        .padding(.top, 64)
                        .frame(maxWidth: .infinity)
                }
                if settings {
                    SettingsPanel(live: live) { settings = false }
                }
            }
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
    }

    private var picture: some View {
        ZStack {
            MonitorSurface(renderer: model.renderer)
            GuidesOverlay(aspect: live.format.aspect, tools: model.tools)
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture {
                    if sheet != nil { sheet = nil } else { chrome.toggle() }
                }
                .accessibilityLabel("Picture")
                .accessibilityHint("Shows or hides the controls")
                .accessibilityAddTraits(.isButton)
            // A tap hides the rail only: the scope and the batteries are what's being watched, so they stay.
            OnPicture(aspect: live.format.aspect) {
                ZStack {
                    if model.tools.scope {
                        ScopePanel(reading: model.scopes, kind: model.tools.scopeKind)
                            .frame(width: 220)
                            .padding(8)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                    }
                    PowerReadout()
                        .padding(8)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
    }

    @ViewBuilder
    private func sheetView(_ open: Sheet) -> some View {
        switch open {
        case .zebras:
            ToolSheet(title: "Zebras") {
                OptionGroup("Level (IRE)", options: ToolSettings.zebraLevels.map { ($0, "\($0)") }, value: model.tools.zebraLevel) {
                    model.tools.zebraLevel = $0
                    model.tools.zebras = true
                }
            }
        case .peaking:
            ToolSheet(title: "Focus peaking") {
                OptionGroup("Colour", options: PeakColor.allCases.map { ($0, $0.label) }, value: model.tools.peakingColor) {
                    model.tools.peakingColor = $0
                    model.tools.peaking = true
                }
                OptionGroup("Sensitivity", options: PeakSensitivity.allCases.map { ($0, $0.label) }, value: model.tools.peakingSensitivity) {
                    model.tools.peakingSensitivity = $0
                    model.tools.peaking = true
                }
            }
        case .scope:
            ToolSheet(title: "Exposure scope") {
                OptionGroup("Graph", options: ScopeGraph.allCases.map { ($0, $0.label) }, value: model.tools.scopeKind) {
                    model.tools.scopeKind = $0
                    model.tools.scope = true
                }
            }
        case .guides:
            ToolSheet(title: "Frame lines") {
                OptionGroup("Aspect", options: FrameLine.allCases.map { ($0, $0.label) }, value: model.tools.guideAspect) {
                    model.tools.guideAspect = $0
                    model.tools.guides = true
                }
            }
        }
    }
}

/// The tools, in a rail beside the picture: down the right edge held
/// sideways, along the bottom upright.
private struct ToolRail: View {
    @Environment(AppModel.self) private var model
    let vertical: Bool
    let onSheet: (Sheet) -> Void
    let onSettings: () -> Void

    var body: some View {
        let items = Group {
            ToolToggle(label: "FC", description: "False color", active: model.tools.falseColor, action: { model.tools.falseColor.toggle() })
            ToolToggle(label: "ZEB", description: "Zebras", active: model.tools.zebras, onSettings: { onSheet(.zebras) }, action: { model.tools.zebras.toggle() })
            ToolToggle(label: "PK", description: "Focus peaking", active: model.tools.peaking, onSettings: { onSheet(.peaking) }, action: { model.tools.peaking.toggle() })
            ToolToggle(label: model.tools.scopeKind.short, description: "Exposure scope", active: model.tools.scope, onSettings: { onSheet(.scope) }, action: { model.tools.scope.toggle() })
            RailDivider(vertical: vertical)
            ToolToggle(label: "GDE", description: "Frame lines", active: model.tools.guides, onSettings: { onSheet(.guides) }, action: { model.tools.guides.toggle() })
            ToolToggle(label: "SAFE", description: "Safe areas", active: model.tools.safeAreas, action: { model.tools.safeAreas.toggle() })
            ToolToggle(label: "2×", description: "Punch in", active: model.tools.punchIn, action: { model.tools.punchIn.toggle() })
            RailDivider(vertical: vertical)
            ToolToggle(label: "SET", description: "Settings", active: false, isSwitch: false, systemImage: "gearshape.fill", action: onSettings)
        }
        Group {
            if vertical {
                ScrollView(.vertical, showsIndicators: false) {
                    VStack(spacing: 0) { items }.frame(maxWidth: .infinity)
                }
                .scrollBounceBehavior(.basedOnSize)
                .frame(width: LC.rail)
                .frame(maxHeight: .infinity)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 0) { items }.frame(maxHeight: .infinity)
                }
                .scrollBounceBehavior(.basedOnSize)
                .frame(height: LC.rail)
                .frame(maxWidth: .infinity)
            }
        }
        .background(LC.inkRaised)
        .defaultScrollAnchor(.center)
    }
}

/// A tool on the rail: a short label that turns blue with an underline when
/// the tool is on. Tap toggles; long-press opens the tool's settings, when it
/// has any.
private struct ToolToggle: View {
    let label: String
    let description: String
    let active: Bool
    var onSettings: (() -> Void)?
    /// False for a rail button that opens something rather than switching a tool.
    var isSwitch = true
    var systemImage: String?
    let action: () -> Void

    var body: some View {
        let color = active ? LC.brandBright : LC.muted
        ZStack(alignment: .bottom) {
            Group {
                if let systemImage {
                    Image(systemName: systemImage).font(.system(size: 17))
                } else {
                    Text(label).font(.lcToggle).tracking(0.4)
                }
            }
            .foregroundStyle(color)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            if active {
                Rectangle().fill(LC.brandBright).frame(width: 20, height: 2)
            }
        }
        .frame(width: LC.hitTarget + 8, height: LC.railItem + 8)
        .contentShape(Rectangle())
        .onTapGesture(perform: action)
        .onLongPressGesture(minimumDuration: 0.4) { onSettings?() }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(description)
        .accessibilityValue(isSwitch ? (active ? "On" : "Off") : "")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(.default, action)
        .accessibilityActions {
            if let onSettings { Button("\(description) settings", action: onSettings) }
        }
    }
}

private struct RailDivider: View {
    let vertical: Bool

    var body: some View {
        Rectangle()
            .fill(LC.inkLine)
            .frame(width: vertical ? 24 : 1, height: vertical ? 1 : 24)
            .padding(vertical ? .vertical : .horizontal, 3)
    }
}

/// Lays [content] out over the picture itself, not the black around it.
struct OnPicture<Content: View>: View {
    let aspect: Float
    @ViewBuilder let content: Content

    var body: some View {
        GeometryReader { geo in
            let fit = Bridge.shared.fit(width: Float(geo.size.width), height: Float(geo.size.height), aspect: aspect)
            content
                .frame(width: CGFloat(fit.width), height: CGFloat(fit.height))
                .offset(x: CGFloat(fit.left), y: CGFloat(fit.top))
        }
    }
}

/// Format · colour interpretation · what the pipeline is doing, refreshed twice a second.
private struct StatusStrip: View {
    @Environment(AppModel.self) private var model
    let live: AppModel.Live

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(headline).font(.lcReadout).foregroundStyle(LC.paper)
            TimelineView(.periodic(from: .now, by: 0.5)) { _ in
                Text(readout).font(.lcReadout).foregroundStyle(LC.muted)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(LC.inkRaised)
        .allowsHitTesting(false)
    }

    private var headline: String {
        var parts = [live.name, live.format.label, live.colorSpec.label, model.tools.signal.short]
        if model.tools.noiseReduction != .off { parts.append("NR \(model.tools.noiseReduction.label.lowercased())") }
        return parts.joined(separator: " · ")
    }

    private var readout: String {
        let s = model.renderer.currentStats
        let src = live.source.stats()
        return String(format: "%.1f fps · %.1f ms · dropped %ld", s.fps, s.latencyMs, src.dropped)
    }
}

/// The iPad's battery: one of the things that ends a shoot early.
private struct PowerReadout: View {
    @State private var level: Float = UIDevice.current.batteryLevel
    @State private var state: UIDevice.BatteryState = UIDevice.current.batteryState

    var body: some View {
        Group {
            if level >= 0 {
                let percent = Int((level * 100).rounded())
                let charging = state == .charging || state == .full
                let low = percent <= 15 && !charging
                HStack(spacing: 6) {
                    Text("IPAD").lcLegend()
                    BatteryGlyph(percent: percent, color: low ? LC.low : charging ? LC.brandBright : LC.paper)
                    Text("\(percent)%").font(.lcCameraValue).foregroundStyle(low ? LC.low : LC.paper)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(LC.inkRaised.opacity(0.6), in: RoundedRectangle(cornerRadius: LC.radius))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("iPad battery \(percent) percent\(charging ? ", charging" : "")")
            }
        }
        .allowsHitTesting(false)
        .onAppear {
            UIDevice.current.isBatteryMonitoringEnabled = true
            level = UIDevice.current.batteryLevel
            state = UIDevice.current.batteryState
        }
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.batteryLevelDidChangeNotification)) { _ in
            level = UIDevice.current.batteryLevel
        }
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.batteryStateDidChangeNotification)) { _ in
            state = UIDevice.current.batteryState
        }
    }
}

/// A battery outline filled to [percent].
private struct BatteryGlyph: View {
    let percent: Int
    let color: Color

    var body: some View {
        Canvas { ctx, size in
            let stroke: CGFloat = 1.2
            let nub: CGFloat = 2
            let body = CGRect(x: 0, y: 0, width: size.width - nub, height: size.height)
            ctx.stroke(Path(roundedRect: body.insetBy(dx: stroke / 2, dy: stroke / 2), cornerRadius: 2), with: .color(color), lineWidth: stroke)
            ctx.fill(Path(CGRect(x: body.width, y: size.height * 0.3, width: nub, height: size.height * 0.4)), with: .color(color))
            let inset = stroke * 1.8
            let full = body.width - inset * 2
            ctx.fill(
                Path(CGRect(x: inset, y: inset, width: full * CGFloat(min(max(percent, 0), 100)) / 100, height: body.height - inset * 2)),
                with: .color(color)
            )
        }
        .frame(width: 20, height: 10)
    }
}
