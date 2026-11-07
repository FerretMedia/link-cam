import SwiftUI

@main
struct LinkCamApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(.dark)
                .tint(LC.brandBright)
        }
    }
}

/// The arrival screen until something is live, then the monitor.
struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ZStack {
            if let live = model.live {
                MonitorScreen(live: live)
                    .transition(.opacity)
            } else {
                ArrivalScreen()
                    .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.live == nil)
        .background(LC.surround)
    }
}
