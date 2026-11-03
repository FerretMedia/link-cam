import SwiftUI

private enum SettingsSection: String, CaseIterable, Identifiable {
    case tools = "Monitor tools"
    case source = "Video source"

    var id: String { rawValue }
}

/// Everything that's set up rather than reached for while shooting: how the
/// tools behave and the capture device's format. A large panel over the
/// picture, which shows dimmed around it; a tap outside closes it.
struct SettingsPanel: View {
    @Environment(AppModel.self) private var model
    let live: AppModel.Live
    let onClose: () -> Void

    @State private var section = SettingsSection.tools

    var body: some View {
        ZStack {
            Color.black.opacity(0.7)
                .ignoresSafeArea()
                .onTapGesture(perform: onClose)
                .accessibilityLabel("Close settings")
                .accessibilityAddTraits(.isButton)
            HStack(spacing: 0) {
                nav
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        Text(section.rawValue).font(.lcTitle).foregroundStyle(LC.paper)
                        VStack(alignment: .leading, spacing: 20) {
                            switch section {
                            case .tools: ToolSettingsView()
                            case .source: SourceSettingsView(live: live, onDisconnect: {
                                onClose()
                                model.close()
                            })
                            }
                        }
                        .frame(maxWidth: 560, alignment: .leading)
                    }
                    .padding(.horizontal, 28)
                    .padding(.vertical, 20)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .frame(maxWidth: 1100)
            .background(LC.ink, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(LC.inkLine, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
        }
    }

    private var nav: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 4) {
                Button(action: onClose) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(LC.paper)
                        .frame(width: LC.hitTarget, height: LC.hitTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Back to the monitor")
                Text("Settings").font(.lcTitle).foregroundStyle(LC.paper)
            }
            ForEach(SettingsSection.allCases) { s in
                Button { section = s } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(s.rawValue).font(.lcLabel).foregroundStyle(s == section ? LC.paper : LC.muted)
                        Text(summary(s)).font(.lcReadout).foregroundStyle(LC.faint).lineLimit(1)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(s == section ? LC.inkLine : LC.inkRaised, in: RoundedRectangle(cornerRadius: LC.radiusChip))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(s == section ? [.isSelected] : [])
            }
            Spacer()
        }
        .padding(12)
        .frame(width: 232)
        .frame(maxHeight: .infinity)
        .background(LC.inkRaised)
    }

    private func summary(_ s: SettingsSection) -> String {
        let t = model.tools
        switch s {
        case .tools:
            let on = [
                t.falseColor ? "False colour" : nil,
                t.zebras ? "Zebras" : nil,
                t.peaking ? "Peaking" : nil,
                t.scope ? t.scopeKind.label : nil,
                t.guides ? "Frame lines" : nil,
            ].compactMap { $0 }
            return on.isEmpty ? "All off" : on.joined(separator: " · ")
        case .source:
            return "\(live.name) · \(t.signal.short) · \(live.format.label)"
        }
    }
}

/// Each tool's settings, and whether it's on. The rail switches them while
/// shooting; this is the place to set them up.
private struct ToolSettingsView: View {
    @Environment(AppModel.self) private var model
    private let onOff = [(false, "Off"), (true, "On")]

    var body: some View {
        ToolBlock(title: "Exposure scope", about: "A histogram or waveform over the picture, with how much is crushed or clipped.") {
            OptionGroup("Show", options: onOff, value: model.tools.scope) { model.tools.scope = $0 }
            OptionGroup("Graph", options: ScopeGraph.allCases.map { ($0, $0.label) }, value: model.tools.scopeKind) { model.tools.scopeKind = $0 }
        }
        ToolBlock(title: "Zebras", about: "Stripes over everything brighter than the level.") {
            OptionGroup("Show", options: onOff, value: model.tools.zebras) { model.tools.zebras = $0 }
            OptionGroup("Level (IRE)", options: ToolSettings.zebraLevels.map { ($0, "\($0)") }, value: model.tools.zebraLevel) { model.tools.zebraLevel = $0 }
        }
        ToolBlock(title: "Focus peaking", about: "Outlines what's in focus.") {
            OptionGroup("Show", options: onOff, value: model.tools.peaking) { model.tools.peaking = $0 }
            OptionGroup("Colour", options: PeakColor.allCases.map { ($0, $0.label) }, value: model.tools.peakingColor) { model.tools.peakingColor = $0 }
            OptionGroup("Sensitivity", options: PeakSensitivity.allCases.map { ($0, $0.label) }, value: model.tools.peakingSensitivity) { model.tools.peakingSensitivity = $0 }
        }
        ToolBlock(title: "False colour", about: "Paints exposure levels in colours, from crushed blacks to clipped highlights.") {
            OptionGroup("Show", options: onOff, value: model.tools.falseColor) { model.tools.falseColor = $0 }
        }
        ToolBlock(title: "Frame lines", about: "Where a crop for another aspect ratio falls, and the title and action safe areas.") {
            OptionGroup("Show", options: onOff, value: model.tools.guides) { model.tools.guides = $0 }
            OptionGroup("Aspect", options: FrameLine.allCases.map { ($0, $0.label) }, value: model.tools.guideAspect) { model.tools.guideAspect = $0 }
            OptionGroup("Safe areas", options: onOff, value: model.tools.safeAreas) { model.tools.safeAreas = $0 }
        }
    }
}

private struct SourceSettingsView: View {
    @Environment(AppModel.self) private var model
    let live: AppModel.Live
    let onDisconnect: () -> Void

    var body: some View {
        ToolBlock(
            title: "Picture",
            about: "Set Signal to what the camera records. In HDR PQ or HLG, Link Cam shows the footage the way DaVinci Resolve "
                + "turns it into Rec.709: 100 nits as white, highlights rolled off from 1000 nits. A capture dongle can't tell "
                + "Link Cam this itself. Noise reduction averages still parts of the picture over several frames and smooths "
                + "moving parts. Both settings change only what this screen shows, never the recording; leave noise reduction "
                + "off to judge how noisy the footage really is."
        ) {
            OptionGroup("Signal", options: Signal.allCases.map { ($0, $0.label) }, value: model.tools.signal) { model.tools.signal = $0 }
            OptionGroup("Noise reduction", options: Denoise.allCases.map { ($0, $0.label) }, value: model.tools.noiseReduction) { model.tools.noiseReduction = $0 }
        }
        Text(
            "\(live.name) sends \(live.format.label). Link Cam picks the best format on its own; "
                + "the colour settings only matter if the picture looks wrong."
                + (model.tools.signal != .sdr ? " In HDR the matrix is BT.2020's, as HDR video always uses." : "")
        )
        .font(.lcBody)
        .foregroundStyle(LC.muted)
        if live.source.formats.count > 1 {
            OptionGroup("Format", options: live.source.formats.map { ($0.id, $0.label) }, value: live.format.id) { id in
                if let f = live.source.formats.first(where: { $0.id == id }) { model.select(f) }
            }
        }
        OptionGroup("Matrix", options: ColorSpecChoice.Matrix.allCases.map { ($0, $0.label) }, value: live.colorSpec.matrix) {
            model.setColorSpec(ColorSpecChoice(matrix: $0, fullRange: live.colorSpec.fullRange))
        }
        OptionGroup("Range", options: [(true, "full"), (false, "limited")], value: live.colorSpec.fullRange) {
            model.setColorSpec(ColorSpecChoice(matrix: live.colorSpec.matrix, fullRange: $0))
        }
        if live.colorOverride != nil {
            LCButton("Use \(live.source.defaultColorSpec.label) from the source") { model.setColorSpec(nil) }
        }
        LCButton("Disconnect \(live.name)", action: onDisconnect)
    }
}

private struct ToolBlock<Content: View>: View {
    let title: String
    let about: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.lcLabel).foregroundStyle(LC.paper)
                Text(about).font(.system(size: 13)).foregroundStyle(LC.muted)
            }
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LC.inkRaised, in: RoundedRectangle(cornerRadius: LC.radius))
    }
}
