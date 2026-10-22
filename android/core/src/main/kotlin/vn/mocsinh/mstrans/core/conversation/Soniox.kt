package vn.mocsinh.mstrans.core.conversation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import vn.mocsinh.mstrans.core.Http
import vn.mocsinh.mstrans.core.Language
import vn.mocsinh.mstrans.core.LanguageDetector
import vn.mocsinh.mstrans.core.arr
import vn.mocsinh.mstrans.core.bool
import vn.mocsinh.mstrans.core.int
import vn.mocsinh.mstrans.core.obj
import vn.mocsinh.mstrans.core.parseObject
import vn.mocsinh.mstrans.core.str

/** One piece of what Soniox heard: a word (or part of one) as it was said, or of its translation. */
data class SonioxToken(
    val text: String,
    /** Settled: it will not change. Otherwise Soniox's guess so far, sent again with its next message. */
    val isFinal: Boolean,
    /** Part of the translation, not of what was said. */
    val isTranslation: Boolean,
    /** The language it is in ("vi", "zh"…), empty when not told. */
    val language: String,
) {
    /** `<end>`: the speaker finished a sentence. `<fin>`: what was asked to be settled now is. */
    val endsSentence: Boolean get() = text == "<end>" || text == "<fin>"
}

/** What a Soniox session sends back, and what it is sent. */
sealed class SonioxEvent {
    data class Tokens(val tokens: List<SonioxToken>) : SonioxEvent()
    /** Everything sent has been answered; the session ends. */
    data object Finished : SonioxEvent()
    /** 401 a wrong key, 402 or 429 no credit left, 400 a request it does not take; -1 the network. */
    data class Failed(val code: Int, val message: String) : SonioxEvent()
    data object Closed : SonioxEvent()

    companion object {
        fun parse(text: String): List<SonioxEvent> {
            val o = parseObject(text) ?: return emptyList()
            if (o.containsKey("error_code") || o.containsKey("error_message")) {
                return listOf(Failed(o["error_code"].int() ?: 0, o["error_message"].str() ?: ""))
            }
            val events = ArrayList<SonioxEvent>()
            val tokens = (o["tokens"].arr() ?: emptyList()).mapNotNull { element ->
                val token = element.obj() ?: return@mapNotNull null
                val t = token["text"].str()
                if (t.isNullOrEmpty()) return@mapNotNull null
                SonioxToken(
                    t, token["is_final"].bool() ?: false,
                    token["translation_status"].str() == "translation", token["language"].str() ?: "",
                )
            }
            if (tokens.isNotEmpty()) events.add(Tokens(tokens))
            if (o["finished"].bool() == true) events.add(Finished)
            return events
        }

        /**
         * The session's first message: how the sound comes (24 kHz mono PCM16), the languages
         * expected, the words to hear right, and what everything heard is translated into. A
         * sentence ends half a second after the speaker stops, so it is read aloud at once.
         */
        fun configMessage(
            apiKey: String, model: String, target: String, hints: List<String>,
            terms: List<String>, pairs: List<Pair<String, String>>,
        ): String = buildJsonObject {
            put("api_key", apiKey)
            put("audio_format", "pcm_s16le")
            if (terms.isNotEmpty() || pairs.isNotEmpty()) {
                putJsonObject("context") {
                    if (terms.isNotEmpty()) put("terms", buildJsonArray { terms.forEach { add(JsonPrimitive(it)) } })
                    if (pairs.isNotEmpty()) put("translation_terms", buildJsonArray {
                        for ((source, targetTerm) in pairs) add(buildJsonObject {
                            put("source", source)
                            put("target", targetTerm)
                        })
                    })
                }
            }
            put("enable_endpoint_detection", true)
            put("enable_language_identification", true)
            put("language_hints", buildJsonArray { hints.forEach { add(JsonPrimitive(it)) } })
            put("max_endpoint_delay_ms", 500)
            put("model", model)
            put("num_channels", 1)
            put("sample_rate", PCM.RATE)
            putJsonObject("translation") {
                put("target_language", target)
                put("type", "one_way")
            }
        }.toString()

        const val KEEPALIVE_MESSAGE = "{\"type\":\"keepalive\"}"
        /** Settles what is still guessed now, without waiting for more sound. */
        const val FINALIZE_MESSAGE = "{\"type\":\"finalize\"}"

        /** Soniox's code for a language (it writes Chinese one way). */
        fun code(language: Language): String = if (language.code == "zh-Hant") "zh" else language.code

        /**
         * The glossary's lines ("báo giá = 报价"): every term is a word to hear right, and each pair
         * with one Vietnamese side says how it is translated, from Vietnamese or into it.
         */
        fun glossary(text: String, intoVietnamese: Boolean): Pair<List<String>, List<Pair<String, String>>> {
            val terms = ArrayList<String>()
            val pairs = ArrayList<Pair<String, String>>()
            for (line in text.lines()) {
                if (line.isBlank()) continue
                val sides = line.split("=").map { it.trim() }
                for (side in sides) if (side.isNotEmpty() && side.length <= 40 && terms.size < 60) terms.add(side)
                if (sides.size != 2 || sides[0].isEmpty() || sides[1].isEmpty()) continue
                val marked = sides.map { LanguageDetector.hasVietnameseMarks(it) }
                if (marked[0] == marked[1]) continue
                val (vietnamese, other) = if (marked[0]) sides[0] to sides[1] else sides[1] to sides[0]
                pairs.add(if (intoVietnamese) other to vietnamese else vietnamese to other)
            }
            return terms to pairs
        }
    }
}

/**
 * What one Soniox session has heard, cut into sentences: the one being said (its settled words,
 * then Soniox's guess at the rest) and those finished.
 */
class SonioxTranscript(
    /** What it is translated into. */
    val target: String,
) {
    data class Sentence(val text: String = "", val translation: String = "", val language: String = "") {
        val isEmpty: Boolean get() = text.isEmpty() && translation.isEmpty()
    }

    private class Builder {
        val text = StringBuilder()
        val translation = StringBuilder()
        var language = ""
        val isEmpty get() = text.isEmpty() && translation.isEmpty()
        fun clear() {
            text.setLength(0)
            translation.setLength(0)
            language = ""
        }
    }

    private val settled = Builder()
    private val guess = Builder()
    /** The speaker finished the sentence; its translation may still be on its way. */
    var ended = false
        private set

    /** The sentence being said, as far as it is known. */
    val current: Sentence
        get() = Sentence(
            settled.text.toString() + guess.text, settled.translation.toString() + guess.translation,
            settled.language.ifEmpty { guess.language },
        )

    /** One message's tokens; returns the sentences it finished. */
    fun take(tokens: List<SonioxToken>): List<Sentence> {
        val finished = ArrayList<Sentence>()
        // The guess comes again whole in every message.
        guess.clear()
        for (token in tokens) {
            if (token.endsSentence) {
                ended = true
                continue
            }
            // Words said after the end begin the next sentence.
            if (ended && !token.isTranslation) finish()?.let { finished.add(it) }
            add(token, if (token.isFinal) settled else guess)
        }
        // An ended sentence is done once nothing of it is guessed and its translation is in (or it
        // needs none: said in the language it is translated into).
        val translated = settled.translation.isNotEmpty() || settled.language == target || settled.text.isBlank()
        if (ended && guess.isEmpty && translated) finish()?.let { finished.add(it) }
        return finished
    }

    /** Ends the sentence being said, with what is known of it (null when nothing is). */
    fun finish(): Sentence? {
        val sentence = current
        settled.clear()
        guess.clear()
        ended = false
        val trimmed = sentence.copy(text = sentence.text.trim(), translation = sentence.translation.trim())
        return if (trimmed.isEmpty) null else trimmed
    }

    private fun add(token: SonioxToken, sentence: Builder) {
        if (token.isTranslation) {
            sentence.translation.append(token.text)
        } else {
            sentence.text.append(token.text)
            if (sentence.language.isEmpty()) sentence.language = token.language
        }
    }
}

/**
 * One Soniox session: sound goes in, and what was said comes back as text with its translation into
 * one language, word by word while the speaker is still talking. Used on `scope`'s thread only.
 */
class SonioxSession(
    private val apiKey: String,
    /** The language it translates into (Soniox's code). */
    val target: String,
    private val hints: List<String>,
    glossary: String,
    private val scope: CoroutineScope,
    private val url: String = "wss://stt-rt.soniox.com/transcribe-websocket",
) {
    companion object {
        /** Newest first: the next is tried when Soniox no longer knows the one before. */
        val models = listOf("stt-rt-v5", "stt-rt-v4")
    }

    var onEvent: ((SonioxEvent) -> Unit)? = null
    var isOpen = false
        private set
    private val terms: Pair<List<String>, List<Pair<String, String>>> =
        SonioxEvent.glossary(glossary, intoVietnamese = target == Language.vietnamese.code)
    private var model = 0
    private var socket: WebSocket? = null
    private var closing = false
    private var answered = false
    @Volatile private var lastSent = 0L
    private var keepalive: Job? = null

    fun open() {
        if (socket != null) return
        val request = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build()
        closing = false
        answered = false
        isOpen = true
        val ws = Http.sockets.newWebSocket(request, Listener())
        socket = ws
        sendText(SonioxEvent.configMessage(apiKey, models[model], target, hints, terms.first, terms.second))
        // Soniox ends a session that hears nothing for a while: a session not spoken to is kept open.
        keepalive?.cancel()
        keepalive = scope.launch {
            while (isActive) {
                delay(2000)
                if (System.currentTimeMillis() - lastSent >= 4000) sendText(SonioxEvent.KEEPALIVE_MESSAGE)
            }
        }
    }

    /** 24 kHz mono PCM16, about 100 ms at a time. */
    fun send(pcm: ByteArray) {
        val ws = socket ?: return
        lastSent = System.currentTimeMillis()
        ws.send(pcm.toByteString())
    }

    /** The speaker stopped: what is still guessed is settled and translated now. */
    fun finalize() = sendText(SonioxEvent.FINALIZE_MESSAGE)

    /** Asks for what is still on its way (an empty message), then ends the session. */
    fun close() {
        keepalive?.cancel()
        keepalive = null
        val ws = socket ?: return
        closing = true
        isOpen = false
        ws.send("")
        scope.launch {
            delay(3000)
            ws.close(1000, null)
        }
        socket = null
    }

    private fun sendText(message: String) {
        val ws = socket ?: return
        lastSent = System.currentTimeMillis()
        ws.send(message)
    }

    /** A model Soniox no longer offers: the session starts again with the next one, unseen. */
    private fun retriesNewerModel(code: Int, message: String): Boolean {
        val ws = socket
        if (code != 400 || answered || !message.contains("model", ignoreCase = true) ||
            model + 1 >= models.size || ws == null
        ) return false
        model += 1
        socket = null
        ws.cancel()
        open()
        return true
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val events = SonioxEvent.parse(text)
            if (events.isEmpty()) return
            scope.launch {
                if (webSocket !== socket && !closing) return@launch
                for (event in events) {
                    if (event is SonioxEvent.Failed && webSocket === socket && retriesNewerModel(event.code, event.message)) return@launch
                    if (event is SonioxEvent.Tokens) answered = true
                    onEvent?.invoke(event)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onMessage(webSocket, bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            ended(webSocket, null, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            ended(webSocket, response?.code ?: 0, t.message ?: "")
        }

        private fun ended(webSocket: WebSocket, status: Int?, message: String) {
            scope.launch {
                if (webSocket !== socket && !closing) return@launch
                val wasClosing = closing
                if (webSocket === socket) socket = null
                isOpen = false
                keepalive?.cancel()
                keepalive = null
                if (!wasClosing) {
                    // Refused at the start: a wrong key, or no credit.
                    if (status in listOf(401, 402, 403, 429)) {
                        onEvent?.invoke(SonioxEvent.Failed(if (status == 403) 401 else status!!, ""))
                    } else {
                        onEvent?.invoke(SonioxEvent.Failed(-1, message))
                    }
                }
                onEvent?.invoke(SonioxEvent.Closed)
            }
        }
    }
}
