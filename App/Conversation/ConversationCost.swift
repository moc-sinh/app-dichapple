import Foundation

/// What the Hội thoại tab spends with OpenAI and Soniox, estimated from what was sent and what OpenAI
/// reports (their prices of October 2026, in US dollars). The iPhone's own voice costs nothing.
struct CostMeter: Codable, Equatable {
    enum Rate {
        /// gpt-transcribe, per minute of sound sent (only speech is sent, not the pauses).
        static let transcribePerMinute = 0.0045
        /// gpt-4o-mini-tts, per minute of voice made ($12 per million audio tokens).
        static let voicePerMinute = 0.015
        /// gpt-realtime-translate, per minute of sound sent to one session.
        static let livePerMinute = 0.034
        /// Soniox's live recognition with translation, per minute of sound sent (about 0.18 USD an hour).
        static let sonioxPerMinute = 0.003
    }

    private(set) var dollars = 0.0

    mutating func transcribed(seconds: Double) { dollars += max(0, seconds) / 60 * Rate.transcribePerMinute }

    mutating func voiced(seconds: Double) { dollars += max(0, seconds) / 60 * Rate.voicePerMinute }

    /// Sound sent to a live session: OpenAI's, or Soniox's.
    mutating func streamed(seconds: Double, soniox: Bool = false) {
        dollars += max(0, seconds) / 60 * (soniox ? Rate.sonioxPerMinute : Rate.livePerMinute)
    }

    /// A translation or a summary, by the tokens OpenAI counted.
    mutating func used(_ usage: TokenUsage?, by model: TranslationModel) {
        guard let usage else { return }
        let price = model.price
        let fresh = max(0, usage.input - usage.cachedInput)
        dollars += (Double(fresh) * price.input + Double(usage.cachedInput) * price.cached
            + Double(usage.output) * price.output) / 1_000_000
    }

    /// Per hour of people speaking, for the choices made: about 12 sentences a minute, each about
    /// 750 tokens in (instructions, glossary, the meeting's last sentences) and 60 out (160 with
    /// GPT-6.1 Sol's reasoning); read aloud, about as long in Vietnamese as it was said.
    /// Soniox's words are read aloud like Chính xác's. `translatesAgain`: each sentence of Soniox or
    /// Dịch liền is translated again by GPT, as Chính xác's are.
    static func perHour(live: Bool, soniox: Bool = false, translatesAgain: Bool = false, model: TranslationModel,
                        readAloud: Bool, openAIVoice: Bool) -> Double {
        let price = model.price
        let sentence = (750 * price.input + (model == .luna ? 60 : 160) * price.output) / 1_000_000
        let again = translatesAgain ? 720 * sentence : 0
        if soniox { return 60 * Rate.sonioxPerMinute + again + (readAloud && openAIVoice ? 60 * Rate.voicePerMinute : 0) }
        if live { return 60 * Rate.livePerMinute + again }
        var hour = 60 * Rate.transcribePerMinute + 720 * sentence
        if readAloud, openAIVoice { hour += 60 * Rate.voicePerMinute }
        return hour
    }

    /// "khoảng 0,03 USD", "dưới 0,01 USD".
    static func text(_ dollars: Double) -> String {
        if dollars < 0.01 { return "dưới 0,01 USD" }
        let rounded = dollars < 1 ? String(format: "%.2f", dollars) : String(format: "%.1f", dollars)
        return "khoảng " + rounded.replacingOccurrences(of: ".", with: ",") + " USD"
    }
}

extension TranslationModel {
    /// US dollars per million tokens: input, input read from OpenAI's cache, output (reasoning included).
    var price: (input: Double, cached: Double, output: Double) {
        switch self {
        case .sol: return (2, 0.1, 10)
        case .luna: return (0.1, 0.01, 0.5)
        }
    }
}
