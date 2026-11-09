import Foundation

/// Keeps the tool settings between launches, in UserDefaults, as the same
/// JSON the Android app keeps in its preferences.
struct SettingsStore {
    private let defaults = UserDefaults.standard
    private static let toolsKey = "tools.v1"

    func loadTools() -> ToolSettings {
        guard let data = defaults.data(forKey: Self.toolsKey),
              let tools = try? JSONDecoder().decode(ToolSettings.self, from: data)
        else { return ToolSettings() }
        return tools
    }

    func saveTools(_ tools: ToolSettings) {
        if let data = try? JSONEncoder().encode(tools) { defaults.set(data, forKey: Self.toolsKey) }
    }
}
