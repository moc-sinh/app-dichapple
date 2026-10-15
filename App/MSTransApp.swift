import SwiftUI

@main
struct MSTransApp: App {
    var body: some Scene {
        WindowGroup { RootView() }
    }
}

enum AppTab: String, Hashable {
    case translate, history, glossary, settings
}

struct RootView: View {
    /// Launching with `-initialTab history` opens that tab (CI takes screenshots this way).
    @State private var tab = AppTab(rawValue: UserDefaults.standard.string(forKey: "initialTab") ?? "") ?? .translate

    var body: some View {
        TabView(selection: $tab) {
            TranslateView(showSettings: { tab = .settings }, showHistory: { tab = .history })
                .tabItem { Label("Dịch", systemImage: "translate") }
                .tag(AppTab.translate)
            HistoryView()
                .tabItem { Label("Lịch sử", systemImage: "clock.arrow.circlepath") }
                .tag(AppTab.history)
            GlossaryView()
                .tabItem { Label("Thuật ngữ", systemImage: "character.book.closed.fill") }
                .tag(AppTab.glossary)
            SettingsView()
                .tabItem { Label("Cài đặt", systemImage: "gearshape.fill") }
                .tag(AppTab.settings)
        }
        .tint(.brandGreen)
    }
}
