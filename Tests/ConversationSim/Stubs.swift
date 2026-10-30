// Linux stand-ins for what the Hội thoại model needs from UIKit, AVFoundation, Soniox and OpenAI
// (Tests/ConversationSim/run.sh).
import Foundation
final class UIApplication { static let shared = UIApplication(); var isIdleTimerDisabled = false }

/// AudioIO without AVFoundation: plays in "real time" (done after the sound's length at the rate).
final class AudioIO: NSObject {
    static var current: AudioIO?
    let queue = DispatchQueue(label: "stub.audio")
    var onSamples: (([Int16]) -> Void)?
    var onInterruption: ((Bool) -> Void)?
    private(set) var running = false
    var rate: Float = 1
    var rates: [Float] = []
    var playedSeconds = 0.0
    private var pending: [UUID: (done: () -> Void, seconds: Double)] = [:]
    private var queuedUntil = Date()
    override init() { super.init(); AudioIO.current = self }
    static func requestPermission() async -> Bool { true }
    var echo: [Bool] = []
    func start(echoCancelling: Bool) throws { running = true; echo.append(echoCancelling) }
    func setEchoCancelling(_ on: Bool) throws { if echo.last != on { echo.append(on) } }
    func stop() { running = false; stopPlayback() }
    var backlog: Double { pending.values.reduce(0) { $0 + $1.seconds } }
    func setRate(_ rate: Float) { self.rate = rate; rates.append(rate) }
    func play(_ pcm: Data, done: @escaping () -> Void) {
        let seconds = PCM.seconds(pcm)
        playedSeconds += seconds
        let id = UUID()
        pending[id] = (done, seconds)
        let start = max(Date(), queuedUntil)
        queuedUntil = start.addingTimeInterval(seconds / Double(rate) / Sim.speed)
        DispatchQueue.main.asyncAfter(deadline: .now() + queuedUntil.timeIntervalSinceNow) { [weak self] in
            self?.pending.removeValue(forKey: id)?.done()
        }
    }
    func stopPlayback() {
        let waiting = pending.values
        pending = [:]
        queuedUntil = Date()
        waiting.forEach { $0.done() }
    }
    func playAlone(_ pcm: Data, done: @escaping () -> Void) { DispatchQueue.main.async(execute: done) }
    func stopAlone() {}
    func feed(_ samples: [Int16]) {
        let sink = onSamples
        queue.async { sink?(samples) }
    }
}

enum Sim { static var speed = 1.0 }

enum AppleVoice {
    enum Failure: LocalizedError { case none; var errorDescription: String? { "apple" } }
    static func pcm(_ text: String, language code: String, heard: @escaping @Sendable (Data) -> Void = { _ in }) async throws -> Data {
        Data(count: 48_000)
    }
}

final class SonioxSession {
    static var all: [SonioxSession] = []
    static let models = ["stt-rt-v5"]
    let target: String
    let speakers: Bool
    let about: [(key: String, value: String)]
    var onEvent: ((SonioxEvent) -> Void)?
    private(set) var isOpen = false
    var sent = 0.0
    var finalized = 0
    let extra: [(vietnamese: String, other: String)]
    init(apiKey: String, target: String, hints: [String], glossary: String, extra: [(vietnamese: String, other: String)] = [],
         speakers: Bool, about: [(key: String, value: String)] = []) {
        self.target = target
        self.speakers = speakers
        self.about = about
        self.extra = extra
        SonioxSession.all.append(self)
    }
    func open() { isOpen = true }
    func send(audio pcm: Data) { sent += PCM.seconds(pcm) }
    func finalize() { finalized += 1 }
    func close() { isOpen = false }
    /// The test speaks for Soniox.
    func say(_ tokens: [SonioxToken]) { onEvent?(.tokens(tokens)) }
}

final class LiveSession {
    static var all: [LiveSession] = []
    static let outputLanguages: Set<String> = ["vi", "en", "zh", "ja", "ko", "es", "pt", "fr", "de", "ru", "it", "hi", "id"]
    static func outputCode(for language: Language) -> String? {
        let code = language.code == "zh-Hant" ? "zh" : language.code
        return outputLanguages.contains(code) ? code : nil
    }
    let language: String
    var onEvent: ((LiveEvent) -> Void)?
    private(set) var isOpen = false
    var sent = 0.0
    init(apiKey: String, language: String) { self.language = language; LiveSession.all.append(self) }
    func open() { isOpen = true }
    func send(audio pcm: Data) { sent += PCM.seconds(pcm) }
    func close() { isOpen = false }
}
