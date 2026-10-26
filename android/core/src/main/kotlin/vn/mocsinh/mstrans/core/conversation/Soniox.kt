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
    /** Who said it (Soniox's speaker number, "1", "2"…), empty when not told. */
    val speaker: String = "",
    /** Where it begins and ends in the sound sent, in milliseconds (words said, not their translation). */
    val startMs: Int? = null,
    val endMs: Int? = null,
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
                    speaker = token["speaker"].str() ?: token["speaker"].int()?.toString() ?: "",
                    startMs = token["start_ms"].int(), endMs = token["end_ms"].int(),
                )
            }
            if (tokens.isNotEmpty()) events.add(Tokens(tokens))
            if (o["finished"].bool() == true) events.add(Finished)
            return events
        }

        /**
         * The session's first message: how the sound comes (24 kHz mono PCM16), the languages
         * expected, the words to hear right, and what everything heard is translated into. A sentence
         * ends half a second after the speaker stops (the shortest Soniox allows). `speakers`: each
         * word says who said it, so a room of several people is cut sentence by sentence, speaker by
         * speaker. `about`: what the talk is (its setting, topic, who the user is), for Soniox's
         * `general` context, which guides both the words it hears and its translation.
         */
        fun configMessage(
            apiKey: String, model: String, target: String, hints: List<String>,
            terms: List<String>, pairs: List<Pair<String, String>>, speakers: Boolean = false,
            about: List<Pair<String, String>> = emptyList(),
        ): String = buildJsonObject {
            put("api_key", apiKey)
            put("audio_format", "pcm_s16le")
            if (about.isNotEmpty() || terms.isNotEmpty() || pairs.isNotEmpty()) {
                putJsonObject("context") {
                    if (about.isNotEmpty()) put("general", buildJsonArray {
                        for ((key, value) in about) add(buildJsonObject {
                            put("key", key)
                            put("value", value)
                        })
                    })
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
            if (speakers) put("enable_speaker_diarization", true)
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
 * What one Soniox session has heard, cut into sentences: the one being said (its settled words, then
 * Soniox's guess at the rest, shown as they come) and those finished.
 *
 * With `splits` (the app makes each sentence's translation itself, with GPT), a sentence also ends
 * where another person starts speaking, where the language changes, and after a long speech's full
 * stop, without waiting for Soniox's end of sentence: in a room where people talk on without pausing,
 * Soniox's sentences ran into one another, several speakers and languages in one.
 *
 * Soniox sends what was said and its translation as one stream, each piece of translation right after
 * the words it translates (its docs; its own SDK prints a piece under the words before it), and settles
 * the stream from its start. So a piece of translation belongs to the sentence of the words just
 * before it in the stream: a sentence cut at its full stop still gets the translation of its last words
 * (`awaiting`, shown as a preview), and the next sentence's words settling mean that translation is all in.
 */
class SonioxTranscript(
    /** What it is translated into. */
    val target: String,
) {
    class Sentence(
        val text: String = "",
        val translation: String = "",
        /** The language it was said in, empty when not told. */
        val language: String = "",
        /** Who said it (Soniox's speaker number), empty when not told. */
        val speaker: String = "",
        /** Counts the session's sentences, so a finished one can be found again. */
        val number: Int = 0,
        /** Where its words begin and end in the session's sound (milliseconds), when told. */
        val startMs: Int? = null,
        val endMs: Int? = null,
    ) {
        val isEmpty: Boolean get() = text.isEmpty() && translation.isEmpty()

        fun copy(text: String = this.text, translation: String = this.translation) =
            Sentence(text, translation, language, speaker, number, startMs, endMs)

        /** The same words, translation, language and speaker (its number and times aside). */
        override fun equals(other: Any?): Boolean = other is Sentence && text == other.text &&
            translation == other.translation && language == other.language && speaker == other.speaker

        override fun hashCode(): Int = listOf(text, translation, language, speaker).hashCode()

        override fun toString(): String = "Sentence(text=$text, translation=$translation, language=$language, speaker=$speaker, number=$number)"
    }

    private class Builder {
        val text = StringBuilder()
        val translation = StringBuilder()
        var language = ""
        var speaker = ""
        var number = 0
        var startMs: Int? = null
        var endMs: Int? = null
        val isEmpty get() = text.isEmpty() && translation.isEmpty()
        fun clear() {
            text.setLength(0)
            translation.setLength(0)
            language = ""
            speaker = ""
            startMs = null
            endMs = null
        }
    }

    /** Cut at a change of speaker or language and after a long speech's full stop (see above). */
    var splits = false
    private val settled = Builder()
    private val guess = Builder()
    /** The speaker finished the sentence; its translation may still be on its way. */
    var ended = false
        private set
    /** `splits`: finished sentences whose translation may still be coming, and those whose last came now. */
    private val awaiting = ArrayList<Sentence>()
    private val released = ArrayList<Sentence>()
    /** Soniox's guess at the translation of a finished sentence, and which one. */
    private val awaitingGuess = StringBuilder()
    private var awaitingGuessNumber = 0
    /** The sentence of the words settled last (0: none yet): the next settled piece of translation is its. */
    private var owner = 0
    private var nextNumber = 1
    /** Where the words settled last end in the sound sent (seconds), for how far Soniox lags. */
    var settledEnd: Double? = null
        private set

    init {
        settled.number = nextNumber
    }

    /** The sentence being said, as far as it is known. */
    val current: Sentence
        get() = Sentence(
            settled.text.toString() + guess.text, settled.translation.toString() + guess.translation,
            settled.language.ifEmpty { guess.language }, settled.speaker.ifEmpty { guess.speaker }, settled.number,
            settled.startMs ?: guess.startMs, guess.endMs ?: settled.endMs,
        )

    /** One message's tokens; returns the sentences it finished. */
    fun take(tokens: List<SonioxToken>): List<Sentence> {
        val finished = ArrayList<Sentence>()
        // The guesses come again whole in every message.
        guess.clear()
        awaitingGuess.setLength(0)
        released.clear()
        // A guessed word came in this message: guessed translation after it is of the words being said.
        var guessing = false
        for (token in tokens) {
            if (token.endsSentence) {
                ended = true
                continue
            }
            if (token.isTranslation) {
                addTranslation(token, guessing)
                continue
            }
            // Words said after the end begin the next sentence.
            if (ended) finish()?.let { finished.add(it) }
            if (token.isFinal) {
                if (splits && startsAnother(token)) finish()?.let { finished.add(it) }
                add(token, settled)
                token.endMs?.let { settledEnd = it / 1000.0 }
                // Settled words of this sentence: what came before them, translations too, is settled.
                owner = settled.number
                released.addAll(awaiting)
                awaiting.clear()
                if (splits && endsLongSpeech(token)) finish()?.let { finished.add(it) }
            } else {
                add(token, guess)
                guessing = true
            }
        }
        // An ended sentence is done once nothing of it is guessed and, without `splits`, its
        // translation is in (or it needs none: said in the language it is translated into).
        val translated = splits || settled.translation.isNotEmpty() || settled.language == target || settled.text.isBlank()
        val guessed = if (splits) guess.text.isNotEmpty() else !guess.isEmpty
        if (ended && !guessed && translated) finish()?.let { finished.add(it) }
        return finished
    }

    /** The finished sentences still getting Soniox's translation, and those whose last piece just came. */
    val previews: List<Sentence>
        get() = (released + awaiting).map { sentence ->
            val translation = if (sentence.number == awaitingGuessNumber) sentence.translation + awaitingGuess else sentence.translation
            sentence.copy(translation = translation.trim())
        }

    /** Ends the sentence being said, with what is known of it (null when nothing is). */
    fun finish(): Sentence? {
        val sentence = current
        settled.clear()
        guess.clear()
        ended = false
        nextNumber += 1
        settled.number = nextNumber
        val trimmed = sentence.copy(text = sentence.text.trim(), translation = sentence.translation.trim())
        if (trimmed.isEmpty) return null
        // The translation of its last words may still come (unless none is needed).
        if (splits && owner == trimmed.number && trimmed.text.isNotEmpty() && trimmed.language != target) {
            awaiting.add(trimmed)
            while (awaiting.size > 2) awaiting.removeAt(0)
        }
        return trimmed
    }

    /** A settled word that is someone else's, or another language, while the sentence has words. */
    private fun startsAnother(token: SonioxToken): Boolean {
        val said = settled.text.toString().trim()
        if (said.isEmpty() || token.text.none { it.isLetterOrDigit() }) return false
        if (token.speaker.isNotEmpty() && settled.speaker.isNotEmpty() && token.speaker != settled.speaker) return true
        // Another language after a sentence or a clause of some length. A foreign word inside a sentence
        // ("好 OK 的", "gửi em cái quotation nhé") stays in it: no full stop or comma before it.
        if (token.language.isEmpty() || settled.language.isEmpty() || token.language == settled.language) return false
        if (said.last() !in ".?!。？！…,，、;；:：") return false
        return TranscriptFilter.syllableCount(said) >= 4
    }

    /**
     * A full stop ending a long speech, or a comma in a very long one (someone talking on for ten seconds
     * and more without a full stop): it is translated now, the rest goes on as a new sentence.
     */
    private fun endsLongSpeech(token: SonioxToken): Boolean {
        val syllables = TranscriptFilter.syllableCount(settled.text.toString())
        if (endsSentence(token.text)) return syllables >= 12
        val last = token.text.trim().lastOrNull() ?: return false
        return last in ",，、;；" && syllables >= 40
    }

    /**
     * A piece of translation: of the words just before it. A settled piece follows the words settled
     * last (`owner`), which may be a sentence already cut; a guessed one, the words guessed before it in
     * this message, or else the settled ones. Without `splits`, the sentence being said.
     */
    private fun addTranslation(token: SonioxToken, guessing: Boolean) {
        val number = if (!splits || guessing || owner == 0) settled.number else owner
        if (number != settled.number) {
            // A cut sentence's: the one waiting for it (one let go of is gone, and the piece with it).
            val index = awaiting.indexOfFirst { it.number == number }
            if (index < 0) return
            if (token.isFinal) {
                awaiting[index] = awaiting[index].copy(translation = awaiting[index].translation + token.text)
            } else {
                awaitingGuess.append(token.text)
                awaitingGuessNumber = number
            }
            return
        }
        add(token, if (token.isFinal) settled else guess)
    }

    private fun add(token: SonioxToken, sentence: Builder) {
        if (token.isTranslation) {
            sentence.translation.append(token.text)
        } else {
            sentence.text.append(token.text)
            if (sentence.language.isEmpty()) sentence.language = token.language
            if (sentence.speaker.isEmpty()) sentence.speaker = token.speaker
            if (sentence.startMs == null) sentence.startMs = token.startMs
            token.endMs?.let { sentence.endMs = it }
        }
    }

    companion object {
        fun endsSentence(text: String): Boolean {
            val last = text.trim().lastOrNull() ?: return false
            return last in ".?!。？！…"
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
    /** Tells who said each word (the room's session; the user's turn is one person). */
    private val speakers: Boolean = false,
    /** What the talk is about, for Soniox's `general` context. */
    private val about: List<Pair<String, String>> = emptyList(),
    private val url: String = URL,
) {
    companion object {
        const val URL = "wss://stt-rt.soniox.com/transcribe-websocket"

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
        sendText(SonioxEvent.configMessage(apiKey, models[model], target, hints, terms.first, terms.second, speakers, about))
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
