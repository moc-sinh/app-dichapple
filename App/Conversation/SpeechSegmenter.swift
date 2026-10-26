import Foundation

/// Cuts the microphone's sound into utterances: from just before the first syllable to the pause
/// that ends the sentence. Each utterance is transcribed and translated on its own, so a meeting
/// is translated sentence by sentence, whoever speaks.
///
/// The idea comes from the voice detection and turn controller of Personal Translator V2: speech is
/// sound well above the room's own noise, and a set pause ends a turn (0.4–1.4 s). The noise
/// level here is the quiet end of the last few seconds, so it follows a noisy room, and a room
/// that is never quiet (air conditioning, a projector) is not taken for one long speech.
struct SpeechSegmenter {
    struct Utterance {
        let samples: [Int16]
        /// Seconds since listening began, at the first sample.
        let start: Double
        var duration: Double { Double(samples.count) / Double(PCM.rate) }
    }

    /// Quiet this long ends an utterance.
    var silence: Double
    /// Longer speech is cut at its quietest moment, so a long talk is translated as it goes.
    var maxLength: Double

    /// Someone is speaking now.
    private(set) var speaking = false
    /// The microphone is not listened to while the phone speaks a translation aloud (its own
    /// voice would be translated again).
    private(set) var muted = false

    static let frame = PCM.rate / 50
    private static let frameSeconds = 0.02
    /// Sound kept from before the first loud frame: the start of the first syllable.
    private static let prerollFrames = 15
    /// Quiet kept after the last word.
    private static let tailFrames = 10
    /// Shorter is a cough, a click or a chair, not speech.
    private static let minimumSpeech = 0.3
    /// Speech is at least this loud, however quiet the room (far voices in a meeting room reach
    /// -45 dBFS or so on an iPhone on the table).
    private static let minimumSpeechDb = -52.0
    private static let historyFrames = 250

    private var history: [Double] = []
    private var historyNext = 0
    private var floorDb = -65.0
    private var framesSinceFloor = 0
    private var confirm = 0
    private var quietFrames = 0
    private var current: [Int16] = []
    private var currentStart = 0
    /// How much of `current` came from before the first loud frame.
    private var lead = 0
    private var preroll: [Int16] = []
    private var pending: [Int16] = []
    private var position = 0

    init(silence: Double = 0.7, maxLength: Double = 15) {
        self.silence = silence
        self.maxLength = maxLength
    }

    /// Takes the next samples (24 kHz); returns the utterances they ended.
    mutating func push(_ samples: [Int16]) -> [Utterance] {
        pending.append(contentsOf: samples)
        var ended: [Utterance] = []
        var offset = 0
        while pending.count - offset >= Self.frame {
            if let utterance = process(pending[offset..<offset + Self.frame]) { ended.append(utterance) }
            offset += Self.frame
        }
        pending.removeFirst(offset)
        return ended
    }

    /// Listening stops: what was being said goes out as it is.
    mutating func flush() -> Utterance? {
        defer {
            speaking = false
            current = []
            preroll = []
            pending = []
            confirm = 0
            quietFrames = 0
        }
        guard speaking else { return nil }
        return finish()
    }

    /// The phone starts speaking: what was being said until now goes out, and nothing is heard
    /// until `unmute`.
    mutating func mute() -> Utterance? {
        muted = true
        let utterance = speaking ? finish() : nil
        speaking = false
        current = []
        preroll = []
        confirm = 0
        return utterance
    }

    mutating func unmute() {
        muted = false
        preroll = []
        confirm = 0
        quietFrames = 0
    }

    private mutating func process(_ frame: ArraySlice<Int16>) -> Utterance? {
        position += frame.count
        if muted { return nil }
        let db = PCM.dbfs(frame)
        remember(db)
        let onset = max(Self.minimumSpeechDb, floorDb + 10)
        let release = max(Self.minimumSpeechDb - 6, floorDb + 5)

        guard speaking else {
            preroll.append(contentsOf: frame)
            if preroll.count > Self.prerollFrames * Self.frame {
                preroll.removeFirst(preroll.count - Self.prerollFrames * Self.frame)
            }
            // Two loud frames in a row: a click is one.
            confirm = db >= onset ? confirm + 1 : 0
            if confirm >= 2 {
                speaking = true
                confirm = 0
                quietFrames = 0
                current = preroll
                currentStart = position - preroll.count
                lead = preroll.count
                preroll = []
            }
            return nil
        }

        current.append(contentsOf: frame)
        quietFrames = db < release ? quietFrames + 1 : 0
        let said = Double(current.count - lead - quietFrames * Self.frame) / Double(PCM.rate)
        if Double(quietFrames) * Self.frameSeconds >= pause(after: said) { return finish() }
        if Double(current.count) >= maxLength * Double(PCM.rate) { return cut() }
        return nil
    }

    /// The pause that ends an utterance `said` seconds long: the one set, for a sentence of up to
    /// three seconds; shorter as it goes on, down to 0.35 s at eight. A room that talks on is cut at
    /// its breaths, and translated as it goes rather than in pieces of the longest length.
    func pause(after said: Double) -> Double {
        let shortest = min(silence, 0.35)
        return silence - (silence - shortest) * max(0, min(1, (said - 3) / 5))
    }

    /// The noise level: the quiet end (10th percentile) of the last five seconds.
    private mutating func remember(_ db: Double) {
        if history.count < Self.historyFrames {
            history.append(db)
        } else {
            history[historyNext] = db
            historyNext = (historyNext + 1) % Self.historyFrames
        }
        framesSinceFloor += 1
        guard framesSinceFloor >= 10 || history.count < 20 else { return }
        framesSinceFloor = 0
        let sorted = history.sorted()
        floorDb = max(-90, min(-25, sorted[sorted.count / 10]))
    }

    private mutating func finish() -> Utterance? {
        speaking = false
        let trailing = max(0, quietFrames - Self.tailFrames) * Self.frame
        let kept = current.dropLast(min(trailing, current.count))
        let speech = Double(current.count - lead - quietFrames * Self.frame) / Double(PCM.rate)
        preroll = Array(current.suffix(Self.prerollFrames * Self.frame))
        quietFrames = 0
        defer { current = [] }
        guard speech >= Self.minimumSpeech else { return nil }
        return Utterance(samples: Array(kept), start: Double(currentStart) / Double(PCM.rate))
    }

    /// Splits a long speech where it is quietest.
    private mutating func cut() -> Utterance {
        let split = Self.split(current)
        let utterance = Utterance(samples: Array(current[..<split]), start: Double(currentStart) / Double(PCM.rate))
        current = Array(current[split...])
        currentStart += split
        lead = 0
        quietFrames = 0
        return utterance
    }

    /// Where long speech is split: after the quietest 20 ms of its last six seconds (a breath
    /// between sentences, usually), never in its first half.
    static func split(_ samples: [Int16]) -> Int {
        let frames = samples.count / frame
        let earliest = max(frames / 2, frames - 300)
        var quietest = frames
        var quietestDb = Double.infinity
        for index in earliest..<frames {
            let start = index * frame
            let db = PCM.dbfs(samples[start..<start + frame])
            if db < quietestDb {
                quietestDb = db
                quietest = index
            }
        }
        return min(samples.count, (quietest + 1) * frame)
    }
}
