package vn.mocsinh.mstrans.core.conversation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import vn.mocsinh.mstrans.core.Http
import vn.mocsinh.mstrans.core.Language
import vn.mocsinh.mstrans.core.TranslateError
import vn.mocsinh.mstrans.core.Translator
import vn.mocsinh.mstrans.core.arr
import vn.mocsinh.mstrans.core.await
import vn.mocsinh.mstrans.core.text
import vn.mocsinh.mstrans.core.obj
import vn.mocsinh.mstrans.core.parseObject
import vn.mocsinh.mstrans.core.str
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** OpenAI's speech recognition and voices, with the user's key. */
class SpeechServices(private val apiKey: String, private val base: String = "https://api.openai.com/v1") {
    data class Transcript(val text: String, /** What OpenAI heard ("vi", "zh"…), when it could tell. */ val language: String?)

    private class ModelUnavailable(message: String) : Exception(message)

    companion object {
        /**
         * The newest recognizer first; an account that cannot use it yet gets the one before (kept
         * for the rest of the run, so only the first sentence pays for the try).
         */
        private val transcribeModels = listOf("gpt-transcribe", "gpt-4o-transcribe")
        private val preferredModel = AtomicInteger(0)

        /** "zh-TW" and "zh-Hant" are Traditional Chinese; other codes keep only their language. */
        fun normalized(code: String): String {
            val lower = code.lowercase()
            if (lower.startsWith("zh-hant") || lower == "zh-tw" || lower == "zh-hk") return "zh-Hant"
            return lower.substringBefore('-')
        }

        fun voiceInstructions(language: Language): String =
            "The text is in ${language.englishName}; speak it with a native ${language.englishName} accent. " +
                "Speak like a professional interpreter: clear, natural and warm, at a brisk conversational pace."
    }

    /**
     * One utterance (a WAV file) to text. `prompt`: the meeting's latest sentences and the glossary's
     * terms, so names and products come out spelt right.
     */
    suspend fun transcribe(wav: ByteArray, prompt: String): Transcript {
        while (true) {
            val index = preferredModel.get()
            try {
                return transcribe(wav, prompt, transcribeModels[index])
            } catch (e: ModelUnavailable) {
                if (index + 1 < transcribeModels.size) preferredModel.compareAndSet(index, index + 1)
                if (preferredModel.get() <= index) throw TranslateError.server(e.message ?: "")
            }
        }
    }

    private suspend fun transcribe(wav: ByteArray, prompt: String, model: String): Transcript {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
        if (prompt.isNotEmpty()) form.addFormDataPart("prompt", prompt.takeLast(800))
        form.addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
        val request = Request.Builder().url("$base/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(form.build())
            .build()
        val (status, body) = load(request)
        val json = parseObject(body)
        if (status != 200) {
            val error = json?.get("error").obj()
            val code = error?.get("code").str() ?: ""
            val message = error?.get("message").str() ?: ""
            if (code == "model_not_found" || status == 404 || (status == 400 && message.contains("model", true))) {
                throw ModelUnavailable(Translator.explain(status, code, message, model))
            }
            throw failure(status, body, model)
        }
        val text = json?.get("text").str()?.trim() ?: ""
        val language = json?.get("languages").arr()?.firstOrNull().obj()?.get("code").str()
        return Transcript(text, language?.let { normalized(it) })
    }

    /**
     * The translation read aloud: 24 kHz mono PCM16. `marin` and `cedar` are OpenAI's most natural
     * voices. OpenAI sends the voice as it makes it: `heard` gets it a tenth of a second at a time,
     * so reading begins long before the end is made. Returns all of it.
     */
    suspend fun speak(text: String, voice: String, language: Language, heard: (ByteArray) -> Unit = {}): ByteArray {
        val body = buildJsonObject {
            put("model", "gpt-4o-mini-tts")
            put("voice", voice)
            put("input", text.take(3_000))
            put("instructions", voiceInstructions(language))
            put("response_format", "pcm")
        }
        val request = Request.Builder().url("$base/audio/speech")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = try {
            Http.client.newCall(request).await()
        } catch (e: IOException) {
            throw TranslateError.network()
        }
        return response.use {
            if (it.code != 200) throw failure(it.code, withContext(Dispatchers.IO) { it.body?.string().orEmpty() }, "gpt-4o-mini-tts")
            val source = it.body?.source() ?: throw TranslateError.network()
            withContext(Dispatchers.IO) {
                val all = ByteArrayOutputStream()
                val piece = ByteArray(4_800)
                var filled = 0
                try {
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = source.read(piece, filled, piece.size - filled)
                        if (n < 0) break
                        filled += n
                        if (filled == piece.size) {
                            val part = piece.copyOf()
                            heard(part)
                            all.write(part)
                            filled = 0
                        }
                    }
                } catch (e: IOException) {
                    throw TranslateError.network()
                }
                if (filled > 0) {
                    val part = piece.copyOf(filled)
                    heard(part)
                    all.write(part)
                }
                all.toByteArray()
            }
        }
    }

    private suspend fun load(request: Request): Pair<Int, String> = try {
        Http.client.newCall(request).text()
    } catch (e: IOException) {
        throw TranslateError.network()
    }

    private fun failure(status: Int, body: String, model: String): Exception {
        if (status in 500..599) return TranslateError.busy()
        val error = parseObject(body)?.get("error").obj()
        return TranslateError.server(
            Translator.explain(status, error?.get("code").str() ?: "", error?.get("message").str() ?: "", model),
        )
    }
}

/** A voice being made: its sound is kept as it comes, so reading can begin before the end is made. */
class VoiceStream() {
    private var bytes = ByteArray(0)
    private var size = 0
    private var done = false
    private var error: Throwable? = null

    /** Made before: all there already. */
    constructor(made: ByteArray) : this() {
        bytes = made
        size = made.size
        done = true
    }

    class Part(val pcm: ByteArray, val finished: Boolean, val failure: Throwable?)

    /** The next part as it is made. */
    @Synchronized
    fun append(pcm: ByteArray) {
        if (done) return
        if (size + pcm.size > bytes.size) bytes = bytes.copyOf(maxOf(size + pcm.size, bytes.size * 2, 48_000))
        System.arraycopy(pcm, 0, bytes, size, pcm.size)
        size += pcm.size
    }

    /** All of it (which begins with what came before), or why it could not be made. */
    @Synchronized
    fun finish(pcm: ByteArray?, failure: Throwable? = null) {
        if (pcm != null && pcm.size >= size) {
            bytes = pcm
            size = pcm.size
        }
        error = failure
        done = true
    }

    /** The sound after its first `offset` bytes, whether it is all there, and why it stopped short. */
    @Synchronized
    fun sound(after: Int): Part {
        val start = after.coerceIn(0, size)
        return Part(bytes.copyOfRange(start, size), done, error)
    }

    /** How much has come so far, and whether that is all. */
    @Synchronized
    fun available(): Pair<Int, Boolean> = size to done

    /** All of it, once made. */
    suspend fun whole(): Result<ByteArray> {
        while (true) {
            val now = sound(0)
            if (now.finished) return now.failure?.let { Result.failure(it) } ?: Result.success(now.pcm)
            delay(20)
        }
    }
}

/**
 * Voices already made, so Đọc lại plays at once and is not paid for again. The newest are kept, up
 * to `limit` bytes (a minute of voice is 2.9 MB).
 */
class VoiceCache(private val limit: Int = 12_000_000) {
    private val items = LinkedHashMap<String, ByteArray>()
    private var bytes = 0

    companion object {
        fun key(text: String, voice: String, language: String) = "$voice|$language|$text"
    }

    @Synchronized
    fun get(key: String): ByteArray? {
        val pcm = items.remove(key) ?: return null
        items[key] = pcm
        return pcm
    }

    @Synchronized
    fun put(key: String, pcm: ByteArray) {
        if (pcm.size > limit) return
        items.remove(key)?.let { bytes -= it.size }
        items[key] = pcm
        bytes += pcm.size
        while (bytes > limit && items.isNotEmpty()) {
            val first = items.keys.first()
            bytes -= items.remove(first)!!.size
        }
    }

    val count: Int @Synchronized get() = items.size
}

internal fun isCancellation(e: Throwable) = e is CancellationException
