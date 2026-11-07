import SwiftUI
import UIKit

/// Pick one of a fixed set, as a row of segments that wraps: the same
/// control as linker-app's OptionGroup.
struct OptionGroup<T: Hashable>: View {
    let legend: String
    let options: [(T, String)]
    let value: T
    let onChange: (T) -> Void

    init(_ legend: String, options: [(T, String)], value: T, onChange: @escaping (T) -> Void) {
        self.legend = legend
        self.options = options
        self.value = value
        self.onChange = onChange
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(legend.uppercased()).lcLegend()
            FlowLayout(spacing: 6) {
                ForEach(options, id: \.0) { item in
                    let (option, label) = item
                    let selected = option == value
                    Button { onChange(option) } label: {
                        Text(label)
                            .font(.lcLabel)
                            .foregroundStyle(selected ? LC.brandBright : LC.paper)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .frame(minWidth: 48, minHeight: 40)
                            .overlay(
                                RoundedRectangle(cornerRadius: LC.radiusChip)
                                    .strokeBorder(selected ? LC.brandBright : LC.inkLine, lineWidth: 1)
                            )
                            .contentShape(RoundedRectangle(cornerRadius: LC.radiusChip))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected ? [.isSelected] : [])
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(legend)
    }
}

/// Lays children out left to right, wrapping onto new rows.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, widest: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                y += rowHeight + spacing
                x = 0
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            widest = max(widest, x - spacing)
        }
        return CGSize(width: min(widest, width), height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                y += rowHeight + spacing
                x = bounds.minX
                rowHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

/// A tool's settings, one level deep. Opaque, never translucent: anything
/// see-through over the picture would tint how it's judged.
struct ToolSheet<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title).font(.lcLabel).foregroundStyle(LC.paper)
            content
        }
        .padding(16)
        .frame(maxWidth: 360, alignment: .leading)
        .background(LC.inkRaised, in: RoundedRectangle(cornerRadius: LC.radius))
        .overlay(RoundedRectangle(cornerRadius: LC.radius).strokeBorder(LC.inkLine, lineWidth: 1))
        // Swallow taps so they don't fall through to the picture and hide the chrome.
        .contentShape(Rectangle())
        .onTapGesture {}
    }
}

/// A plain outlined button, for actions that aren't tools.
struct LCButton: View {
    let title: String
    let action: () -> Void

    init(_ title: String, action: @escaping () -> Void) {
        self.title = title
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.lcLabel)
                .foregroundStyle(LC.paper)
                .padding(.horizontal, 16)
                .frame(minHeight: LC.hitTarget)
                .overlay(RoundedRectangle(cornerRadius: LC.radiusChip).strokeBorder(LC.inkLine, lineWidth: 1))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// The glyph and the name, side by side, as Linker draws its own wordmark.
/// Both come from /brand, outside the Apache licence.
struct Wordmark: View {
    var size: CGFloat = 32

    var body: some View {
        HStack(spacing: 10) {
            Image("Glyph")
                .resizable()
                .scaledToFit()
                .frame(width: size, height: size)
                .accessibilityHidden(true)
            Text(Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "")
                .font(.system(size: size * 0.7, weight: .semibold))
                .foregroundStyle(LC.paper)
        }
        .accessibilityElement(children: .combine)
    }
}

/// A short message across the top of a screen, with an action when there's one to take.
struct StatusBanner: View {
    let banner: AppModel.Banner
    let onDismiss: () -> Void

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(banner.text).font(.lcBody).foregroundStyle(LC.paper)
            Spacer(minLength: 0)
            if banner.opensSettings, let url = URL(string: UIApplication.openSettingsURLString) {
                Link("Settings", destination: url).font(.lcLabel).foregroundStyle(LC.brandBright)
            }
            Button("Dismiss", action: onDismiss).font(.lcLabel).foregroundStyle(LC.muted)
        }
        .padding(14)
        .background(LC.inkRaised, in: RoundedRectangle(cornerRadius: LC.radius))
        .overlay(RoundedRectangle(cornerRadius: LC.radius).strokeBorder(LC.inkLine, lineWidth: 1))
    }
}
