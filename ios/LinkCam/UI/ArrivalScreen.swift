import AVFoundation
import SwiftUI

/// Where people arrive: nothing is live yet. It lists any capture device
/// that's plugged in, or explains how to plug one in, and offers the test
/// pattern.
///
/// Held sideways it's two columns, what this is on the left and what to do
/// on the right; upright they stack.
struct ArrivalScreen: View {
    @Environment(AppModel.self) private var model

    private enum Phase {
        case waiting
        case ready([AVCaptureDevice])
        case connecting(String)
    }

    private var phase: Phase {
        if let name = model.connecting { return .connecting(name) }
        return model.devices.isEmpty ? .waiting : .ready(model.devices)
    }

    var body: some View {
        GeometryReader { geo in
            let sideways = geo.size.width > geo.size.height && geo.size.width >= 600
            ZStack {
                BrandSquares()
                ScrollView {
                    Group {
                        if sideways {
                            HStack(alignment: .center, spacing: 48) {
                                intro.frame(maxWidth: .infinity, alignment: .leading)
                                actions.frame(maxWidth: .infinity)
                            }
                            .frame(maxWidth: 920)
                        } else {
                            VStack(alignment: .leading, spacing: 32) {
                                intro
                                actions
                            }
                            .frame(maxWidth: 440)
                        }
                    }
                    .padding(.horizontal, 32)
                    .padding(.vertical, 24)
                    .frame(maxWidth: .infinity, minHeight: geo.size.height)
                }
                .scrollBounceBehavior(.basedOnSize)
            }
        }
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: 0) {
            Wordmark(size: 28)
            Spacer().frame(height: 36)
            switch phase {
            case .waiting: StatusLine(text: "Waiting for a capture device", lit: false)
            case .ready(let devices):
                StatusLine(text: devices.count == 1 ? "Capture device found" : "\(devices.count) capture devices found", lit: true)
            case .connecting: StatusLine(text: "Connecting", lit: true, pulse: true)
            }
            Spacer().frame(height: 12)
            Text(title)
                .font(.lcDisplay)
                .tracking(-0.9)
                .foregroundStyle(LC.paper)
                .accessibilityAddTraits(.isHeader)
            Spacer().frame(height: 12)
            Text(about)
                .font(.lcBody)
                .lineSpacing(7)
                .foregroundStyle(LC.muted)
        }
    }

    private var title: String {
        switch phase {
        case .waiting: "Plug in your camera"
        case .ready: "Ready to monitor"
        case .connecting(let name): "Opening \(name)…"
        }
    }

    private var about: String {
        switch phase {
        case .connecting: "If iPadOS asks, allow Link Cam to use the camera. It only opens the capture device."
        default: "Your camera's HDMI picture on this screen, with exposure and focus tools over it. Nothing here changes the recording."
        }
    }

    private var actions: some View {
        VStack(spacing: 12) {
            if let banner = model.banner {
                StatusBanner(banner: banner) { model.banner = nil }
            }
            Panel {
                switch phase {
                case .waiting, .connecting:
                    Step(number: 1, text: "HDMI out of the camera into a capture dongle")
                    Step(number: 2, text: "The dongle into this iPad's USB-C port")
                    Step(number: 3, text: "Link Cam finds it by itself")
                case .ready(let devices):
                    ForEach(devices, id: \.uniqueID) { device in
                        ActionRow(title: device.localizedName, detail: "USB video capture") { model.open(device) }
                    }
                }
            }
            Panel {
                ActionRow(title: "Test pattern", detail: "Try the tools without a camera") { model.openTestPattern() }
            }
        }
    }
}

/// A small caps line with a dot: brand blue when something is there, grey while waiting.
private struct StatusLine: View {
    let text: String
    let lit: Bool
    var pulse = false
    @State private var dim = false

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(lit ? LC.brandBright : LC.faint)
                .frame(width: 8, height: 8)
                .opacity(pulse && dim ? 0.3 : 1)
                .animation(pulse ? .linear(duration: 0.7).repeatForever(autoreverses: true) : .default, value: dim)
                .onAppear { dim = pulse }
            Text(text.uppercased()).lcLegend(lit ? LC.paper : LC.muted)
        }
    }
}

private struct Panel<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 4) { content }
            .padding(8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(LC.ink.opacity(0.85), in: RoundedRectangle(cornerRadius: LC.radius))
            .overlay(RoundedRectangle(cornerRadius: LC.radius).strokeBorder(LC.inkLine, lineWidth: 1))
    }
}

private struct Step: View {
    let number: Int
    let text: String

    var body: some View {
        HStack(spacing: 12) {
            Text("\(number)")
                .font(.lcToggle)
                .foregroundStyle(LC.muted)
                .frame(width: 24, height: 24)
                .overlay(Circle().strokeBorder(LC.inkLine, lineWidth: 1))
            Text(text).font(.lcLabel).foregroundStyle(LC.paper)
        }
        .padding(.horizontal, 8)
        .frame(minHeight: LC.hitTarget, alignment: .leading)
    }
}

private struct ActionRow: View {
    let title: String
    let detail: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.lcLabel).foregroundStyle(LC.paper).lineLimit(1)
                    Text(detail).font(.lcReadout).foregroundStyle(LC.muted)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(LC.muted)
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
