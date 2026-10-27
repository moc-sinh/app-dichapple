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
    /** `<fin>`: the turn was ended on purpose (the button let go, listening stopped). */
    val isFin: Boolean get() = text == "<fin>"
}

/** What a Soniox session sends back, and what it is sent. */
sealed class SonioxEvent {
    data class Tokens(val tokens: List<SonioxToken>) : SonioxEvent()
    /**
     * How much of the sound sent Soniox has turned into settled words, and into words at all (settled or
     * guessed), in milliseconds: told with each message, before its tokens.
     */
    data class Processed(val finalMs: Int, val totalMs: Int) : SonioxEvent()
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
            val final = o["final_audio_proc_ms"].int()
            val total = o["total_audio_proc_ms"].int()
            if (final != null && total != null) events.add(Processed(final, total))
            val list = o["tokens"].arr()
            val tokens = (list ?: emptyList()).mapNotNull { element ->
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
            // An empty list too: Soniox's guesses, sent again whole in every message, are gone (taken back).
            val finished = o["finished"].bool() == true
            if (list != null && !(tokens.isEmpty() && finished)) events.add(Tokens(tokens))
            if (finished) events.add(Finished)
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
 * Soniox's sentences ran into one another, several speakers and languages in one. Soniox's end of
 * sentence ends one only after a full stop (or a question mark…): one in the middle of a thought
 * ("如果你们订五百台，… 我们可以给百分之五") is a pause, and what follows is the same sentence; the model
 * ends it after a quiet moment. A word or two of another speaker in the middle of a sentence (a word
 * Soniox gave to the wrong person, or "对" said over the speaker) cuts it only once that speaker has
 * said three syllables; when the first speaker goes on, they stay in it.
 *
 * Soniox sends what was said and its translation as one stream, each piece of translation right after
 * the words it translates (its docs; its own SDK prints a piece under the words before it), and settles
 * the stream from its start. So a piece of translation belongs to the sentence of the words just
 * before it in the stream: a sentence cut at its full stop still gets the translation of its last words
 * (`awaiting`, shown as a preview), and the next sentence's words settling mean that translation is all in.
 *
 * A sentence's language is the one most of its syllables were said in, not its first word's: "Dạ,
 * 王总，我们下周一确认订单" is Chinese, and "OK，那我们下周一确认" too.
 */
class SonioxTranscript(
    /** What it is translated into. */
    val target: String,
) {
    class Sentence(
        val text: String = "",
        val translation: String = "",
        /** The language most of it was said in, empty when not told. */
        val language: String = "",
        /** Who said it (Soniox's speaker number), empty when not told. */
        val speaker: String = "",
        /** Counts the session's sentences, so a finished one can be found again. */
        val number: Int = 0,
        /** Where its words begin and end in the session's sound (milliseconds), when told. */
        val startMs: Int? = null,
        val endMs: Int? = null,
        /**
         * Where the words its translation has come for so far end (each piece of translation follows the
         * words it translates): the words after it are still untranslated.
         */
        val coveredMs: Int? = null,
    ) {
        val isEmpty: Boolean get() = text.isEmpty() && translation.isEmpty()

        fun copy(text: String = this.text, translation: String = this.translation, coveredMs: Int? = this.coveredMs) =
            Sentence(text, translation, language, speaker, number, startMs, endMs, coveredMs)

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
        var coveredMs: Int? = null
        /** Syllables said in each language. */
        val syllables = HashMap<String, Int>()
        val isEmpty get() = text.isEmpty() && translation.isEmpty()

        fun clear() {
            text.setLength(0)
            translation.setLength(0)
            language = ""
            speaker = ""
            startMs = null
            endMs = null
            coveredMs = null
            syllables.clear()
        }

        /** The translation has come for the words up to `end`. */
        fun cover(end: Int?) {
            if (end != null) coveredMs = maxOf(coveredMs ?: end, end)
        }

        fun copy() = Builder().also {
            it.text.append(text)
            it.translation.append(translation)
            it.language = language
            it.speaker = speaker
            it.number = number
            it.startMs = startMs
            it.endMs = endMs
            it.coveredMs = coveredMs
            it.syllables.putAll(syllables)
        }

        fun sentence() = Sentence(text.toString(), translation.toString(), language, speaker, number, startMs, endMs, coveredMs)
    }

    /** Cut by speaker, language and sentence as it is said (see above). */
    var splits = false
    private val settled = Builder()
    private val guess = Builder()
    /**
     * `splits`: settled words of another speaker in the middle of a sentence, not yet three syllables,
     * and the settled translation that came after them (theirs, wherever they go).
     */
    private val switching = ArrayList<SonioxToken>()
    private val switchingTranslation = StringBuilder()
    private var switchingCovered: Int? = null
    /**
     * The speaker finished the sentence (without `splits`), or paused in the middle of one (with
     * `splits`: no full stop yet); its translation may still be on its way.
     */
    var ended = false
        private set
    /** `splits`: finished sentences whose translation may still be coming, and those whose last came now. */
    private val awaiting = ArrayList<Sentence>()
    private val released = ArrayList<Sentence>()
    /** Soniox's guess at the translation of a finished sentence, and which one. */
    private val awaitingGuess = StringBuilder()
    private var awaitingGuessNumber = 0
    private var awaitingGuessCovered: Int? = null
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
        get() {
            val sentence = settled.copy()
            for (word in switching) add(word, sentence)
            sentence.translation.append(switchingTranslation)
            sentence.cover(switchingCovered)
            sentence.text.append(guess.text)
            sentence.translation.append(guess.translation)
            sentence.cover(guess.coveredMs)
            for ((language, count) in guess.syllables) sentence.syllables.merge(language, count, Int::plus)
            sentence.language = majority(sentence.syllables, sentence.language.ifEmpty { guess.language })
            if (sentence.speaker.isEmpty()) sentence.speaker = guess.speaker
            if (sentence.startMs == null) sentence.startMs = guess.startMs
            guess.endMs?.let { sentence.endMs = it }
            return sentence.sentence()
        }

    /** One message's tokens; returns the sentences it finished. */
    fun take(tokens: List<SonioxToken>): List<Sentence> {
        val finished = ArrayList<Sentence>()
        // The guesses come again whole in every message.
        guess.clear()
        awaitingGuess.setLength(0)
        awaitingGuessCovered = null
        released.clear()
        // A guessed word came in this message: guessed translation after it is of the words being said.
        var guessing = false
        // Where the last word of this message ends: a piece of translation after it has come for it.
        var saidEnd: Int? = null
        for (token in tokens) {
            if (token.endsSentence) {
                if (splits) endSpoken(token.isFin, finished) else ended = true
                continue
            }
            if (token.isTranslation) {
                addTranslation(token, guessing, saidEnd)
                continue
            }
            token.endMs?.let { saidEnd = it }
            if (token.isFinal) token.endMs?.let { settledEnd = it / 1000.0 }
            if (splits) {
                if (token.isFinal) {
                    settleSplit(token, finished)
                } else {
                    add(token, guess)
                    guessing = true
                }
                continue
            }
            // Words said after the end begin the next sentence.
            if (ended) finish()?.let { finished.add(it) }
            if (token.isFinal) {
                settle(token)
            } else {
                add(token, guess)
                guessing = true
            }
        }
        // Without `splits`, an ended sentence is done once nothing of it is guessed and its translation
        // is in (or it needs none: said in the language it is translated into).
        if (!splits) {
            val translated = settled.translation.isNotEmpty() || settled.language == target || settled.text.isBlank()
            if (ended && guess.isEmpty && translated) finish()?.let { finished.add(it) }
        }
        return finished
    }

    /** The finished sentences still getting Soniox's translation, and those whose last piece just came. */
    val previews: List<Sentence>
        get() = (released + awaiting).map { sentence ->
            if (sentence.number != awaitingGuessNumber) return@map sentence.copy(translation = sentence.translation.trim())
            val covered = awaitingGuessCovered?.let { maxOf(sentence.coveredMs ?: it, it) } ?: sentence.coveredMs
            sentence.copy(translation = (sentence.translation + awaitingGuess).trim(), coveredMs = covered)
        }

    /** Something shown is Soniox's guess (words or translation): a message without it takes it back. */
    val showsGuess: Boolean get() = !guess.isEmpty || awaitingGuess.isNotEmpty()

    /**
     * Words of the sentence being said are still Soniox's guess: its speaker goes on (or someone else
     * begins). A pause is not ended until they settle: ended with them, the words came again as the next
     * sentence once settled.
     */
    val hasGuess: Boolean get() = guess.text.isNotBlank()

    /**
     * The user began to speak (Tôi nói): the room's speaker has stopped, as at Soniox's end of a sentence,
     * which Soniox does not send while the user talks on (it hears their turn too). Not while words of it
     * are still guessed: they settle first.
     */
    fun endHere(): List<Sentence> {
        if (!splits || hasGuess) return emptyList()
        val finished = ArrayList<Sentence>()
        endSpoken(false, finished)
        return finished
    }

    /** Ends the sentence being said, with what is known of it (null when nothing is). */
    fun finish(): Sentence? {
        // A word or two of another speaker at its very end stay in it.
        keepSwitching()
        val sentence = current
        val settledTranslation = settled.translation.toString()
        val settledCovered = settled.coveredMs
        settled.clear()
        guess.clear()
        ended = false
        nextNumber += 1
        settled.number = nextNumber
        val trimmed = sentence.copy(text = sentence.text.trim(), translation = sentence.translation.trim())
        if (trimmed.isEmpty) return null
        // The translation of its last words may still come (unless none is needed). Only what Soniox
        // settled is kept: its guess at the rest comes again in its next message, settled or guessed.
        if (splits && owner == trimmed.number && trimmed.text.isNotEmpty() && trimmed.language != target) {
            awaiting.add(trimmed.copy(translation = settledTranslation.trim(), coveredMs = settledCovered))
            while (awaiting.size > 2) awaiting.removeAt(0)
        }
        return trimmed
    }

    /**
     * Ends the sentence with its settled words only, Soniox's guesses at the rest left out: the session
     * is gone, and the sound of those words is sent again to the next one.
     */
    fun finishSettled(): Sentence? {
        guess.clear()
        return finish()
    }

    /**
     * A settled word, with `splits`: it may end the sentence before it (another speaker, another
     * language, a long speech's full stop), or wait to tell a word or two of another speaker.
     */
    private fun settleSplit(token: SonioxToken, finished: MutableList<Sentence>) {
        val wordless = token.text.none { it.isLetterOrDigit() }
        if (switching.isNotEmpty()) {
            if (token.speaker == switching[0].speaker || wordless) return hold(token, finished)
            if (token.speaker.isEmpty() || token.speaker == settled.speaker) {
                // The speaker goes on: the few words were theirs, or said over them; they stay in it.
                keepSwitching()
            } else {
                confirmSwitch(finished)
            }
        }
        val said = settled.text.toString().trim()
        if (said.isNotEmpty() && !wordless) {
            if (token.speaker.isNotEmpty() && settled.speaker.isNotEmpty() && token.speaker != settled.speaker) {
                // Someone else, after a sentence or a clause (or after Soniox's end of one): now. In the
                // middle of one: once they have said a few syllables.
                if (!ended && said.last() !in CLAUSE_ENDS) return hold(token, finished)
                finish()?.let { finished.add(it) }
            } else if (startsAnotherLanguage(token)) {
                finish()?.let { finished.add(it) }
            }
        }
        ended = false
        settle(token)
        if (endsLongSpeech(token)) finish()?.let { finished.add(it) }
    }

    /** A settled word of another speaker in the middle of a sentence: held until they have said a few syllables, then theirs. */
    private fun hold(token: SonioxToken, finished: MutableList<Sentence>) {
        switching.add(token)
        if (switching.sumOf { TranscriptFilter.syllableCount(it.text) } < SWITCH_SYLLABLES) return
        confirmSwitch(finished)
        if (endsLongSpeech(token)) finish()?.let { finished.add(it) }
    }

    /** The words held were the speaker's own, or said over them: they stay in the sentence. */
    private fun keepSwitching() {
        for (word in switching) settle(word)
        settled.translation.append(switchingTranslation)
        settled.cover(switchingCovered)
        switching.clear()
        switchingTranslation.setLength(0)
        switchingCovered = null
    }

    /**
     * The few words of another speaker were theirs: the sentence before ends without them, and they begin
     * the next, with the translation that came after them.
     */
    private fun confirmSwitch(finished: MutableList<Sentence>) {
        val words = ArrayList(switching)
        val translation = switchingTranslation.toString()
        val covered = switchingCovered
        switching.clear()
        switchingTranslation.setLength(0)
        switchingCovered = null
        if (words.isEmpty()) return
        finish()?.let { finished.add(it) }
        for (word in words) settle(word)
        settled.translation.append(translation)
        settled.cover(covered)
    }

    /**
     * Soniox's end of sentence, with `splits`: after a full stop, or a turn ended on purpose (`<fin>`),
     * the sentence is done; in the middle of a thought, it is a pause.
     */
    private fun endSpoken(fin: Boolean, finished: MutableList<Sentence>) {
        if (switching.isNotEmpty()) confirmSwitch(finished)
        val said = settled.text.toString().trim()
        if (said.isEmpty()) return
        if (fin || endsSentence(said)) finish()?.let { finished.add(it) } else ended = true
    }

    private fun settle(token: SonioxToken) {
        add(token, settled)
        // Settled words of this sentence: what came before them, translations too, is settled.
        owner = settled.number
        released.addAll(awaiting)
        awaiting.clear()
    }

    /**
     * Another language after a sentence or a clause of some length. A foreign word inside a sentence
     * ("好 OK 的", "gửi em cái quotation nhé") stays in it: no full stop or comma before it.
     */
    private fun startsAnotherLanguage(token: SonioxToken): Boolean {
        val said = settled.text.toString().trim()
        if (token.language.isEmpty() || settled.language.isEmpty() || token.language == settled.language) return false
        val last = said.lastOrNull() ?: return false
        return last in CLAUSE_ENDS && TranscriptFilter.syllableCount(said) >= 4
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
     * A piece of translation: of the words just before it. After words held (another speaker's few, the
     * last settled): theirs, wherever they go. A settled piece follows the words settled last (`owner`),
     * which may be a sentence already cut; a guessed one, the words guessed before it in this message, or
     * else the settled ones. Without `splits`, the sentence being said. `saidEnd`: where the last word
     * before it in this message ends (none: the words it translates came in an earlier message, and end
     * where its sentence's settled words do).
     */
    private fun addTranslation(token: SonioxToken, guessing: Boolean, saidEnd: Int?) {
        if (switching.isNotEmpty()) {
            if (token.isFinal) {
                switchingTranslation.append(token.text)
                switchingCovered = saidEnd ?: switching.last().endMs
            } else {
                add(token, guess)
                guess.cover(saidEnd ?: switching.last().endMs)
            }
            return
        }
        val number = if (!splits || guessing || owner == 0) settled.number else owner
        if (number != settled.number) {
            // A cut sentence's: the one waiting for it (one let go of is gone, and the piece with it).
            val index = awaiting.indexOfFirst { it.number == number }
            if (index < 0) return
            val waiting = awaiting[index]
            val covered = saidEnd ?: waiting.endMs
            if (token.isFinal) {
                val reached = covered?.let { maxOf(waiting.coveredMs ?: it, it) } ?: waiting.coveredMs
                awaiting[index] = waiting.copy(translation = waiting.translation + token.text, coveredMs = reached)
            } else {
                awaitingGuess.append(token.text)
                awaitingGuessNumber = number
                awaitingGuessCovered = covered
            }
            return
        }
        if (token.isFinal) {
            add(token, settled)
            settled.cover(saidEnd ?: settled.endMs)
        } else {
            add(token, guess)
            guess.cover(saidEnd ?: guess.endMs ?: settled.endMs)
        }
    }

    private fun add(token: SonioxToken, sentence: Builder) {
        if (token.isTranslation) {
            sentence.translation.append(token.text)
            return
        }
        sentence.text.append(token.text)
        val syllables = TranscriptFilter.syllableCount(token.text)
        if (syllables > 0 && token.language.isNotEmpty()) sentence.syllables.merge(token.language, syllables, Int::plus)
        sentence.language = majority(sentence.syllables, sentence.language.ifEmpty { token.language })
        if (sentence.speaker.isEmpty()) sentence.speaker = token.speaker
        if (sentence.startMs == null) sentence.startMs = token.startMs
        token.endMs?.let { sentence.endMs = it }
    }

    companion object {
        private const val CLAUSE_ENDS = ".?!。？！…,，、;；:："
        /** Syllables another speaker says in the middle of a sentence before it is theirs. */
        private const val SWITCH_SYLLABLES = 3

        fun endsSentence(text: String): Boolean {
            val last = text.trim().lastOrNull() ?: return false
            return last in ".?!。？！…"
        }

        /** The language most syllables were said in; on a tie, the one it had. */
        fun majority(syllables: Map<String, Int>, first: String): String {
            var best = first
            var most = syllables[first] ?: 0
            for ((language, count) in syllables.toSortedMap()) {
                if (count > most) {
                    best = language
                    most = count
                }
            }
            return best
        }
    }
}

/**
 * The user's own voice in what the room's Soniox session hears. The room is listened to during the
 * user's turns (Tôi nói) too, so a partner who answers meanwhile, or finishes a sentence as the button
 * goes down, is not lost; the user's words, which their own session translates, are taken out. In a turn
 * only a voice known to be a partner's passes: one heard saying a few syllables in another language than
 * Vietnamese outside the user's turns, more than it said in Vietnamese in them (a partner's "vâng", or the
 * user's first word given a partner's number, no longer made that partner the user for the rest of the
 * meeting).
 */
class MyVoiceFilter {
    private class Turn(val from: Double, var to: Double = Double.POSITIVE_INFINITY)

    /** The user's turns, in seconds of the room's sound (the last one open while it lasts). */
    private val turns = ArrayList<Turn>()
    /**
     * Syllables each of the room's voices (Soniox's speaker numbers) said in Vietnamese in the user's
     * turns, and in another language outside them.
     */
    private val mine = HashMap<String, Int>()
    private val theirs = HashMap<String, Int>()
    /** The last settled word was taken out: the settled translation after it goes too. */
    private var droppingSettled = false

    fun began(at: Double) {
        if (turns.lastOrNull()?.to == Double.POSITIVE_INFINITY) return
        turns.add(Turn(at))
        if (turns.size > 20) turns.removeAt(0)
    }

    fun ended(at: Double) {
        for (turn in turns) if (turn.to == Double.POSITIVE_INFINITY) turn.to = at
    }

    /** The room's session was opened again: Soniox numbers its speakers anew. */
    fun newSession() {
        mine.clear()
        theirs.clear()
        droppingSettled = false
    }

    /**
     * The room's tokens without the user's words said in their turns, and the translation of those words
     * (`origin`: where this session's sound begins in the turns' seconds).
     */
    fun filter(tokens: List<SonioxToken>, origin: Double): List<SonioxToken> {
        if (turns.isEmpty()) {
            for (token in tokens) learn(token)
            return tokens
        }
        val kept = ArrayList<SonioxToken>()
        var dropping = droppingSettled
        for (token in tokens) {
            when {
                token.endsSentence -> kept.add(token)
                token.isTranslation -> if (!dropping) kept.add(token)
                else -> {
                    dropping = isMine(token, origin)
                    if (token.isFinal) droppingSettled = dropping
                    if (!dropping) kept.add(token)
                }
            }
        }
        return kept
    }

    private fun isMine(token: SonioxToken, origin: Double): Boolean {
        val start = token.startMs ?: run {
            learn(token)
            return false
        }
        val at = origin + start / 1000.0
        if (turns.none { at >= it.from - MARGIN && at <= it.to + MARGIN }) {
            learn(token)
            return false
        }
        if (token.language == Language.vietnamese.code) {
            if (token.isFinal && token.speaker.isNotEmpty()) mine.merge(token.speaker, TranscriptFilter.syllableCount(token.text), Int::plus)
            return true
        }
        val said = theirs[token.speaker] ?: 0
        return token.speaker.isEmpty() || said < KNOWN || said <= (mine[token.speaker] ?: 0)
    }

    /** A word outside the user's turns: a voice heard in another language is a partner's. */
    private fun learn(token: SonioxToken) {
        if (!token.isFinal || token.isTranslation || token.speaker.isEmpty() || token.language.isEmpty() ||
            token.language == Language.vietnamese.code
        ) return
        theirs.merge(token.speaker, TranscriptFilter.syllableCount(token.text), Int::plus)
    }

    companion object {
        /** Sound around a turn that is still the user's: the button pressed a moment after they began, and how far Soniox's times are off. */
        const val MARGIN = 0.3
        /** Syllables a voice says in another language outside the user's turns before it is a partner's. */
        const val KNOWN = 3
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
