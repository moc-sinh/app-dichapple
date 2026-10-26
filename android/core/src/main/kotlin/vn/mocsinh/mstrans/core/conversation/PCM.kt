package vn.mocsinh.mstrans.core.conversation

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Sound as OpenAI's speech services take and give it: 24 kHz, mono, 16-bit little-endian PCM. */
object PCM {
    const val RATE = 24_000

    fun data(samples: ShortArray, from: Int = 0, to: Int = samples.size): ByteArray {
        val out = ByteArray((to - from) * 2)
        var j = 0
        for (i in from until to) {
            val v = samples[i].toInt()
            out[j++] = (v and 0xFF).toByte()
            out[j++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun samples(data: ByteArray, length: Int = data.size): ShortArray {
        val out = ShortArray(length / 2)
        for (i in out.indices) {
            out[i] = ((data[2 * i].toInt() and 0xFF) or (data[2 * i + 1].toInt() shl 8)).toShort()
        }
        return out
    }

    /** A WAV file around the samples, for the transcription upload. */
    fun wav(samples: ShortArray, rate: Int = RATE): ByteArray = wav(data(samples), rate)

    /** A WAV file around PCM16 bytes. */
    fun wav(pcm: ByteArray, rate: Int = RATE): ByteArray {
        val header = java.io.ByteArrayOutputStream(44 + pcm.size)
        fun put(text: String) = header.write(text.toByteArray(Charsets.US_ASCII))
        fun put32(value: Int) {
            header.write(value and 0xFF); header.write((value shr 8) and 0xFF)
            header.write((value shr 16) and 0xFF); header.write((value shr 24) and 0xFF)
        }
        fun put16(value: Int) {
            header.write(value and 0xFF); header.write((value shr 8) and 0xFF)
        }
        put("RIFF"); put32(36 + pcm.size); put("WAVE")
        put("fmt "); put32(16); put16(1); put16(1); put32(rate); put32(rate * 2); put16(2); put16(16)
        put("data"); put32(pcm.size)
        header.write(pcm)
        return header.toByteArray()
    }

    /**
     * The PCM16 bytes in a WAV file (its "data" chunk), as Google sends its voices; bytes that are
     * not a WAV file are taken as PCM16 already.
     */
    fun bodyOfWav(file: ByteArray): ByteArray {
        fun tag(at: Int, s: String) = at + 4 <= file.size && (0 until 4).all { file[at + it] == s[it].code.toByte() }
        if (file.size < 12 || !tag(0, "RIFF") || !tag(8, "WAVE")) return file.copyOf(file.size / 2 * 2)
        var offset = 12
        while (offset + 8 <= file.size) {
            val size = (file[offset + 4].toInt() and 0xFF) or ((file[offset + 5].toInt() and 0xFF) shl 8) or
                ((file[offset + 6].toInt() and 0xFF) shl 16) or ((file[offset + 7].toInt() and 0xFF) shl 24)
            val start = offset + 8
            if (tag(offset, "data")) {
                // A file written as it was made may give no length, or one past its end.
                val end = if (size <= 0 || start + size > file.size) file.size else start + size
                return file.copyOfRange(start, start + (end - start) / 2 * 2)
            }
            if (size < 0) break
            offset = start + size + size % 2
        }
        return ByteArray(0)
    }

    fun seconds(pcm: ByteArray): Double = seconds(pcm.size)
    fun seconds(bytes: Int): Double = (bytes / 2).toDouble() / RATE

    /** Loudness in dB below full scale; -100 for silence. */
    fun dbfs(samples: ShortArray, from: Int = 0, to: Int = samples.size): Double {
        if (to <= from) return -100.0
        var sum = 0.0
        for (i in from until to) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / (to - from))
        return if (rms > 0) max(-100.0, 20 * log10(rms)) else -100.0
    }
}

/** A growable list of samples without boxing. */
class SampleBuffer(capacity: Int = 4096) {
    var array = ShortArray(capacity)
        private set
    var size = 0
        private set

    fun append(samples: ShortArray, from: Int = 0, to: Int = samples.size) {
        val n = to - from
        ensure(size + n)
        System.arraycopy(samples, from, array, size, n)
        size += n
    }

    fun append(other: SampleBuffer) = append(other.array, 0, other.size)

    /** Drops the first `n` samples. */
    fun removeFirst(n: Int) {
        val k = minOf(n, size)
        System.arraycopy(array, k, array, 0, size - k)
        size -= k
    }

    fun clear() {
        size = 0
    }

    fun toArray(from: Int = 0, to: Int = size): ShortArray = array.copyOfRange(from, to)

    private fun ensure(n: Int) {
        if (n <= array.size) return
        array = array.copyOf(maxOf(n, array.size * 2))
    }
}
