package vn.mocsinh.mstrans.core.conversation

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import vn.mocsinh.mstrans.core.Http
import vn.mocsinh.mstrans.core.arr
import vn.mocsinh.mstrans.core.await
import vn.mocsinh.mstrans.core.obj
import vn.mocsinh.mstrans.core.parseObject
import vn.mocsinh.mstrans.core.str
import java.io.IOException
import java.util.Base64
import java.util.Collections

/**
 * Google's Chirp 3 HD voices, with the user's Google Cloud key: natural in Vietnamese and in most
 * of the partners' languages, a sentence in about half a second, the first million characters of a
 * month free. Asked for at 24 kHz like OpenAI's, so they are queued and played the same way.
 */
class GoogleVoice(private val apiKey: String, private val base: String = "https://texttospeech.googleapis.com") {
    class Failure(val kind: Kind, val detail: String = "") : Exception() {
        enum class Kind { BAD_KEY, NOT_ENABLED, BILLING, QUOTA, BUSY, NETWORK, UNSUPPORTED, REFUSED }

        override val message: String
            get() = when (kind) {
                Kind.BAD_KEY -> "Key Google không đúng. Kiểm tra lại trong Cài đặt › Giọng Google."
                Kind.NOT_ENABLED ->
                    "Dự án Google của key chưa bật Cloud Text-to-Speech API. Bật ở console.cloud.google.com rồi thử lại."
                Kind.BILLING -> "Dự án Google chưa gắn thanh toán. Google cần thẻ, dù chỉ dùng trong phần miễn phí."
                Kind.QUOTA -> "Google báo đọc quá nhiều trong một lúc. Đợi một chút rồi thử lại."
                Kind.BUSY -> "Máy chủ Google đang bận. Thử lại."
                Kind.NETWORK -> "Không kết nối được Google. Kiểm tra mạng rồi thử lại."
                Kind.UNSUPPORTED -> "Google chưa có giọng cho ngôn ngữ này."
                Kind.REFUSED -> "Google không đọc được câu này: $detail"
            }
    }

    companion object {
        /** The voices offered, by Google's name for them (the same in every language). */
        const val FEMALE = "Aoede"
        const val MALE = "Charon"
        private const val PREFIX = "google-"

        /** A Google voice as the voice setting keeps it ("google-Aoede"), and back. */
        fun id(name: String) = PREFIX + name
        fun nameOf(id: String): String? = if (id.startsWith(PREFIX)) id.removePrefix(PREFIX) else null

        /** Google's locale for an app language; null where Chirp 3 HD has no voice. */
        fun locale(code: String): String? = when (code) {
            "vi" -> "vi-VN"
            "zh", "zh-Hant" -> "cmn-CN"
            "en" -> "en-US"
            "ko" -> "ko-KR"
            "ja" -> "ja-JP"
            "th" -> "th-TH"
            "id" -> "id-ID"
            "ru" -> "ru-RU"
            "fr" -> "fr-FR"
            "de" -> "de-DE"
            "es" -> "es-ES"
            "hi" -> "hi-IN"
            "ar" -> "ar-XA"
            else -> null
        }

        /** Languages Google turned down this run: read by the phone's voice without asking again. */
        private val refusedLocales: MutableSet<String> = Collections.synchronizedSet(HashSet())

        fun requestBody(text: String, voice: String, locale: String): String = buildJsonObject {
            putJsonObject("audioConfig") {
                put("audioEncoding", "LINEAR16")
                put("sampleRateHertz", PCM.RATE)
            }
            // Google takes 5,000 bytes: a sentence is far shorter.
            putJsonObject("input") { put("text", text.take(1_500)) }
            putJsonObject("voice") {
                put("languageCode", locale)
                put("name", "$locale-Chirp3-HD-$voice")
            }
        }.toString()

        /** The voice in Google's answer: `audioContent`, a WAV file for LINEAR16. */
        fun pcmFromAnswer(body: String): ByteArray? {
            val content = parseObject(body)?.get("audioContent").str() ?: return null
            val file = runCatching { Base64.getDecoder().decode(content) }.getOrNull() ?: return null
            return PCM.bodyOfWav(file)
        }

        fun failure(status: Int, body: String): Failure {
            if (status in 500..599) return Failure(Failure.Kind.BUSY)
            val error = parseObject(body)?.get("error").obj()
            val message = error?.get("message").str() ?: ""
            val reasons = (error?.get("details").arr() ?: emptyList()).mapNotNull { it.obj()?.get("reason").str() }
            val lower = message.lowercase()
            if ("API_KEY_INVALID" in reasons || status == 401) return Failure(Failure.Kind.BAD_KEY)
            if ("BILLING_DISABLED" in reasons || lower.contains("billing")) return Failure(Failure.Kind.BILLING)
            if ("SERVICE_DISABLED" in reasons || lower.contains("has not been used")) return Failure(Failure.Kind.NOT_ENABLED)
            if (status == 429 || "RATE_LIMIT_EXCEEDED" in reasons) return Failure(Failure.Kind.QUOTA)
            if (status == 400 && (lower.contains("voice") || lower.contains("language"))) return Failure(Failure.Kind.UNSUPPORTED)
            return Failure(Failure.Kind.REFUSED, message.ifEmpty { "HTTP $status" })
        }
    }

    /** `text` read in the language `code` by the voice `name` ("Aoede"). */
    suspend fun speak(text: String, name: String, code: String): ByteArray {
        val locale = locale(code)
        if (locale == null || locale in refusedLocales) throw Failure(Failure.Kind.UNSUPPORTED)
        val request = Request.Builder().url("$base/v1/text:synthesize")
            .header("X-Goog-Api-Key", apiKey)
            .post(requestBody(text, name, locale).toRequestBody("application/json".toMediaType()))
            .build()
        val (status, body) = load(request)
        if (status != 200) {
            val failure = failure(status, body)
            if (failure.kind == Failure.Kind.UNSUPPORTED) refusedLocales.add(locale)
            throw failure
        }
        val pcm = pcmFromAnswer(body)
        if (pcm == null || pcm.isEmpty()) throw Failure(Failure.Kind.REFUSED, "không có tiếng")
        return pcm
    }

    /**
     * Opens the connection before the first sentence, and tells at once when the key does not work.
     * Null: it works, or the network did not answer (a sentence will say).
     */
    suspend fun warmUp(): Failure? {
        val request = Request.Builder().url("$base/v1/voices?languageCode=vi-VN").header("X-Goog-Api-Key", apiKey).build()
        val (status, body) = runCatching { load(request) }.getOrNull() ?: return null
        if (status == 200) return null
        val failure = failure(status, body)
        return if (failure.kind == Failure.Kind.BUSY) null else failure
    }

    private suspend fun load(request: Request): Pair<Int, String> = try {
        Http.client.newCall(request).await().use { it.code to it.body?.string().orEmpty() }
    } catch (e: IOException) {
        throw Failure(Failure.Kind.NETWORK)
    }
}
