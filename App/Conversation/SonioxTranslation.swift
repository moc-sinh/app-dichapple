import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// One piece of what Soniox heard: a word (or part of one) as it was said, or of its translation.
struct SonioxToken: Equatable {
    var text: String
    /// Settled: it will not change. Otherwise Soniox's guess so far, sent again (perhaps changed)
    /// with its next message.
    var isFinal: Bool
    /// Part of the translation, not of what was said.
    var isTranslation: Bool
    /// The language it is in ("vi", "zh"…), empty when not told.
    var language: String
    /// Who said it (Soniox's speaker number, "1", "2"…), empty when not told.
    var speaker = ""
    /// Where it begins and ends in the sound sent, in milliseconds (words said, not their translation).
    var startMs: Int?
    var endMs: Int?

    /// `<end>`: the speaker finished a sentence. `<fin>`: what was asked to be settled now is.
    var endsSentence: Bool { text == "<end>" || text == "<fin>" }
    /// `<fin>`: the turn was ended on purpose (the button let go, listening stopped).
    var isFin: Bool { text == "<fin>" }

    static func == (a: SonioxToken, b: SonioxToken) -> Bool {
        a.text == b.text && a.isFinal == b.isFinal && a.isTranslation == b.isTranslation && a.language == b.language
            && a.speaker == b.speaker
    }
}

/// What a Soniox session sends back, and what it is sent.
enum SonioxEvent: Equatable {
    case tokens([SonioxToken])
    /// How much of the sound sent Soniox has turned into settled words, and into words at all (settled
    /// or guessed), in milliseconds: told with each message, before its tokens.
    case processed(finalMs: Int, totalMs: Int)
    /// Everything sent has been answered; the session ends.
    case finished
    /// 401 a wrong key, 402 or 429 no credit left, 400 a request it does not take; -1 the network.
    case failed(code: Int, message: String)
    case closed

    static func parse(_ text: String) -> [SonioxEvent] {
        guard let data = text.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return [] }
        if object["error_code"] != nil || object["error_message"] != nil {
            let code = object["error_code"] as? Int ?? Int(object["error_code"] as? String ?? "") ?? 0
            return [.failed(code: code, message: object["error_message"] as? String ?? "")]
        }
        var events: [SonioxEvent] = []
        if let final = (object["final_audio_proc_ms"] as? NSNumber)?.intValue,
           let total = (object["total_audio_proc_ms"] as? NSNumber)?.intValue {
            events.append(.processed(finalMs: final, totalMs: total))
        }
        let list = object["tokens"] as? [[String: Any]]
        let tokens = (list ?? []).compactMap { token -> SonioxToken? in
            guard let text = token["text"] as? String, !text.isEmpty else { return nil }
            let speaker = token["speaker"] as? String ?? (token["speaker"] as? Int).map(String.init) ?? ""
            return SonioxToken(text: text, isFinal: token["is_final"] as? Bool ?? false,
                               isTranslation: token["translation_status"] as? String == "translation",
                               language: token["language"] as? String ?? "", speaker: speaker,
                               startMs: token["start_ms"] as? Int, endMs: token["end_ms"] as? Int)
        }
        // An empty list too: Soniox's guesses, sent again whole in every message, are gone (taken back).
        if list != nil, !(tokens.isEmpty && object["finished"] as? Bool == true) { events.append(.tokens(tokens)) }
        if object["finished"] as? Bool == true { events.append(.finished) }
        return events
    }

    /// The session's first message: how the sound comes (24 kHz mono PCM16), the languages expected,
    /// the words to hear right, and what everything heard is translated into. A sentence ends half a
    /// second after the speaker stops (the shortest Soniox allows), so it is read aloud at once.
    /// `speakers`: each word says who said it, so a room of several people is cut sentence by
    /// sentence, speaker by speaker (one sentence of two people translated together read as nonsense).
    /// `about`: what the talk is (its setting, topic, who the user is), for Soniox's `general` context,
    /// which guides both the words it hears and its translation.
    static func configMessage(apiKey: String, model: String, target: String, hints: [String],
                              terms: [String], pairs: [(source: String, target: String)], speakers: Bool,
                              about: [(key: String, value: String)] = []) -> String {
        var config: [String: Any] = [
            "api_key": apiKey,
            "model": model,
            "audio_format": "pcm_s16le",
            "sample_rate": PCM.rate,
            "num_channels": 1,
            "language_hints": hints,
            "enable_language_identification": true,
            "enable_endpoint_detection": true,
            "max_endpoint_delay_ms": 500,
            "translation": ["type": "one_way", "target_language": target],
        ]
        if speakers { config["enable_speaker_diarization"] = true }
        var context: [String: Any] = [:]
        if !about.isEmpty { context["general"] = about.map { ["key": $0.key, "value": $0.value] } }
        if !terms.isEmpty { context["terms"] = terms }
        if !pairs.isEmpty { context["translation_terms"] = pairs.map { ["source": $0.source, "target": $0.target] } }
        if !context.isEmpty { config["context"] = context }
        let data = (try? JSONSerialization.data(withJSONObject: config, options: [.sortedKeys])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    static let keepaliveMessage = #"{"type":"keepalive"}"#
    /// Settles what is still guessed now, without waiting for more sound.
    static let finalizeMessage = #"{"type":"finalize"}"#

    /// Soniox's code for a language (it writes Chinese one way).
    static func code(for language: Language) -> String {
        language.code == "zh-Hant" ? "zh" : language.code
    }

    /// The glossary's lines ("báo giá = 报价"): every term is a word to hear right, and each pair with
    /// one Vietnamese side says how it is translated, from Vietnamese (`intoVietnamese` false) or into it.
    static func glossary(_ text: String, intoVietnamese: Bool) -> (terms: [String], pairs: [(source: String, target: String)]) {
        var terms: [String] = []
        var pairs: [(source: String, target: String)] = []
        for line in text.split(whereSeparator: \.isNewline) {
            let sides = line.split(separator: "=").map { $0.trimmingCharacters(in: .whitespaces) }
            for side in sides where !side.isEmpty && side.count <= 40 && terms.count < 60 { terms.append(side) }
            guard sides.count == 2, !sides[0].isEmpty, !sides[1].isEmpty else { continue }
            let marked = sides.map(LanguageDetector.hasVietnameseMarks)
            guard marked[0] != marked[1] else { continue }
            let (vietnamese, other) = marked[0] ? (sides[0], sides[1]) : (sides[1], sides[0])
            pairs.append(intoVietnamese ? (other, vietnamese) : (vietnamese, other))
        }
        return (terms, pairs)
    }
}

/// What one Soniox session has heard, cut into sentences: the one being said (its settled words,
/// then Soniox's guess at the rest, shown as they come) and those finished.
///
/// With `splits` (the app makes each sentence's translation itself, with GPT), a sentence also ends
/// where another person starts speaking, where the language changes, and after a long speech's full
/// stop, without waiting for Soniox's end of sentence: in a room where people talk on without
/// pausing, Soniox's sentences ran into one another, several speakers and languages in one. Soniox's
/// end of sentence ends one only after a full stop (or a question mark…): one in the middle of a
/// thought ("如果你们订五百台，… 我们可以给百分之五") is a pause, and what follows is the same
/// sentence; the model ends it after a quiet moment. A word or two of another speaker in the middle
/// of a sentence (a word Soniox gave to the wrong person, or "对" said over the speaker) cuts it only
/// once that speaker has said three syllables; when the first speaker goes on, they stay in it.
///
/// Soniox sends what was said and its translation as one stream, each piece of translation right
/// after the words it translates (its docs; its own SDK prints a piece under the words before it), and
/// settles the stream from its start. So a piece of translation belongs to the sentence of the words
/// just before it in the stream: a sentence cut at its full stop still gets the translation of its
/// last words (`awaiting`, shown as a preview), and the next sentence's words settling mean that
/// translation is all in. It used to go by time, and the first piece of a speaker who had just
/// begun went to the sentence of the one before for a few seconds.
///
/// A sentence's language is the one most of its syllables were said in, not its first word's: "Dạ,
/// 王总，我们下周一确认订单" is Chinese, and "OK，那我们下周一确认" too.
struct SonioxTranscript {
    struct Sentence: Equatable {
        var text = ""
        var translation = ""
        /// The language most of it was said in, empty when not told.
        var language = ""
        /// Who said it (Soniox's speaker number), empty when not told.
        var speaker = ""
        /// Counts the session's sentences, so a finished one can be found again.
        var number = 0
        /// Where its words begin and end in the session's sound (milliseconds), when told.
        var startMs: Int?
        var endMs: Int?
        /// Where the words its translation has come for so far end (each piece of translation follows
        /// the words it translates): the words after it are still untranslated.
        var coveredMs: Int?
        /// Syllables said in each language.
        var syllables: [String: Int] = [:]

        var isEmpty: Bool { text.isEmpty && translation.isEmpty }

        static func == (a: Sentence, b: Sentence) -> Bool {
            a.text == b.text && a.translation == b.translation && a.language == b.language && a.speaker == b.speaker
        }
    }

    /// What it is translated into.
    let target: String
    /// Cut by speaker, language and sentence as it is said (see above).
    var splits = false
    private var settled = Sentence()
    private var guess = Sentence()
    /// `splits`: settled words of another speaker in the middle of a sentence, not yet three
    /// syllables, and the settled translation that came after them (theirs, wherever they go).
    private var switching: [SonioxToken] = []
    private var switchingTranslation = ""
    private var switchingCovered: Int?
    /// The speaker finished the sentence (without `splits`), or paused in the middle of one (with
    /// `splits`: no full stop yet); its translation may still be on its way.
    private(set) var ended = false
    /// `splits`: finished sentences whose translation may still be coming (the one whose words
    /// settled last), and those whose translation came in full with the last message.
    private var awaiting: [Sentence] = []
    private var released: [Sentence] = []
    /// Soniox's guess at the translation of a finished sentence, and which one.
    private var awaitingGuess = ""
    private var awaitingGuessNumber = 0
    private var awaitingGuessCovered: Int?
    /// The sentence of the words settled last (0: none yet): the next settled piece of translation is its.
    private var owner = 0
    private var nextNumber = 1
    /// Where the words settled last end in the sound sent (seconds), for how far Soniox lags.
    private(set) var settledEnd: Double?

    init(target: String) {
        self.target = target
        settled.number = nextNumber
    }

    /// The sentence being said, as far as it is known.
    var current: Sentence {
        var sentence = settled
        for word in switching { Self.add(word, to: &sentence) }
        sentence.translation += switchingTranslation
        Self.cover(&sentence, to: switchingCovered)
        sentence.text += guess.text
        sentence.translation += guess.translation
        Self.cover(&sentence, to: guess.coveredMs)
        for (language, count) in guess.syllables { sentence.syllables[language, default: 0] += count }
        sentence.language = Self.majority(sentence.syllables, first: sentence.language.isEmpty ? guess.language : sentence.language)
        if sentence.speaker.isEmpty { sentence.speaker = guess.speaker }
        if sentence.startMs == nil { sentence.startMs = guess.startMs }
        if let end = guess.endMs { sentence.endMs = end }
        return sentence
    }

    /// One message's tokens; returns the sentences it finished.
    mutating func take(_ tokens: [SonioxToken]) -> [Sentence] {
        var finished: [Sentence] = []
        // The guesses come again whole in every message.
        guess = Sentence()
        awaitingGuess = ""
        awaitingGuessCovered = nil
        released = []
        /// A guessed word came in this message: guessed translation after it is of the words being said.
        var guessing = false
        /// Where the last word of this message ends: a piece of translation after it has come for it.
        var saidEnd: Int?
        for token in tokens {
            if token.endsSentence {
                if splits { endSpoken(fin: token.isFin, into: &finished) } else { ended = true }
                continue
            }
            if token.isTranslation {
                addTranslation(token, guessing: guessing, after: saidEnd)
                continue
            }
            if let end = token.endMs { saidEnd = end }
            if token.isFinal, let end = token.endMs { settledEnd = Double(end) / 1000 }
            if splits {
                if token.isFinal {
                    settleSplit(token, into: &finished)
                } else {
                    Self.add(token, to: &guess)
                    guessing = true
                }
                continue
            }
            // Words said after the end begin the next sentence.
            if ended, let sentence = finish() { finished.append(sentence) }
            if token.isFinal {
                settle(token)
            } else {
                Self.add(token, to: &guess)
                guessing = true
            }
        }
        // Without `splits`, an ended sentence is done once nothing of it is guessed and its
        // translation is in (or it needs none: said in the language it is translated into).
        if !splits {
            let translated = !settled.translation.isEmpty || settled.language == target
                || settled.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            if ended, guess.isEmpty, translated, let sentence = finish() { finished.append(sentence) }
        }
        return finished
    }

    /// The finished sentences still getting Soniox's translation, and those whose last piece came
    /// with the last message, with what has come of it.
    var previews: [Sentence] {
        (released + awaiting).map { sentence in
            var preview = sentence
            if sentence.number == awaitingGuessNumber {
                preview.translation += awaitingGuess
                Self.cover(&preview, to: awaitingGuessCovered)
            }
            preview.translation = preview.translation.trimmingCharacters(in: .whitespacesAndNewlines)
            return preview
        }
    }

    /// Something shown is Soniox's guess (words or translation): a message without it takes it back.
    var showsGuess: Bool { !guess.isEmpty || !awaitingGuess.isEmpty }

    /// Words of the sentence being said are still Soniox's guess: its speaker goes on (or someone else
    /// begins). A pause is not ended until they settle: ended with them, the words came again as the
    /// next sentence once settled.
    var hasGuess: Bool { !guess.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// The user began to speak (Tôi nói): the room's speaker has stopped, as at Soniox's end of a
    /// sentence, which Soniox does not send while the user talks on (it hears their turn too). Not while
    /// words of it are still guessed: they settle first.
    mutating func endHere() -> [Sentence] {
        guard splits, !hasGuess else { return [] }
        var finished: [Sentence] = []
        endSpoken(fin: false, into: &finished)
        return finished
    }

    /// Ends the sentence being said, with what is known of it (nil when nothing is).
    mutating func finish() -> Sentence? {
        // A word or two of another speaker at its very end stay in it.
        keepSwitching()
        var sentence = current
        let settledTranslation = settled.translation
        let settledCovered = settled.coveredMs
        settled = Sentence()
        guess = Sentence()
        ended = false
        nextNumber += 1
        settled.number = nextNumber
        sentence.text = sentence.text.trimmingCharacters(in: .whitespacesAndNewlines)
        sentence.translation = sentence.translation.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !sentence.isEmpty else { return nil }
        // The translation of its last words may still come (unless none is needed). Only what Soniox
        // settled is kept: its guess at the rest comes again in its next message, settled or guessed.
        if splits, owner == sentence.number, !sentence.text.isEmpty, sentence.language != target {
            var kept = sentence
            kept.translation = settledTranslation.trimmingCharacters(in: .whitespacesAndNewlines)
            kept.coveredMs = settledCovered
            awaiting.append(kept)
            while awaiting.count > 2 { awaiting.removeFirst() }
        }
        return sentence
    }

    /// Ends the sentence with its settled words only, Soniox's guesses at the rest left out: the
    /// session is gone, and the sound of those words is sent again to the next one.
    mutating func finishSettled() -> Sentence? {
        guess = Sentence()
        return finish()
    }

    /// A settled word, with `splits`: it may end the sentence before it (another speaker, another
    /// language, a long speech's full stop), or wait to tell a word or two of another speaker.
    private mutating func settleSplit(_ token: SonioxToken, into finished: inout [Sentence]) {
        let wordless = !token.text.contains(where: { $0.isLetter || $0.isNumber })
        if !switching.isEmpty {
            if token.speaker == switching[0].speaker || wordless { return hold(token, into: &finished) }
            if token.speaker.isEmpty || token.speaker == settled.speaker {
                // The speaker goes on: the few words were theirs, or said over them; they stay in it.
                keepSwitching()
            } else {
                confirmSwitch(into: &finished)
            }
        }
        let said = settled.text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !said.isEmpty, !wordless {
            if !token.speaker.isEmpty, !settled.speaker.isEmpty, token.speaker != settled.speaker {
                // Someone else, after a sentence or a clause (or after Soniox's end of one): now.
                // In the middle of one: once they have said a few syllables.
                guard ended || said.last.map({ Self.clauseEnds.contains($0) }) == true else {
                    return hold(token, into: &finished)
                }
                if let sentence = finish() { finished.append(sentence) }
            } else if startsAnotherLanguage(token), let sentence = finish() {
                finished.append(sentence)
            }
        }
        ended = false
        settle(token)
        if endsLongSpeech(token), let sentence = finish() { finished.append(sentence) }
    }

    /// A settled word of another speaker in the middle of a sentence: held until they have said a
    /// few syllables, then theirs.
    private mutating func hold(_ token: SonioxToken, into finished: inout [Sentence]) {
        switching.append(token)
        let said = switching.reduce(0) { $0 + TranscriptFilter.syllableCount($1.text) }
        guard said >= Self.switchSyllables else { return }
        confirmSwitch(into: &finished)
        if endsLongSpeech(token), let sentence = finish() { finished.append(sentence) }
    }

    /// The words held were the speaker's own, or said over them: they stay in the sentence.
    private mutating func keepSwitching() {
        for word in switching { settle(word) }
        settled.translation += switchingTranslation
        Self.cover(&settled, to: switchingCovered)
        switching = []
        switchingTranslation = ""
        switchingCovered = nil
    }

    /// The few words of another speaker were theirs: the sentence before ends without them, and they
    /// begin the next, with the translation that came after them.
    private mutating func confirmSwitch(into finished: inout [Sentence]) {
        let words = switching
        let translation = switchingTranslation
        let covered = switchingCovered
        switching = []
        switchingTranslation = ""
        switchingCovered = nil
        guard !words.isEmpty else { return }
        if let sentence = finish() { finished.append(sentence) }
        for word in words { settle(word) }
        settled.translation += translation
        Self.cover(&settled, to: covered)
    }

    /// Soniox's end of sentence, with `splits`: after a full stop, or a turn ended on purpose
    /// (`<fin>`), the sentence is done; in the middle of a thought, it is a pause.
    private mutating func endSpoken(fin: Bool, into finished: inout [Sentence]) {
        if !switching.isEmpty { confirmSwitch(into: &finished) }
        let said = settled.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !said.isEmpty else { return }
        if fin || Self.endsSentence(said) {
            if let sentence = finish() { finished.append(sentence) }
        } else {
            ended = true
        }
    }

    private mutating func settle(_ token: SonioxToken) {
        Self.add(token, to: &settled)
        // Settled words of this sentence: what came before them, translations too, is settled.
        owner = settled.number
        released += awaiting
        awaiting = []
    }

    /// Another language after a sentence or a clause of some length. A foreign word inside a
    /// sentence ("好 OK 的", "gửi em cái quotation nhé") stays in it: no full stop or comma before it.
    private func startsAnotherLanguage(_ token: SonioxToken) -> Bool {
        let said = settled.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.language.isEmpty, !settled.language.isEmpty, token.language != settled.language,
              let last = said.last, Self.clauseEnds.contains(last) else { return false }
        return TranscriptFilter.syllableCount(said) >= 4
    }

    /// A full stop ending a long speech, or a comma in a very long one (someone talking on for ten
    /// seconds and more without a full stop): it is translated now, the rest goes on as a new sentence.
    private func endsLongSpeech(_ token: SonioxToken) -> Bool {
        let syllables = TranscriptFilter.syllableCount(settled.text)
        if Self.endsSentence(token.text) { return syllables >= 12 }
        guard let last = token.text.trimmingCharacters(in: .whitespacesAndNewlines).last else { return false }
        return ",，、;；".contains(last) && syllables >= 40
    }

    static func endsSentence(_ text: String) -> Bool {
        guard let last = text.trimmingCharacters(in: .whitespacesAndNewlines).last else { return false }
        return ".?!。？！…".contains(last)
    }

    private static let clauseEnds = ".?!。？！…,，、;；:："
    /// Syllables another speaker says in the middle of a sentence before it is theirs.
    private static let switchSyllables = 3

    /// A piece of translation: of the words just before it. A settled piece follows the words settled
    /// last (`owner`), which may be a sentence already cut; a guessed one, the words guessed before it
    /// in this message, or else the settled ones. Without `splits`, the sentence being said.
    /// `saidEnd`: where the last word before it in this message ends (none: the words it translates
    /// came in an earlier message, and end where its sentence's settled words do).
    private mutating func addTranslation(_ token: SonioxToken, guessing: Bool, after saidEnd: Int?) {
        // After words held (another speaker's few, the last settled): theirs, wherever they go.
        if !switching.isEmpty {
            if token.isFinal {
                switchingTranslation += token.text
                switchingCovered = saidEnd ?? switching.last?.endMs
            } else {
                Self.add(token, to: &guess)
                Self.cover(&guess, to: saidEnd ?? switching.last?.endMs)
            }
            return
        }
        let number = !splits || guessing || owner == 0 ? settled.number : owner
        if number != settled.number {
            // A cut sentence's: the one waiting for it (one let go of is gone, and the piece with it).
            guard let index = awaiting.firstIndex(where: { $0.number == number }) else { return }
            let covered = saidEnd ?? awaiting[index].endMs
            if token.isFinal {
                awaiting[index].translation += token.text
                Self.cover(&awaiting[index], to: covered)
            } else {
                awaitingGuess += token.text
                awaitingGuessNumber = number
                awaitingGuessCovered = covered
            }
            return
        }
        if token.isFinal {
            Self.add(token, to: &settled)
            Self.cover(&settled, to: saidEnd ?? settled.endMs)
        } else {
            Self.add(token, to: &guess)
            Self.cover(&guess, to: saidEnd ?? guess.endMs ?? settled.endMs)
        }
    }

    /// The translation has come for the words up to `end`.
    private static func cover(_ sentence: inout Sentence, to end: Int?) {
        guard let end else { return }
        sentence.coveredMs = max(sentence.coveredMs ?? end, end)
    }

    private static func add(_ token: SonioxToken, to sentence: inout Sentence) {
        if token.isTranslation {
            sentence.translation += token.text
            return
        }
        sentence.text += token.text
        let syllables = TranscriptFilter.syllableCount(token.text)
        if syllables > 0, !token.language.isEmpty { sentence.syllables[token.language, default: 0] += syllables }
        sentence.language = majority(sentence.syllables, first: sentence.language.isEmpty ? token.language : sentence.language)
        if sentence.speaker.isEmpty { sentence.speaker = token.speaker }
        if sentence.startMs == nil { sentence.startMs = token.startMs }
        if let end = token.endMs { sentence.endMs = end }
    }

    /// The language most syllables were said in; on a tie, the one it had.
    static func majority(_ syllables: [String: Int], first: String) -> String {
        var best = first
        var most = syllables[first] ?? 0
        for (language, count) in syllables.sorted(by: { $0.key < $1.key }) where count > most {
            best = language
            most = count
        }
        return best
    }
}

/// The user's own voice in what the room's Soniox session hears. The room is listened to during the
/// user's turns (Tôi nói) too, so a partner who answers meanwhile, or finishes a sentence as the
/// button goes down, is not lost; the user's words, which their own session translates, are taken
/// out. In a turn only a voice known to be a partner's passes: one heard saying a few syllables in
/// another language than Vietnamese outside the user's turns, more than it said in Vietnamese in
/// them (a partner's "vâng", or the user's first word given a partner's number, no longer made that
/// partner the user for the rest of the meeting).
struct MyVoiceFilter {
    /// The user's turns, in seconds of the room's sound (the last one open while it lasts).
    private(set) var turns: [(from: Double, to: Double)] = []
    /// Syllables each of the room's voices (Soniox's speaker numbers) said in Vietnamese in the user's
    /// turns, and in another language outside them.
    private var mine: [String: Int] = [:]
    private var theirs: [String: Int] = [:]
    /// The last settled word was taken out: the settled translation after it goes too.
    private var droppingSettled = false
    /// Sound around a turn that is still the user's: the button pressed a moment after they began,
    /// and how far Soniox's times are off.
    static let margin = 0.3
    /// Syllables a voice says in another language outside the user's turns before it is a partner's.
    static let known = 3

    mutating func began(at: Double) {
        guard turns.last?.to != .infinity else { return }
        turns.append((at, .infinity))
        if turns.count > 20 { turns.removeFirst() }
    }

    mutating func ended(at: Double) {
        for index in turns.indices where turns[index].to == .infinity { turns[index].to = at }
    }

    /// The room's session was opened again: Soniox numbers its speakers anew.
    mutating func newSession() {
        mine = [:]
        theirs = [:]
        droppingSettled = false
    }

    /// The room's tokens without the user's words said in their turns, and the translation of those
    /// words (`origin`: where this session's sound begins in the turns' seconds).
    mutating func filter(_ tokens: [SonioxToken], origin: Double) -> [SonioxToken] {
        guard !turns.isEmpty else {
            for token in tokens { learn(token) }
            return tokens
        }
        var kept: [SonioxToken] = []
        var dropping = droppingSettled
        for token in tokens {
            if token.endsSentence {
                kept.append(token)
            } else if token.isTranslation {
                if !dropping { kept.append(token) }
            } else {
                dropping = isMine(token, origin: origin)
                if token.isFinal { droppingSettled = dropping }
                if !dropping { kept.append(token) }
            }
        }
        return kept
    }

    private mutating func isMine(_ token: SonioxToken, origin: Double) -> Bool {
        guard let start = token.startMs else {
            learn(token)
            return false
        }
        let at = origin + Double(start) / 1000
        guard turns.contains(where: { at >= $0.from - Self.margin && at <= $0.to + Self.margin }) else {
            learn(token)
            return false
        }
        if token.language == Language.vietnamese.code {
            if token.isFinal, !token.speaker.isEmpty {
                mine[token.speaker, default: 0] += TranscriptFilter.syllableCount(token.text)
            }
            return true
        }
        let said = theirs[token.speaker] ?? 0
        return token.speaker.isEmpty || said < Self.known || said <= mine[token.speaker] ?? 0
    }

    /// A word outside the user's turns: a voice heard in another language is a partner's.
    private mutating func learn(_ token: SonioxToken) {
        guard token.isFinal, !token.isTranslation, !token.speaker.isEmpty, !token.language.isEmpty,
              token.language != Language.vietnamese.code else { return }
        theirs[token.speaker, default: 0] += TranscriptFilter.syllableCount(token.text)
    }
}

/// One Soniox session: sound goes in, and what was said comes back as text with its translation
/// into one language, word by word while the speaker is still talking. Two-way talk needs one per
/// direction. Used on the main queue only: what the socket says is handled there too.
final class SonioxSession {
    /// Newest first: the next is tried when Soniox no longer knows the one before.
    static let models = ["stt-rt-v5", "stt-rt-v4"]

    /// The language it translates into (Soniox's code).
    let target: String
    /// Called on the main queue.
    var onEvent: ((SonioxEvent) -> Void)?
    private(set) var isOpen = false
    private let apiKey: String
    private let hints: [String]
    private let glossary: (terms: [String], pairs: [(source: String, target: String)])
    /// Tells who said each word (the room's session; the user's turn is one person).
    private let speakers: Bool
    private let about: [(key: String, value: String)]
    private var model = 0
    private var task: URLSessionWebSocketTask?
    private var closing = false
    private var answered = false
    private var lastSent = Date.distantPast
    private var keepalive: Timer?

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        return URLSession(configuration: config)
    }()

    init(apiKey: String, target: String, hints: [String], glossary: String, speakers: Bool,
         about: [(key: String, value: String)] = []) {
        self.apiKey = apiKey
        self.target = target
        self.hints = hints
        self.speakers = speakers
        self.about = about
        self.glossary = SonioxEvent.glossary(glossary, intoVietnamese: target == Language.vietnamese.code)
    }

    func open() {
        guard task == nil, let url = URL(string: "wss://stt-rt.soniox.com/transcribe-websocket") else { return }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        let task = Self.session.webSocketTask(with: request)
        self.task = task
        closing = false
        answered = false
        isOpen = true
        task.resume()
        send(.string(SonioxEvent.configMessage(apiKey: apiKey, model: Self.models[model], target: target, hints: hints,
                                               terms: glossary.terms, pairs: glossary.pairs, speakers: speakers,
                                               about: about)))
        receive(task)
        // Soniox ends a session that hears nothing for a while: a session not spoken to is kept open.
        keepalive?.invalidate()
        // In the common modes, so it also runs while the user scrolls the transcript.
        let timer = Timer(timeInterval: 2, repeats: true) { [weak self] _ in
            guard let self, Date().timeIntervalSince(self.lastSent) >= 4 else { return }
            self.send(.string(SonioxEvent.keepaliveMessage))
        }
        RunLoop.main.add(timer, forMode: .common)
        keepalive = timer
    }

    deinit {
        keepalive?.invalidate()
    }

    /// 24 kHz mono PCM16, about 100 ms at a time.
    func send(audio pcm: Data) {
        send(.data(pcm))
    }

    /// The speaker stopped: what is still guessed is settled and translated now.
    func finalize() {
        send(.string(SonioxEvent.finalizeMessage))
    }

    /// Asks for what is still on its way (an empty message), then ends the session.
    func close() {
        keepalive?.invalidate()
        keepalive = nil
        guard let task else { return }
        closing = true
        isOpen = false
        task.send(.string("")) { _ in }
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak task] in task?.cancel(with: .normalClosure, reason: nil) }
        self.task = nil
    }

    private func send(_ message: URLSessionWebSocketTask.Message) {
        guard let task else { return }
        lastSent = Date()
        task.send(message) { [weak self] error in
            guard let error else { return }
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.closing else { return }
                self.deliver(.failed(code: -1, message: error.localizedDescription))
            }
        }
    }

    private func receive(_ task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            DispatchQueue.main.async { [weak self] in self?.received(result, from: task) }
        }
    }

    private func received(_ result: Result<URLSessionWebSocketTask.Message, Error>, from task: URLSessionWebSocketTask) {
        guard task === self.task || closing else { return }
        switch result {
        case .success(let message):
            let text: String?
            switch message {
            case .string(let string): text = string
            case .data(let data): text = String(data: data, encoding: .utf8)
            @unknown default: text = nil
            }
            for event in SonioxEvent.parse(text ?? "") {
                if case .failed(let code, let message) = event, retriesNewerModel(code: code, message: message) { return }
                if case .tokens = event { answered = true }
                deliver(event)
            }
            receive(task)
        case .failure(let error):
            let wasClosing = closing
            if task === self.task { self.task = nil }
            isOpen = false
            keepalive?.invalidate()
            keepalive = nil
            if !wasClosing {
                // Refused at the start: a wrong key, or no credit.
                let status = (task.response as? HTTPURLResponse)?.statusCode ?? 0
                if [401, 402, 403, 429].contains(status) {
                    deliver(.failed(code: status == 403 ? 401 : status, message: ""))
                } else {
                    deliver(.failed(code: -1, message: error.localizedDescription))
                }
            }
            deliver(.closed)
        }
    }

    /// A model Soniox no longer offers: the session starts again with the next one, unseen.
    private func retriesNewerModel(code: Int, message: String) -> Bool {
        guard code == 400, !answered, message.localizedCaseInsensitiveContains("model"),
              model + 1 < Self.models.count, let task else { return false }
        model += 1
        self.task = nil
        task.cancel(with: .normalClosure, reason: nil)
        open()
        return true
    }

    private func deliver(_ event: SonioxEvent) {
        DispatchQueue.main.async { [weak self] in self?.onEvent?(event) }
    }
}
