package vn.mocsinh.mstrans.audio

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import vn.mocsinh.mstrans.core.Language
import vn.mocsinh.mstrans.core.conversation.PCM
import vn.mocsinh.mstrans.core.conversation.PhoneVoice
import vn.mocsinh.mstrans.core.conversation.PhoneVoiceFailure
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The phone's own voice (Android's text-to-speech): free, made on the phone, and turned into 24 kHz
 * mono PCM16 like OpenAI's, so it is queued, played and kept out of the microphone the same way.
 */
class AndroidPhoneVoice(context: Context) : PhoneVoice {
    private val app = context.applicationContext
    private val lock = Mutex()
    private var engine: TextToSpeech? = null

    /** One sentence at a time: the engine has one language at a time. */
    override suspend fun pcm(text: String, languageCode: String, heard: (ByteArray) -> Unit): ByteArray = lock.withLock {
        val tts = engine() ?: throw PhoneVoiceFailure(NO_ENGINE)
        val locale = locale(languageCode)
        val availability = tts.isLanguageAvailable(locale)
        if (availability < TextToSpeech.LANG_AVAILABLE) {
            throw PhoneVoiceFailure(
                "Điện thoại chưa có giọng ${Language.displayName(languageCode)}. Cài trong Cài đặt điện thoại › Ngôn ngữ › " +
                    "Đầu ra chuyển văn bản sang lời nói, hoặc chọn giọng OpenAI (menu ⋯ › Giọng đọc).",
            )
        }
        tts.language = locale
        val seconds = maxOf(10.0, text.length * 0.25)
        withTimeoutOrNull((seconds * 1000).toLong()) { synthesize(tts, text, heard) }
            ?: throw PhoneVoiceFailure(SILENT)
    }

    private suspend fun engine(): TextToSpeech? {
        engine?.let { return it }
        val ready = CompletableDeferred<Boolean>()
        val tts = withContext(Dispatchers.Main) {
            TextToSpeech(app) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        }
        val ok = withTimeoutOrNull(8_000) { ready.await() } ?: false
        if (!ok) {
            tts.shutdown()
            return null
        }
        engine = tts
        return tts
    }

    private suspend fun synthesize(tts: TextToSpeech, text: String, heard: (ByteArray) -> Unit): ByteArray {
        val id = UUID.randomUUID().toString()
        val file = File(app.cacheDir, "voice-$id.wav")
        try {
            return suspendCancellableCoroutine { continuation ->
                val output = ByteArrayOutputStream()
                var format: Converter? = null
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                        if (utteranceId == id) format = Converter(sampleRateInHz, audioFormat, channelCount)
                    }

                    override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                        if (utteranceId != id || audio == null) return
                        val pcm = format?.convert(audio) ?: return
                        if (pcm.isEmpty()) return
                        synchronized(output) { output.write(pcm) }
                        heard(pcm)
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != id || !continuation.isActive) return
                        var pcm = synchronized(output) { output.toByteArray() }
                        // An engine that only writes the file: the sound is read from it.
                        if (pcm.isEmpty()) pcm = runCatching { fromWav(file.readBytes()) }.getOrDefault(ByteArray(0))
                        if (pcm.isEmpty()) continuation.resumeWithException(PhoneVoiceFailure(SILENT)) else continuation.resume(pcm)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id && continuation.isActive) continuation.resumeWithException(PhoneVoiceFailure(SILENT))
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id && continuation.isActive) continuation.resumeWithException(PhoneVoiceFailure(SILENT))
                    }
                })
                continuation.invokeOnCancellation { runCatching { tts.stop() } }
                val result = tts.synthesizeToFile(text, Bundle(), file, id)
                if (result != TextToSpeech.SUCCESS && continuation.isActive) {
                    continuation.resumeWithException(PhoneVoiceFailure(SILENT))
                }
            }
        } finally {
            file.delete()
        }
    }

    /** The engine's sound, whatever its format, as 24 kHz mono PCM16. */
    private class Converter(rate: Int, private val encoding: Int, private val channels: Int) {
        private val resampler = Resampler(rate, PCM.RATE)
        private var held = ByteArray(0)

        fun convert(bytes: ByteArray): ByteArray {
            val data = if (held.isEmpty()) bytes else held + bytes
            val frameBytes = channels * when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                else -> 2
            }
            val frames = data.size / frameBytes
            held = data.copyOfRange(frames * frameBytes, data.size)
            val mono = ShortArray(frames)
            for (f in 0 until frames) {
                var sum = 0
                for (ch in 0 until channels) {
                    val at = f * frameBytes + ch * (frameBytes / channels)
                    sum += when (encoding) {
                        AudioFormat.ENCODING_PCM_8BIT -> ((data[at].toInt() and 0xFF) - 128) shl 8
                        AudioFormat.ENCODING_PCM_FLOAT -> {
                            val bits = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8) or
                                ((data[at + 2].toInt() and 0xFF) shl 16) or ((data[at + 3].toInt() and 0xFF) shl 24)
                            (Float.fromBits(bits).coerceIn(-1f, 1f) * 32767).toInt()
                        }
                        else -> (data[at].toInt() and 0xFF) or (data[at + 1].toInt() shl 8)
                    }
                }
                mono[f] = (sum / channels).toShort()
            }
            return PCM.data(resampler.convert(mono, mono.size))
        }
    }

    companion object {
        private const val NO_ENGINE =
            "Điện thoại chưa có bộ đọc văn bản. Cài “Dịch vụ lời nói của Google” từ CH Play, hoặc chọn giọng OpenAI (menu ⋯ › Giọng đọc)."
        private const val SILENT = "Giọng điện thoại không đọc được câu này. Thử lại, hoặc chọn giọng OpenAI."

        /** Where a language is spoken in several countries, the one the partners most likely come from. */
        fun locale(code: String): Locale = when (code) {
            "vi" -> Locale.forLanguageTag("vi-VN")
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "zh-Hant" -> Locale.TRADITIONAL_CHINESE
            "en" -> Locale.US
            "es" -> Locale.forLanguageTag("es-ES")
            "fr" -> Locale.FRANCE
            "de" -> Locale.GERMANY
            "ko" -> Locale.KOREA
            "ja" -> Locale.JAPAN
            else -> Locale.forLanguageTag(code)
        }

        /** The sound of a WAV file (any rate, 8/16-bit or float, mono or stereo) as 24 kHz mono PCM16. */
        fun fromWav(wav: ByteArray): ByteArray {
            fun int(at: Int) = (wav[at].toInt() and 0xFF) or ((wav[at + 1].toInt() and 0xFF) shl 8) or
                ((wav[at + 2].toInt() and 0xFF) shl 16) or ((wav[at + 3].toInt() and 0xFF) shl 24)
            fun short(at: Int) = (wav[at].toInt() and 0xFF) or ((wav[at + 1].toInt() and 0xFF) shl 8)
            if (wav.size < 44 || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF") return ByteArray(0)
            var at = 12
            var rate = 0
            var channels = 1
            var bits = 16
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            while (at + 8 <= wav.size) {
                val id = String(wav, at, 4, Charsets.US_ASCII)
                val size = int(at + 4)
                val body = at + 8
                if (id == "fmt ") {
                    val type = short(body)
                    channels = short(body + 2)
                    rate = int(body + 4)
                    bits = short(body + 14)
                    encoding = when {
                        type == 3 -> AudioFormat.ENCODING_PCM_FLOAT
                        bits == 8 -> AudioFormat.ENCODING_PCM_8BIT
                        else -> AudioFormat.ENCODING_PCM_16BIT
                    }
                } else if (id == "data" && rate > 0) {
                    val end = minOf(wav.size, if (size <= 0) wav.size else body + size)
                    return Converter(rate, encoding, maxOf(1, channels)).convert(wav.copyOfRange(body, end))
                }
                at = body + size + (size and 1)
                if (size < 0) break
            }
            return ByteArray(0)
        }
    }
}
