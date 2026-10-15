import AppIntents
import UIKit

/// "Dịch tin đã sao chép": for Back Tap or the Action button, so a partner's message can be read
/// in Vietnamese without opening the keyboard.
struct TranslateCopiedIntent: AppIntent {
    static let title: LocalizedStringResource = "Dịch tin đã sao chép"
    static let description = IntentDescription("Dịch tin nhắn vừa sao chép sang tiếng Việt, dùng ngữ cảnh cuộc chat.")
    static let openAppWhenRun = false

    @Parameter(title: "Tin nhắn", description: "Để trống để dùng tin vừa sao chép")
    var text: String?

    func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
        guard let apiKey = SharedStore.apiKey else { throw IntentFailure.message(TranslateError.noKey.errorDescription ?? "") }
        var source = text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if source.isEmpty {
            source = await MainActor.run { UIPasteboard.general.string ?? "" }.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard !source.isEmpty else { throw IntentFailure.message("Chưa có tin nào được sao chép.") }

        let output: String
        var note: String?
        if let cached = SharedStore.cachedIncoming(source) {
            output = cached
        } else {
            do {
                let result = try await Translator(apiKey: apiKey).translate(source, to: .vietnamese, context: SharedStore.context())
                output = result.text
                note = result.note
                SharedStore.cacheIncoming(source, translation: output)
                SharedStore.addHistory(HistoryItem(side: .partner, source: source, translation: output,
                                                   languageCode: LanguageDetector.language(of: source)?.code ?? "",
                                                   note: result.note))
            } catch {
                throw IntentFailure.message(error.localizedDescription)
            }
        }
        SharedStore.remember(ChatLine(side: .partner, text: source, date: Date()))
        let dialog = note.map { "\(output)\n\nLưu ý: \($0)" } ?? output
        return .result(value: output, dialog: IntentDialog(stringLiteral: dialog))
    }
}

enum IntentFailure: Error, CustomLocalizedStringResourceConvertible {
    case message(String)

    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .message(let text): return "\(text)"
        }
    }
}

struct MSTransShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: TranslateCopiedIntent(),
            phrases: ["Dịch tin đã sao chép bằng \(.applicationName)", "\(.applicationName) dịch tin"],
            shortTitle: "Dịch tin đã sao chép",
            systemImageName: "translate"
        )
    }
}
