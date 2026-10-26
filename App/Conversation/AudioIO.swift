import AVFoundation

/// The microphone and the speaker for the Hội thoại tab. What the microphone hears comes out as
/// 24 kHz mono PCM16 (what OpenAI takes) on `queue`, about a tenth of a second at a time;
/// translations are played back in the same format, one after another.
///
/// Always the iPhone's own microphone and speaker, even with headphones on: the phone on the table
/// hears the room, and what it says is for the partners to hear.
final class AudioIO: NSObject {
    enum Failure: LocalizedError {
        case noMicrophone

        var errorDescription: String? { "Không mở được micro. Đóng app khác đang dùng micro rồi thử lại." }
    }

    let queue = DispatchQueue(label: "MSTrans.audio", qos: .userInitiated)
    /// The microphone's sound, on `queue`.
    var onSamples: (([Int16]) -> Void)?
    /// A phone call or another app took the microphone (true), or gave it back (false). Main queue.
    var onInterruption: ((Bool) -> Void)?

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    /// Reads faster while the voice is behind a room that talks on, at the same pitch.
    private let pitch = AVAudioUnitTimePitch()
    private let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Double(PCM.rate), channels: 1,
                                       interleaved: false)!
    private var converter: AVAudioConverter?
    private(set) var running = false
    /// Sound scheduled and not yet heard (and how long it is): told it is done when playback stops
    /// early too.
    private var pending: [UUID: (done: () -> Void, seconds: Double)] = [:]
    /// A translation read again while not listening (`playAlone`).
    private var alone: AVAudioPlayer?
    private var aloneDone: (() -> Void)?

    override init() {
        super.init()
        engine.attach(player)
        engine.attach(pitch)
        pitch.bypass = true
        engine.connect(player, to: pitch, format: format)
        engine.connect(pitch, to: engine.mainMixerNode, format: format)
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(configurationChanged), name: .AVAudioEngineConfigurationChange, object: engine)
        center.addObserver(self, selector: #selector(interrupted(_:)), name: AVAudioSession.interruptionNotification, object: nil)
    }

    static func requestPermission() async -> Bool {
        await AVAudioApplication.requestRecordPermission()
    }

    /// `echoCancelling`: Apple's voice processing, which takes the phone's own playback out of
    /// what the microphone hears (live mode speaks while it listens). It also favours a voice
    /// close to the phone, so a meeting is listened to without it.
    func start(echoCancelling: Bool) throws {
        stopAlone()
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
        try session.setActive(true)
        try? session.overrideOutputAudioPort(.speaker)
        if engine.inputNode.isVoiceProcessingEnabled != echoCancelling {
            try engine.inputNode.setVoiceProcessingEnabled(echoCancelling)
        }
        try startEngine()
        running = true
    }

    func stop() {
        running = false
        stopPlayback()
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func startEngine() throws {
        let input = engine.inputNode
        input.removeTap(onBus: 0)
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0,
              let converter = AVAudioConverter(from: inputFormat, to: format) else { throw Failure.noMicrophone }
        self.converter = converter
        input.installTap(onBus: 0, bufferSize: 4_096, format: inputFormat) { [weak self] buffer, _ in
            self?.captured(buffer)
        }
        engine.prepare()
        try engine.start()
    }

    private func captured(_ buffer: AVAudioPCMBuffer) {
        guard let converter, buffer.frameLength > 0 else { return }
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * format.sampleRate / buffer.format.sampleRate) + 64
        guard let output = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return }
        var given = false
        var error: NSError?
        let status = converter.convert(to: output, error: &error) { _, inputStatus in
            if given {
                inputStatus.pointee = .noDataNow
                return nil
            }
            given = true
            inputStatus.pointee = .haveData
            return buffer
        }
        guard status != .error, output.frameLength > 0, let channel = output.floatChannelData?[0] else { return }
        let samples = PCM.int16(UnsafeBufferPointer(start: channel, count: Int(output.frameLength)))
        queue.async { [weak self] in self?.onSamples?(samples) }
    }

    /// Plays 24 kHz mono PCM16 after what is already playing; `done` on the main queue once it has
    /// been heard (or playback stopped).
    func play(_ pcm: Data, done: @escaping () -> Void) {
        let samples = PCM.samples(pcm)
        guard running, engine.isRunning, !samples.isEmpty,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)),
              let channel = buffer.floatChannelData?[0] else {
            DispatchQueue.main.async(execute: done)
            return
        }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        for (index, sample) in samples.enumerated() { channel[index] = Float(sample) / 32768 }
        let id = UUID()
        pending[id] = (done, Double(samples.count) / format.sampleRate)
        player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
            DispatchQueue.main.async { self?.pending.removeValue(forKey: id)?.done() }
        }
        if !player.isPlaying { player.play() }
    }

    /// Seconds of sound scheduled and not yet heard (as made, before `setRate`).
    var backlog: Double { pending.values.reduce(0) { $0 + $1.seconds } }

    /// How fast what is played is read: 1 as made, up to 1.35 while catching up. The pitch stays.
    func setRate(_ rate: Float) {
        guard pitch.rate != rate || pitch.bypass != (rate == 1) else { return }
        pitch.rate = rate
        pitch.bypass = rate == 1
    }

    func stopPlayback() {
        player.stop()
        let waiting = pending.values
        pending = [:]
        waiting.forEach { $0.done() }
    }

    /// Plays 24 kHz mono PCM16 while not listening (Đọc lại after Dừng): the speaker only, the
    /// microphone stays off. `done` on the main queue.
    func playAlone(_ pcm: Data, done: @escaping () -> Void) {
        stopAlone()
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .spokenAudio)
            try session.setActive(true)
            let player = try AVAudioPlayer(data: PCM.wav(pcm: pcm), fileTypeHint: AVFileType.wav.rawValue)
            player.delegate = self
            alone = player
            aloneDone = done
            guard player.play() else { return stopAlone() }
        } catch {
            DispatchQueue.main.async(execute: done)
        }
    }

    func stopAlone() {
        guard let player = alone else { return }
        player.stop()
        aloneFinished(ObjectIdentifier(player))
    }

    private func aloneFinished(_ player: ObjectIdentifier) {
        guard let alone, ObjectIdentifier(alone) == player else { return }
        self.alone = nil
        let done = aloneDone
        aloneDone = nil
        if !running { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
        if let done { DispatchQueue.main.async(execute: done) }
    }

    /// Headphones plugged in or taken off: the engine stops and starts again with the new route.
    @objc private func configurationChanged() {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.running else { return }
            self.stopPlayback()
            try? AVAudioSession.sharedInstance().overrideOutputAudioPort(.speaker)
            try? self.startEngine()
        }
    }

    @objc private func interrupted(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self, self.running else { return }
            switch type {
            case .began:
                self.stopPlayback()
                self.onInterruption?(true)
            case .ended:
                try? AVAudioSession.sharedInstance().setActive(true)
                try? AVAudioSession.sharedInstance().overrideOutputAudioPort(.speaker)
                try? self.startEngine()
                self.onInterruption?(false)
            @unknown default:
                break
            }
        }
    }
}

extension AudioIO: AVAudioPlayerDelegate {
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        let id = ObjectIdentifier(player)
        DispatchQueue.main.async { [weak self] in self?.aloneFinished(id) }
    }

    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        let id = ObjectIdentifier(player)
        DispatchQueue.main.async { [weak self] in self?.aloneFinished(id) }
    }
}
