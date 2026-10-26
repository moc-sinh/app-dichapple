package vn.mocsinh.mstrans.core.conversation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import vn.mocsinh.mstrans.core.Http
import vn.mocsinh.mstrans.core.Language
import vn.mocsinh.mstrans.core.obj
import vn.mocsinh.mstrans.core.parseObject
import vn.mocsinh.mstrans.core.str
import java.util.Base64

/** What OpenAI's live speech translation (`gpt-realtime-translate`) sends back while people talk. */
sealed class LiveEvent {
    data object Ready : LiveEvent()
    /** More of what was said, as recognised. */
    data class Source(val delta: String) : LiveEvent()
    /** More of its translation. */
    data class Target(val delta: String) : LiveEvent()
    /** More of the translation spoken: 24 kHz mono PCM16. */
    class Audio(val pcm: ByteArray) : LiveEvent()
    data class Failed(val code: String, val message: String) : LiveEvent()
    data object Closed : LiveEvent()

    companion object {
        fun parse(text: String): LiveEvent? {
            val o = parseObject(text) ?: return null
            return when (o["type"].str()) {
                "session.created" -> Ready
                "session.closed" -> Closed
                "session.input_transcript.delta" -> o["delta"].str()?.let { Source(it) }
                "session.output_transcript.delta" -> o["delta"].str()?.let { Target(it) }
                "session.output_audio.delta" -> {
                    val delta = o["delta"].str() ?: return null
                    runCatching { Audio(Base64.getDecoder().decode(delta)) }.getOrNull()
                }
                "error" -> {
                    val error = o["error"].obj() ?: o
                    Failed(error["code"].str() ?: "error", error["message"].str() ?: "")
                }
                else -> null
            }
        }

        /** Sound for the session (24 kHz mono PCM16). Base64 needs no escaping in JSON. */
        fun appendMessage(pcm: ByteArray): String =
            "{\"type\":\"session.input_audio_buffer.append\",\"audio\":\"" + Base64.getEncoder().encodeToString(pcm) + "\"}"

        fun languageMessage(code: String): String =
            "{\"type\":\"session.update\",\"session\":{\"audio\":{\"output\":{\"language\":\"$code\"}}}}"

        const val CLOSE_MESSAGE = "{\"type\":\"session.close\"}"
    }
}

/**
 * One live translation session: sound goes in, and its translation into one language comes back as
 * text and voice while the speaker is still talking. Two-way talk needs one per direction. Used on
 * `scope`'s thread only: what the socket says is handled there too.
 */
class LiveSession(
    private val apiKey: String,
    val language: String,
    private val scope: CoroutineScope,
    private val url: String = "wss://api.openai.com/v1/realtime/translations?model=gpt-realtime-translate",
) {
    companion object {
        /** The languages OpenAI's live translation can speak (any of 70 can be heard). */
        val outputLanguages = setOf("vi", "en", "zh", "ja", "ko", "es", "pt", "fr", "de", "ru", "it", "hi", "id")

        fun outputCode(language: Language): String? {
            val code = if (language.code == "zh-Hant") "zh" else language.code
            return if (code in outputLanguages) code else null
        }
    }

    /** Called on `scope`'s thread. */
    var onEvent: ((LiveEvent) -> Unit)? = null
    var isOpen = false
        private set
    private var socket: WebSocket? = null
    private var closing = false

    fun open() {
        if (socket != null) return
        val request = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build()
        closing = false
        isOpen = true
        val ws = Http.sockets.newWebSocket(request, Listener())
        socket = ws
        ws.send(LiveEvent.languageMessage(language))
    }

    /** 24 kHz mono PCM16, about 100 ms at a time. */
    fun send(pcm: ByteArray) {
        socket?.send(LiveEvent.appendMessage(pcm))
    }

    /** Asks for what is still on its way, then ends the session. */
    fun close() {
        val ws = socket ?: return
        closing = true
        isOpen = false
        ws.send(LiveEvent.CLOSE_MESSAGE)
        scope.launch {
            delay(3000)
            ws.close(1000, null)
        }
        socket = null
    }

    private fun deliver(event: LiveEvent) {
        scope.launch { onEvent?.invoke(event) }
    }

    private inner class Listener : WebSocketListener() {
        private fun current(ws: WebSocket, block: () -> Unit) {
            scope.launch { if (ws === socket || closing) block() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = LiveEvent.parse(text) ?: return
            current(webSocket) { onEvent?.invoke(event) }
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
                if (!wasClosing) {
                    // Refused at the start: a wrong key, or one without live translation.
                    when (status) {
                        401 -> onEvent?.invoke(LiveEvent.Failed("invalid_api_key", ""))
                        403, 404 -> onEvent?.invoke(LiveEvent.Failed("model_not_available", ""))
                        else -> onEvent?.invoke(LiveEvent.Failed("network", message))
                    }
                }
                onEvent?.invoke(LiveEvent.Closed)
            }
        }
    }
}

/**
 * Dịch liền is paid by the minute of sound sent, so a quiet room is not sent: sound goes to a
 * session while someone speaks, with the half second before (the first syllable) and the three
 * seconds after (so it finishes the sentence), and nothing while nobody speaks.
 */
class LiveGate {
    companion object {
        /** In chunks of a tenth of a second. */
        const val PREROLL = 5
        const val HOLD = 30
    }

    private val buffered = ArrayList<ByteArray>()
    private var left = 0

    /** The next tenth of a second, and whether someone is speaking in it; returns what to send now. */
    fun feed(chunk: ByteArray, speech: Boolean): List<ByteArray> {
        if (speech) {
            left = HOLD
            val out = ArrayList<ByteArray>(buffered)
            out.add(chunk)
            buffered.clear()
            return out
        }
        if (left > 0) {
            left -= 1
            return listOf(chunk)
        }
        buffered.add(chunk)
        while (buffered.size > PREROLL) buffered.removeAt(0)
        return emptyList()
    }

    fun reset() {
        buffered.clear()
        left = 0
    }
}

/**
 * Dịch liền's captions, cut into sentences. OpenAI's live translation sends what it heard and its
 * translation as two streams of pieces, saying neither where a sentence ends nor which sentence a
 * piece of translation belongs to; and a room that talks on never goes quiet for long, so one
 * caption ran on for minutes, several people in it. A caption is cut after a full stop once it is a
 * few seconds long (after a comma once it is long, anywhere once it is very long), and the
 * translation still coming for it goes to it (`awaiting`) until it has as many sentence ends as what
 * was said, or none has come for a while.
 */
class LiveCaptions {
    data class Caption(
        val number: Int,
        val text: String = "",
        val translation: String = "",
        /** When its first piece came (seconds, any clock). */
        val started: Double,
    )

    companion object {
        /** Nothing heard this long: the caption ends. */
        const val QUIET = 2.5
        /** A caption is cut after a full stop once it is this long; after a comma, and anywhere. */
        const val SHORTEST = 3.0
        const val LONG = 10.0
        const val LONGEST = 20.0
        /** No piece of its translation this long: an ended caption's translation is done. */
        const val TRANSLATION_QUIET = 3.0

        private const val SENTENCE_ENDS = ".?!。？！…"

        /** Sentence ends in `text` ("..." is one). */
        fun ends(text: String): Int {
            var count = 0
            var previous = false
            for (character in text) {
                val end = character in SENTENCE_ENDS
                if (end && !previous) count += 1
                previous = end
            }
            return count
        }

        /** Its translation has as many sentence ends as what was said. */
        fun isComplete(caption: Caption): Boolean {
            val said = ends(caption.text)
            return said > 0 && ends(caption.translation) >= said
        }
    }

    /** The caption being heard. */
    var current: Caption? = null
        private set
    /** Ended captions whose translation may still be coming, oldest first. */
    val awaiting: List<Caption> get() = waiting
    private val waiting = ArrayList<Caption>()
    private val released = ArrayList<Caption>()
    private var last = 0.0
    private val lastPiece = HashMap<Int, Double>()
    private var nextNumber = 1

    /** More of what was said; returns the caption it ended, if any. */
    fun heard(delta: String, now: Double): Caption? {
        var ended: Caption? = null
        val caption = current
        if (caption != null && cuts(caption, delta, now)) ended = finishCurrent(now)
        begin(now)
        current = current?.let { it.copy(text = it.text + delta) }
        last = now
        return ended
    }

    /** More of the translation; returns the number of the caption it went to. */
    fun translated(delta: String, now: Double): Int {
        while (waiting.isNotEmpty()) {
            val first = waiting[0]
            if (isComplete(first) || now - (lastPiece[first.number] ?: now) > TRANSLATION_QUIET) {
                release(first.number)
                continue
            }
            waiting[0] = first.copy(translation = first.translation + delta)
            lastPiece[first.number] = now
            return first.number
        }
        begin(now)
        current = current?.let { it.copy(translation = it.translation + delta) }
        last = now
        return current?.number ?: 0
    }

    /** Time passing: the caption being heard ends after a quiet moment; returns it. */
    fun tick(now: Double): Caption? {
        for (caption in waiting.toList()) {
            if (now - (lastPiece[caption.number] ?: now) > TRANSLATION_QUIET) release(caption.number)
        }
        if (current == null || now - last < QUIET) return null
        return finishCurrent(now)
    }

    /** Listening stops, or the session is gone: everything ends as it is; returns the caption heard. */
    fun finishAll(now: Double): Caption? {
        val ended = if (current == null) null else finishCurrent(now)
        while (waiting.isNotEmpty()) release(waiting[0].number)
        return ended
    }

    /** Captions whose translation is in (or stopped coming), since this was last asked. */
    fun takeReleased(): List<Caption> {
        val taken = released.toList()
        released.clear()
        return taken
    }

    private fun begin(now: Double) {
        if (current != null) return
        current = Caption(number = nextNumber, started = now)
        nextNumber += 1
    }

    private fun finishCurrent(now: Double): Caption? {
        val caption = current ?: return null
        current = null
        if (caption.text.any { it.isLetterOrDigit() } && !isComplete(caption)) {
            waiting.add(caption)
            lastPiece[caption.number] = now
            while (waiting.size > 2) release(waiting[0].number)
        } else {
            released.add(caption)
        }
        return caption
    }

    private fun release(number: Int) {
        val index = waiting.indexOfFirst { it.number == number }
        if (index < 0) return
        released.add(waiting.removeAt(index))
        lastPiece.remove(number)
    }

    /** A piece of speech after the caption's end of sentence (or clause, or anything, as it grows). */
    private fun cuts(caption: Caption, delta: String, now: Double): Boolean {
        if (delta.none { it.isLetterOrDigit() }) return false
        val end = caption.text.trim().lastOrNull() ?: return false
        val age = now - caption.started
        if (end in SENTENCE_ENDS) return age >= SHORTEST
        if (end in ",，、;；:：") return age >= LONG
        return age >= LONGEST
    }
}
