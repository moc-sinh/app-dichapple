import AppIntents
import UIKit

/// "Dịch tin đã sao chép", both ways, for Back Tap or the Action button:
/// - a partner's message → read it in Vietnamese, without opening the keyboard;
/// - your own Vietnamese → translated into the partner's language and put on the clipboard,
///   ready to paste. That is how to reply in apps that turn off other keyboards, such as Zalo.
struct TranslateCopiedIntent: AppIntent {
    static let title: LocalizedStringResource = "Dịch tin đã sao chép"
    static let description = IntentDescription(
        "Tin của đối tác: dịch sang tiếng Việt. Câu tiếng Việt của bạn: dịch sang tiếng của đối tác và chép sẵn để dán (dùng cho Zalo).")
    static let openAppWhenRun = false

    @Parameter(title: "Tin nhắn", description: "Để trống để dùng tin vừa sao chép")
    var text: String?

    func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
        guard let apiKey = SharedStore.apiKey else { throw IntentFailure.message(TranslateError.noKey.errorDescription ?? "") }
        var source = text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if source.isEmpty {
            source = await MainActor.run { UIPasteboard.general.string ?? "" }.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard !source.isEmpty else {
            throw IntentFailure.message("Chưa đọc được tin đã sao chép. Sao chép lại; nếu vẫn vậy, trong app Phím tắt thêm thao tác “Lấy bảng tạm” trước thao tác MSTrans.")
        }
        let answer: (value: String, dialog: String)
        if LanguageDetector.isVietnamese(source) {
            answer = try await reply(source, apiKey: apiKey)
        } else {
            answer = try await read(source, apiKey: apiKey)
        }
        return .result(value: answer.value, dialog: IntentDialog(stringLiteral: answer.dialog))
    }

    /// The partner's message, in Vietnamese.
    private func read(_ source: String, apiKey: String) async throws -> (value: String, dialog: String) {
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
        return (output, note.map { "\(output)\n\nLưu ý: \($0)" } ?? output)
    }

    /// Your Vietnamese, in the partner's language, copied so it can be pasted into the chat.
    private func reply(_ source: String, apiKey: String) async throws -> (value: String, dialog: String) {
        let context = SharedStore.context()
        let target = Language.target(code: SharedStore.targetCode, context: context)
        let result: Translation
        do {
            result = try await Translator(apiKey: apiKey).translate(source, to: target, context: context, checkMeaning: SharedStore.checkMeaning)
        } catch {
            throw IntentFailure.message(error.localizedDescription)
        }
        SharedStore.remember(ChatLine(side: .me, text: result.text, date: Date()))
        SharedStore.addHistory(HistoryItem(side: .me, source: source, translation: result.text, languageCode: target.code,
                                           meaning: result.meaning, note: result.note))
        let copied: Bool = await MainActor.run {
            let before = UIPasteboard.general.changeCount
            UIPasteboard.general.string = result.text
            let after = UIPasteboard.general.changeCount
            // The keyboard should not offer our own translation as a partner's message.
            if after != before { SharedStore.handledPasteboard = after }
            return after != before
        }
        var dialog = copied
            ? "Đã chép bản \(target.name), dán vào ô chat:\n\(result.text)"
            : "Bản \(target.name) (chưa tự chép được: trong app Phím tắt thêm thao tác “Sao chép vào bảng tạm” sau thao tác MSTrans):\n\(result.text)"
        if let meaning = result.meaning { dialog += "\n\nNghĩa: \(meaning)" }
        if let note = result.note { dialog += "\n\nLưu ý: \(note)" }
        return (result.text, dialog)
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
