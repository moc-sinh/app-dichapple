import SwiftUI
import UIKit

extension Color {
    static let brandGreen = Color(uiColor: Brand.green)
    static let brandGreenFill = Color(uiColor: Brand.greenFill)
    static let brandOrange = Color(uiColor: Brand.orange)
    static let brandGold = Color(uiColor: Brand.gold)
    static let brandGoldFill = Color(uiColor: Brand.goldFill)
    static let greenWash = Color(uiColor: Brand.greenWash)
    static let orangeWash = Color(uiColor: Brand.orangeWash)
    static let goldWash = Color(uiColor: Brand.goldWash)
}

extension ChatLine.Side {
    /// The two accents of the logo: green for you, orange for the partner.
    var color: Color { self == .me ? .brandGreen : .brandOrange }
    var label: String { self == .me ? "Bạn gửi" : "Đối tác" }
    var symbol: String { self == .me ? "arrow.up.right" : "arrow.down.left" }
}

/// The mark and the name, once, at the top of the Translate tab.
struct BrandTitle: View {
    var body: some View {
        HStack(spacing: 8) {
            Image("Logo")
                .resizable()
                .scaledToFit()
                .frame(width: 24, height: 24)
            Text(Brand.name)
                .font(.headline)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Brand.name)
        .accessibilityAddTraits(.isHeader)
    }
}

/// A rounded panel on the grouped background. `accent` draws a side bar in that colour.
struct Card<Content: View>: View {
    var accent: Color? = nil
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) { content }
            .padding(16)
            .padding(.leading, accent == nil ? 0 : 4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(alignment: .leading) {
                if let accent {
                    Capsule()
                        .fill(accent)
                        .frame(width: 4)
                        .padding(.vertical, 16)
                        .padding(.leading, 8)
                        .accessibilityHidden(true)
                }
            }
    }
}

/// Who a message is from, and its language: "Đối tác · Tiếng Trung".
struct SideTag: View {
    let side: ChatLine.Side
    let language: String

    var body: some View {
        Label(language.isEmpty ? side.label : "\(side.label) · \(language)", systemImage: side.symbol)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(side.color)
    }
}

/// Something worth a second look, in gold.
struct NoteBox: View {
    let text: String

    var body: some View {
        Label {
            Text(text)
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill")
        }
        .font(.subheadline)
        .foregroundStyle(Color.brandGold)
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.goldWash, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityLabel("Lưu ý: \(text)")
    }
}

/// How far setup has got. The keyboard records Full Access the first time it opens with it.
struct SetupStatus {
    var keyboardAdded: Bool
    var keyboardReady: Bool
    var hasKey: Bool

    var done: Bool { keyboardReady && hasKey }
    var remaining: Int { (keyboardReady ? 0 : 1) + (hasKey ? 0 : 1) }

    static var current: SetupStatus {
        SetupStatus(keyboardAdded: InstalledKeyboard.isAdded, keyboardReady: SharedStore.keyboardHasFullAccess,
                    hasKey: SharedStore.apiKey != nil)
    }
}

/// The keyboard inside this app, as iOS knows it (installers may rename its bundle ID).
enum InstalledKeyboard {
    static var bundles: [Bundle] {
        guard let plugins = Bundle.main.builtInPlugInsURL,
              let items = try? FileManager.default.contentsOfDirectory(at: plugins, includingPropertiesForKeys: nil) else { return [] }
        return items.compactMap { Bundle(url: $0) }.filter {
            ($0.object(forInfoDictionaryKey: "NSExtension") as? [String: Any])?["NSExtensionPointIdentifier"] as? String
                == "com.apple.keyboard-service"
        }
    }

    /// Added under Settings › Keyboards. Read from the system's list, so it is known even if the
    /// keyboard has never run.
    static var isAdded: Bool {
        let enabled = UserDefaults.standard.array(forKey: "AppleKeyboards") as? [String] ?? []
        return bundles.contains { enabled.contains($0.bundleIdentifier ?? "") }
    }
}

enum Clipboard {
    /// Copies without the keyboard then offering to translate our own text.
    static func copy(_ text: String) {
        UIPasteboard.general.string = text
        SharedStore.handledPasteboard = UIPasteboard.general.changeCount
    }

    /// For the diagnostics (the end of recent typing): this iPhone only, not the user's other
    /// Apple devices, and gone from the clipboard after a quarter of an hour.
    static func copyPrivately(_ text: String) {
        UIPasteboard.general.setItems([["public.utf8-plain-text": text]],
                                      options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(15 * 60)])
        SharedStore.handledPasteboard = UIPasteboard.general.changeCount
    }
}

func openSystemSettings() {
    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
}

func openShortcutsApp() {
    if let url = URL(string: "shortcuts://") { UIApplication.shared.open(url) }
}
