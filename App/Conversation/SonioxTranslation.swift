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
    /// Where it ends in the sound sent, in milliseconds (words said, not their translation).
    var endMs: Int?

    /// `<end>`: the speaker finished a sentence. `<fin>`: what was asked to be settled now is.
    var endsSentence: Bool { text == "<end>" || text == "<fin>" }

    static func == (a: SonioxToken, b: SonioxToken) -> Bool {
        a.text == b.text && a.isFinal == b.isFinal && a.isTranslation == b.isTranslation && a.language == b.language
            && a.speaker == b.speaker
    }
}

/// What a Soniox session sends back, and what it is sent.
enum SonioxEvent: Equatable {
    case tokens([SonioxToken])
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
        let tokens = (object["tokens"] as? [[String: Any]] ?? []).compactMap { token -> SonioxToken? in
            guard let text = token["text"] as? String, !text.isEmpty else { return nil }
            let speaker = token["speaker"] as? String ?? (token["speaker"] as? Int).map(String.init) ?? ""
            return SonioxToken(text: text, isFinal: token["is_final"] as? Bool ?? false,
                               isTranslation: token["translation_status"] as? String == "translation",
                               language: token["language"] as? String ?? "", speaker: speaker,
                               endMs: token["end_ms"] as? Int)
        }
        if !tokens.isEmpty { events.append(.tokens(tokens)) }
        if object["finished"] as? Bool == true { events.append(.finished) }
        return events
    }

    /// The session's first message: how the sound comes (24 kHz mono PCM16), the languages expected,
    /// the words to hear right, and what everything heard is translated into. A sentence ends half a
    /// second after the speaker stops (the shortest Soniox allows), so it is read aloud at once.
    /// `speakers`: each word says who said it, so a room of several people is cut sentence by
    /// sentence, speaker by speaker (one sentence of two people translated together read as nonsense).
    static func configMessage(apiKey: String, model: String, target: String, hints: [String],
                              terms: [String], pairs: [(source: String, target: String)], speakers: Bool) -> String {
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
/// own translation comes in pieces and does not say which sentence a piece belongs to: pieces still
/// coming for a sentence already finished go to it (`awaiting`), as a preview of its translation.
struct SonioxTranscript {
    struct Sentence: Equatable {
        var text = ""
        var translation = ""
        /// The language it was said in, empty when not told.
        var language = ""
        /// Who said it (Soniox's speaker number), empty when not told.
        var speaker = ""
        /// Counts the session's sentences, so a finished one can be found again.
        var number = 0

        var isEmpty: Bool { text.isEmpty && translation.isEmpty }

        static func == (a: Sentence, b: Sentence) -> Bool {
            a.text == b.text && a.translation == b.translation && a.language == b.language && a.speaker == b.speaker
        }
    }

    /// What it is translated into.
    let target: String
    /// Cut at a change of speaker or language and after a long speech's full stop (see above).
    var splits = false
    private var settled = Sentence()
    private var guess = Sentence()
    /// The speaker finished the sentence; its translation may still be on its way.
    private(set) var ended = false
    /// `splits`: finished sentences whose translation from Soniox may still be coming, oldest first.
    private(set) var awaiting: [Sentence] = []
    private var awaitingGuess = ""
    /// When each awaiting sentence finished, or last had a piece of translation.
    private var awaitingSince: [Int: Double] = [:]
    private var now: Double = 0
    private var nextNumber = 1
    /// Where the words settled last end in the sound sent (seconds), for how far Soniox lags.
    private(set) var settledEnd: Double?

    init(target: String) {
        self.target = target
        settled.number = nextNumber
    }

    /// The sentence being said, as far as it is known.
    var current: Sentence {
        Sentence(text: settled.text + guess.text, translation: settled.translation + guess.translation,
                 language: settled.language.isEmpty ? guess.language : settled.language,
                 speaker: settled.speaker.isEmpty ? guess.speaker : settled.speaker, number: settled.number)
    }

    /// One message's tokens (`now`: seconds, any clock); returns the sentences it finished.
    mutating func take(_ tokens: [SonioxToken], now: Double = 0) -> [Sentence] {
        var finished: [Sentence] = []
        self.now = now
        // The guesses come again whole in every message.
        guess = Sentence()
        awaitingGuess = ""
        for token in tokens {
            if token.endsSentence {
                ended = true
                continue
            }
            if token.isTranslation {
                addTranslation(token, now: now)
                continue
            }
            // Words said after the end begin the next sentence.
            if ended, let sentence = finish() { finished.append(sentence) }
            if token.isFinal {
                if splits, startsAnother(token), let sentence = finish() { finished.append(sentence) }
                Self.add(token, to: &settled)
                if let end = token.endMs { settledEnd = Double(end) / 1000 }
                if splits, endsLongSpeech(token), let sentence = finish() { finished.append(sentence) }
            } else {
                Self.add(token, to: &guess)
            }
        }
        // An ended sentence is done once nothing of it is guessed and, without `splits`, its
        // translation is in (or it needs none: said in the language it is translated into).
        let translated = splits || !settled.translation.isEmpty || settled.language == target
            || settled.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let guessed = splits ? !guess.text.isEmpty : !guess.isEmpty
        if ended, !guessed, translated, let sentence = finish() { finished.append(sentence) }
        return finished
    }

    /// The finished sentences still waiting for Soniox's translation, with what has come of it.
    var previews: [Sentence] {
        awaiting.enumerated().map { index, sentence in
            var preview = sentence
            if index == 0 { preview.translation += awaitingGuess }
            preview.translation = preview.translation.trimmingCharacters(in: .whitespacesAndNewlines)
            return preview
        }
    }

    /// No more of this sentence's translation is expected: the next pieces are another's.
    private mutating func release(_ number: Int) {
        awaiting.removeAll { $0.number == number }
        awaitingSince[number] = nil
    }

    /// Ends the sentence being said, with what is known of it (nil when nothing is).
    mutating func finish() -> Sentence? {
        var sentence = current
        settled = Sentence()
        guess = Sentence()
        ended = false
        nextNumber += 1
        settled.number = nextNumber
        sentence.text = sentence.text.trimmingCharacters(in: .whitespacesAndNewlines)
        sentence.translation = sentence.translation.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !sentence.isEmpty else { return nil }
        // Its translation may still be coming (unless it is in, or none is needed).
        if splits, !sentence.text.isEmpty, sentence.language != target, !Self.endsSentence(sentence.translation) {
            awaiting.append(sentence)
            awaitingSince[sentence.number] = now
            while awaiting.count > 2 { release(awaiting[0].number) }
        }
        return sentence
    }

    /// A settled word that is someone else's, or another language, while the sentence has words.
    private func startsAnother(_ token: SonioxToken) -> Bool {
        let said = settled.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !said.isEmpty, token.text.contains(where: { $0.isLetter || $0.isNumber }) else { return false }
        if !token.speaker.isEmpty, !settled.speaker.isEmpty, token.speaker != settled.speaker { return true }
        // Another language after a sentence or a clause of some length. A foreign word inside a
        // sentence ("好 OK 的", "gửi em cái quotation nhé") stays in it: no full stop or comma before it.
        guard !token.language.isEmpty, !settled.language.isEmpty, token.language != settled.language,
              let last = said.last, ".?!。？！…,，、;；:：".contains(last) else { return false }
        return TranscriptFilter.syllableCount(said) >= 4
    }

    /// A full stop ending a long speech: it is translated now, the rest goes on as a new sentence.
    private func endsLongSpeech(_ token: SonioxToken) -> Bool {
        Self.endsSentence(token.text) && TranscriptFilter.syllableCount(settled.text) >= 12
    }

    static func endsSentence(_ text: String) -> Bool {
        guard let last = text.trimmingCharacters(in: .whitespacesAndNewlines).last else { return false }
        return ".?!。？！…".contains(last)
    }

    /// A piece of translation: to the oldest finished sentence still waiting for it, while it seems
    /// to be its (no full stop yet, and a piece came for it lately), otherwise to the one being said.
    private mutating func addTranslation(_ token: SonioxToken, now: Double) {
        if splits {
            while let first = awaiting.first {
                let quietFor = now - (awaitingSince[first.number] ?? now)
                if Self.endsSentence(first.translation) || (now > 0 && quietFor > 1.5) {
                    release(first.number)
                    continue
                }
                if token.isFinal {
                    awaiting[0].translation += token.text
                } else {
                    awaitingGuess += token.text
                }
                awaitingSince[first.number] = now
                return
            }
        }
        if token.isFinal { Self.add(token, to: &settled) } else { Self.add(token, to: &guess) }
    }

    private static func add(_ token: SonioxToken, to sentence: inout Sentence) {
        if token.isTranslation {
            sentence.translation += token.text
        } else {
            sentence.text += token.text
            if sentence.language.isEmpty { sentence.language = token.language }
            if sentence.speaker.isEmpty { sentence.speaker = token.speaker }
        }
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

    init(apiKey: String, target: String, hints: [String], glossary: String, speakers: Bool) {
        self.apiKey = apiKey
        self.target = target
        self.hints = hints
        self.speakers = speakers
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
                                               terms: glossary.terms, pairs: glossary.pairs, speakers: speakers)))
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
