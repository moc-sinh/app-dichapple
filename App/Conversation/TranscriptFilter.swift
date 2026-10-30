import Foundation

/// Text a speech recognizer writes without anyone having said it. From silence, a cough or room
/// noise, recognizers trained on online video write channel sign-offs ("Cảm ơn các bạn đã theo dõi",
/// "Thanks for watching", "字幕由…提供"), repeat one syllable many times, or write more words than the
/// sound could hold. Such text is dropped before it is translated. The rules come from the
/// VietNote meeting worker, which met them in real meetings.
enum TranscriptFilter {
    static func isNoise(_ text: String, seconds: Double) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.contains(where: { $0.isLetter || $0.isNumber }) else { return true }
        return isRepetitive(trimmed) || isImplausiblyFast(trimmed, seconds: seconds) || isKnownPhantom(trimmed)
    }

    /// One short piece over and over ("ha ha ha ha ha", "的的的的的的") making up most of the text.
    static func isRepetitive(_ text: String) -> Bool {
        let letters = Array(text.lowercased().filter { $0.isLetter || $0.isNumber })
        guard letters.count >= 12 else { return false }
        var longest = 0
        for unit in 1...12 where unit * 4 <= letters.count {
            var start = 0
            while start + unit * 4 <= letters.count {
                var repeats = 1
                while start + (repeats + 1) * unit <= letters.count,
                      letters[(start + repeats * unit)..<(start + (repeats + 1) * unit)] == letters[start..<(start + unit)] {
                    repeats += 1
                }
                if repeats >= 4 { longest = max(longest, repeats * unit) }
                start += repeats >= 4 ? repeats * unit : 1
            }
        }
        return Double(longest) / Double(letters.count) > 0.6
    }

    /// More syllables than the sound could hold: nobody says eleven a second for long (a fast
    /// Chinese speaker says eight, which eight as the limit took for noise and dropped).
    static func isImplausiblyFast(_ text: String, seconds: Double) -> Bool {
        let syllables = syllableCount(text)
        return syllables >= 8 && Double(syllables) > 11 * max(0.5, seconds - 0.35) + 1
    }

    /// Words, except in Chinese and Japanese, where each character is a syllable.
    static func syllableCount(_ text: String) -> Int {
        var count = 0
        var inWord = false
        for scalar in text.unicodeScalars {
            if isIdeographic(scalar) {
                count += 1
                inWord = false
            } else if scalar.properties.isAlphabetic || ("0"..."9").contains(Character(scalar)) {
                if !inWord { count += 1 }
                inWord = true
            } else {
                inWord = false
            }
        }
        return count
    }

    private static func isIdeographic(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x3040...0x30FF, 0x3400...0x4DBF, 0x4E00...0x9FFF, 0xF900...0xFAFF: return true
        default: return false
        }
    }

    /// Sign-offs of online videos, which recognizers write for silence.
    static func isKnownPhantom(_ text: String) -> Bool {
        let lower = text.lowercased()
        for phrase in ["字幕由", "字幕提供", "请不吝点赞", "订阅我的频道", "明镜与点点", "ご視聴ありがとうございました",
                       "チャンネル登録", "시청해주셔서 감사합니다", "구독과 좋아요", "mbc 뉴스"] where lower.contains(phrase) {
            return true
        }
        let words = folded(text)
        if words.hasPrefix("cam on cac ban da theo doi") || words.hasPrefix("de khong bo lo nhung video")
            || words.contains("ung ho kenh") { return true }
        if (words.contains("subscribe") || words.contains("dang ky") || words.contains("dang ki")) && words.contains("kenh") {
            return true
        }
        return ["thanks for watching", "thank you for watching", "please subscribe", "like and subscribe",
                "subscribe to my channel"].contains { words.contains($0) }
    }

    /// Lower case without marks (đ as d), words of plain letters and digits joined by one space.
    static func folded(_ text: String) -> String {
        let plain = text.lowercased().replacingOccurrences(of: "đ", with: "d")
            .folding(options: .diacriticInsensitive, locale: nil)
        var words: [String] = []
        var word = ""
        for c in plain.unicodeScalars {
            if ("a"..."z").contains(Character(c)) || ("0"..."9").contains(Character(c)) {
                word.unicodeScalars.append(c)
            } else if !word.isEmpty {
                words.append(word)
                word = ""
            }
        }
        if !word.isEmpty { words.append(word) }
        return words.joined(separator: " ")
    }
}

/// The phone's own voice heard again. When a translation is read aloud through the speaker, the
/// microphone hears it: a sentence said while the phone was saying the same words is that echo, not
/// someone speaking (from the VietNote worker's transcript echo check).
///
/// Only while the phone speaks, and a moment after: a partner repeating what the phone said a few
/// seconds before ("好的，下周一确认订单" after it said "我们下周一确认订单") is confirming it, and
/// was lost. Compared by pairs of words (of characters, in Chinese and Japanese), not single ones: a
/// colleague's sentence of common words ("của chúng tôi", "我们的") was taken for its echo.
struct EchoGuard {
    /// The phone's voice is heard while it plays and a moment after (the room's echo, and how far a
    /// recognizer's times are off).
    static let tail = 1.5
    private static let kept = 60.0
    private var spoken: [(from: Double, to: Double, words: Set<String>, pairs: Set<String>)] = []

    /// The phone begins saying `text` at `at` (seconds, the clock heard sentences are timed by), until
    /// `stopped`.
    mutating func spoke(_ text: String, at: Double) {
        let words = Self.words(text)
        guard !words.isEmpty else { return }
        spoken.append((at, .infinity, Set(words), Set(Self.pairs(words))))
        spoken.removeAll { at - $0.to > Self.kept }
    }

    /// The phone stopped speaking.
    mutating func stopped(at: Double) {
        for index in spoken.indices where spoken[index].to == .infinity { spoken[index].to = at }
    }

    /// A sentence said from `from` to `to` (seconds): the phone's voice heard back, when said while the
    /// phone said the same words.
    func isEcho(_ text: String, from: Double, to: Double) -> Bool {
        let heard = Self.words(text)
        let during = spoken.filter { from <= $0.to + Self.tail && to >= $0.from }
        guard !heard.isEmpty, !during.isEmpty else { return false }
        // A word or two: all of them said by the phone.
        if heard.count < 3 {
            let said = during.reduce(into: Set<String>()) { $0.formUnion($1.words) }
            return heard.allSatisfy(said.contains)
        }
        let said = during.reduce(into: Set<String>()) { $0.formUnion($1.pairs) }
        let pairs = Self.pairs(heard)
        let matched = pairs.filter(said.contains).count
        // Half of a long sentence (what the recognizer heard of the phone's voice has a mistake here
        // and there), all of a short one: "这个呢" after the phone said "这个价格" is someone asking.
        return matched * 2 >= pairs.count && matched >= min(pairs.count, 3)
    }

    static func pairs(_ words: [String]) -> [String] {
        zip(words, words.dropFirst()).map { $0 + " " + $1 }
    }

    /// Lower-case words; in Chinese and Japanese, each character.
    static func words(_ text: String) -> [String] {
        var words: [String] = []
        var word = ""
        for scalar in text.lowercased().unicodeScalars {
            let ideographic: Bool
            switch scalar.value {
            case 0x3040...0x30FF, 0x3400...0x4DBF, 0x4E00...0x9FFF, 0xF900...0xFAFF: ideographic = true
            default: ideographic = false
            }
            if ideographic {
                if !word.isEmpty { words.append(word); word = "" }
                words.append(String(scalar))
            } else if scalar.properties.isAlphabetic || ("0"..."9").contains(Character(scalar)) {
                word.unicodeScalars.append(scalar)
            } else if !word.isEmpty {
                words.append(word)
                word = ""
            }
        }
        if !word.isEmpty { words.append(word) }
        return words
    }
}
