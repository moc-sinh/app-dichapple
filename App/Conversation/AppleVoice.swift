import AVFoundation

/// The iPhone's own voices: free, made on the phone, and turned into 24 kHz mono PCM16 like
/// OpenAI's, so they are queued, played and kept out of the microphone the same way.
enum AppleVoice {
    enum Failure: LocalizedError {
        case noVoice(String)
        case silent

        var errorDescription: String? {
            switch self {
            case .noVoice(let name):
                return "iPhone chưa có giọng \(name). Tải trong Cài đặt › Trợ năng › Nội dung được đọc › Giọng nói, hoặc chọn giọng OpenAI."
            case .silent:
                return "Giọng iPhone không đọc được câu này. Thử lại, hoặc chọn giọng OpenAI."
            }
        }
    }

    /// The best voice installed for the language: premium, then enhanced, then the standard one.
    static func voice(for code: String) -> AVSpeechSynthesisVoice? {
        let wanted = locale(for: code)
        let voices = AVSpeechSynthesisVoice.speechVoices().filter {
            $0.language.hasPrefix(wanted) && !$0.voiceTraits.contains(.isNoveltyVoice)
                && !$0.voiceTraits.contains(.isPersonalVoice)
        }
        return voices.max { $0.quality.rawValue < $1.quality.rawValue } ?? AVSpeechSynthesisVoice(language: wanted)
    }

    /// Where a language is spoken in several countries, the one the partners most likely come from.
    private static func locale(for code: String) -> String {
        switch code {
        case "zh": return "zh-CN"
        case "zh-Hant": return "zh-TW"
        case "en": return "en-US"
        case "es": return "es-ES"
        case "fr": return "fr-FR"
        case "de": return "de-DE"
        default: return code
        }
    }

    /// `text` read in the language `code`; `heard` gets the sound as it is made, from another thread.
    @MainActor
    static func pcm(_ text: String, language code: String, heard: @escaping @Sendable (Data) -> Void = { _ in }) async throws -> Data {
        guard let voice = voice(for: code) else { throw Failure.noVoice(Meeting.languageName(code)) }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voice
        let job = Job(heard: heard)
        return try await withCheckedThrowingContinuation { continuation in
            job.start(utterance, seconds: max(10, Double(text.count) * 0.25), continuation: continuation)
        }
    }

    /// One sentence being made. It holds its synthesizer (which holds it) until it is done.
    private final class Job: NSObject, AVSpeechSynthesizerDelegate, @unchecked Sendable {
        private var synthesizer: AVSpeechSynthesizer?
        private var converter: AVAudioConverter?
        private var continuation: CheckedContinuation<Data, Error>?
        private var output = Data()
        private let heard: @Sendable (Data) -> Void
        private let lock = NSLock()
        private let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Double(PCM.rate), channels: 1,
                                           interleaved: false)!

        init(heard: @escaping @Sendable (Data) -> Void) {
            self.heard = heard
        }

        func start(_ utterance: AVSpeechUtterance, seconds: Double, continuation: CheckedContinuation<Data, Error>) {
            self.continuation = continuation
            let synthesizer = AVSpeechSynthesizer()
            self.synthesizer = synthesizer
            synthesizer.delegate = self
            synthesizer.write(utterance) { [self] buffer in self.take(buffer) }
            // A voice that never says it has finished.
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { [self] in self.finish() }
        }

        private func take(_ buffer: AVAudioBuffer) {
            guard let buffer = buffer as? AVAudioPCMBuffer else { return }
            // An empty buffer: the sentence is all there.
            guard buffer.frameLength > 0 else { return finish() }
            lock.lock()
            defer { lock.unlock() }
            guard continuation != nil else { return }
            if converter == nil || converter?.inputFormat != buffer.format {
                converter = AVAudioConverter(from: buffer.format, to: format)
            }
            guard let converter else { return }
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * format.sampleRate / buffer.format.sampleRate) + 64
            guard let converted = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return }
            var given = false
            var error: NSError?
            let status = converter.convert(to: converted, error: &error) { _, inputStatus in
                if given {
                    inputStatus.pointee = .noDataNow
                    return nil
                }
                given = true
                inputStatus.pointee = .haveData
                return buffer
            }
            guard status != .error, converted.frameLength > 0, let channel = converted.floatChannelData?[0] else { return }
            let pcm = PCM.data(PCM.int16(UnsafeBufferPointer(start: channel, count: Int(converted.frameLength))))
            output.append(pcm)
            heard(pcm)
        }

        func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
            // The last buffer may still be on its way.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [self] in self.finish() }
        }

        func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
            finish()
        }

        private func finish() {
            lock.lock()
            let continuation = continuation
            self.continuation = nil
            let output = output
            lock.unlock()
            guard let continuation else { return }
            DispatchQueue.main.async { [self] in self.synthesizer = nil }
            if output.isEmpty {
                continuation.resume(throwing: Failure.silent)
            } else {
                continuation.resume(returning: output)
            }
        }
    }
}
