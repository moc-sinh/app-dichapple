import Foundation

/// Sound as OpenAI's speech services take and give it: 24 kHz, mono, 16-bit little-endian PCM.
enum PCM {
    static let rate = 24_000

    static func int16(_ samples: UnsafeBufferPointer<Float>) -> [Int16] {
        samples.map { Int16(max(-1, min(1, $0)) * 32767) }
    }

    static func data(_ samples: ArraySlice<Int16>) -> Data {
        var data = Data(capacity: samples.count * 2)
        for sample in samples {
            let value = UInt16(bitPattern: sample.littleEndian)
            data.append(UInt8(value & 0xFF))
            data.append(UInt8(value >> 8))
        }
        return data
    }

    static func data(_ samples: [Int16]) -> Data { data(samples[...]) }

    static func samples(_ data: Data) -> [Int16] {
        var samples = [Int16](repeating: 0, count: data.count / 2)
        data.withUnsafeBytes { raw in
            for i in samples.indices {
                samples[i] = Int16(bitPattern: UInt16(raw[2 * i]) | UInt16(raw[2 * i + 1]) << 8)
            }
        }
        return samples
    }

    /// A WAV file around the samples, for the transcription upload.
    static func wav(_ samples: [Int16], rate: Int = rate) -> Data { wav(pcm: data(samples), rate: rate) }

    /// A WAV file around PCM16 bytes (a translation played again with the microphone off).
    static func wav(pcm body: Data, rate: Int = rate) -> Data {
        var header = Data()
        func put(_ text: String) { header.append(contentsOf: Array(text.utf8)) }
        func put32(_ value: UInt32) { withUnsafeBytes(of: value.littleEndian) { header.append(contentsOf: $0) } }
        func put16(_ value: UInt16) { withUnsafeBytes(of: value.littleEndian) { header.append(contentsOf: $0) } }
        put("RIFF"); put32(UInt32(36 + body.count)); put("WAVE")
        put("fmt "); put32(16); put16(1); put16(1); put32(UInt32(rate)); put32(UInt32(rate * 2)); put16(2); put16(16)
        put("data"); put32(UInt32(body.count))
        return header + body
    }

    static func seconds(_ pcm: Data) -> Double { Double(pcm.count / 2) / Double(rate) }

    /// Loudness in dB below full scale; -100 for silence.
    static func dbfs(_ samples: ArraySlice<Int16>) -> Double {
        guard !samples.isEmpty else { return -100 }
        var sum = 0.0
        for sample in samples {
            let value = Double(sample) / 32768
            sum += value * value
        }
        let rms = (sum / Double(samples.count)).squareRoot()
        return rms > 0 ? max(-100, 20 * log10(rms)) : -100
    }
}
