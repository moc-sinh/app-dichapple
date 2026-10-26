package vn.mocsinh.mstrans.core.conversation

import java.text.Normalizer

/**
 * Text a speech recognizer writes without anyone having said it: channel sign-offs ("Cảm ơn các
 * bạn đã theo dõi", "Thanks for watching", "字幕由…提供"), one syllable many times, or more words
 * than the sound could hold. Such text is dropped before it is translated.
 */
object TranscriptFilter {
    fun isNoise(text: String, seconds: Double): Boolean {
        val trimmed = text.trim()
        if (trimmed.none { it.isLetterOrDigit() }) return true
        return isRepetitive(trimmed) || isImplausiblyFast(trimmed, seconds) || isKnownPhantom(trimmed)
    }

    /** One short piece over and over ("ha ha ha ha ha", "的的的的的的") making up most of the text. */
    fun isRepetitive(text: String): Boolean {
        val letters = text.lowercase().filter { it.isLetterOrDigit() }
        if (letters.length < 12) return false
        var longest = 0
        for (unit in 1..12) {
            if (unit * 4 > letters.length) break
            var start = 0
            while (start + unit * 4 <= letters.length) {
                var repeats = 1
                while (start + (repeats + 1) * unit <= letters.length &&
                    letters.regionMatches(start + repeats * unit, letters, start, unit)
                ) repeats++
                if (repeats >= 4) longest = maxOf(longest, repeats * unit)
                start += if (repeats >= 4) repeats * unit else 1
            }
        }
        return longest.toDouble() / letters.length > 0.6
    }

    /**
     * More syllables than the sound could hold: nobody says eleven a second for long (a fast Chinese
     * speaker says eight, which eight as the limit took for noise and dropped).
     */
    fun isImplausiblyFast(text: String, seconds: Double): Boolean {
        val syllables = syllableCount(text)
        return syllables >= 8 && syllables > 11 * maxOf(0.5, seconds - 0.35) + 1
    }

    /** Words, except in Chinese and Japanese, where each character is a syllable. */
    fun syllableCount(text: String): Int {
        var count = 0
        var inWord = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (isIdeographic(cp)) {
                count++
                inWord = false
            } else if (Character.isAlphabetic(cp) || cp in '0'.code..'9'.code) {
                if (!inWord) count++
                inWord = true
            } else {
                inWord = false
            }
        }
        return count
    }

    internal fun isIdeographic(cp: Int): Boolean =
        cp in 0x3040..0x30FF || cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF

    /** Sign-offs of online videos, which recognizers write for silence. */
    fun isKnownPhantom(text: String): Boolean {
        val lower = text.lowercase()
        for (phrase in listOf(
            "字幕由", "字幕提供", "请不吝点赞", "订阅我的频道", "明镜与点点", "ご視聴ありがとうございました",
            "チャンネル登録", "시청해주셔서 감사합니다", "구독과 좋아요", "mbc 뉴스",
        )) if (lower.contains(phrase)) return true
        val words = folded(text)
        if (words.startsWith("cam on cac ban da theo doi") || words.startsWith("de khong bo lo nhung video") ||
            words.contains("ung ho kenh")
        ) return true
        if ((words.contains("subscribe") || words.contains("dang ky") || words.contains("dang ki")) && words.contains("kenh")) {
            return true
        }
        return listOf(
            "thanks for watching", "thank you for watching", "please subscribe", "like and subscribe",
            "subscribe to my channel",
        ).any { words.contains(it) }
    }

    /** Lower case without marks (đ as d), words of plain letters and digits joined by one space. */
    fun folded(text: String): String {
        val plain = Normalizer.normalize(text.lowercase().replace("đ", "d"), Normalizer.Form.NFD)
        val words = ArrayList<String>()
        val word = StringBuilder()
        for (c in plain) {
            if (c in 'a'..'z' || c in '0'..'9') {
                word.append(c)
            } else if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) {
                continue
            } else if (word.isNotEmpty()) {
                words.add(word.toString())
                word.setLength(0)
            }
        }
        if (word.isNotEmpty()) words.add(word.toString())
        return words.joinToString(" ")
    }
}

/**
 * The phone's own voice heard again. When a translation is read aloud through the speaker, the
 * microphone hears it: a sentence said while the phone was saying the same words is that echo, not
 * someone speaking.
 *
 * Only while the phone speaks, and a moment after: a partner repeating what the phone said a few
 * seconds before ("好的，下周一确认订单" after it said "我们下周一确认订单") is confirming it, and was
 * lost. Compared by pairs of words (of characters, in Chinese and Japanese), not single ones: a
 * colleague's sentence of common words ("của chúng tôi", "我们的") was taken for its echo.
 */
class EchoGuard {
    private class Said(val from: Double, var to: Double, val words: Set<String>, val pairs: Set<String>)

    private val spoken = ArrayList<Said>()

    companion object {
        /** The phone's voice is heard while it plays and a moment after (the room's echo, and how far a recognizer's times are off). */
        const val TAIL = 1.5
        private const val KEPT = 60.0

        /** Lower-case words; in Chinese and Japanese, each character. */
        fun words(text: String): List<String> {
            val words = ArrayList<String>()
            val word = StringBuilder()
            val lower = text.lowercase()
            var i = 0
            while (i < lower.length) {
                val cp = lower.codePointAt(i)
                i += Character.charCount(cp)
                if (TranscriptFilter.isIdeographic(cp)) {
                    if (word.isNotEmpty()) { words.add(word.toString()); word.setLength(0) }
                    words.add(String(Character.toChars(cp)))
                } else if (Character.isAlphabetic(cp) || cp in '0'.code..'9'.code) {
                    word.appendCodePoint(cp)
                } else if (word.isNotEmpty()) {
                    words.add(word.toString())
                    word.setLength(0)
                }
            }
            if (word.isNotEmpty()) words.add(word.toString())
            return words
        }

        fun pairs(words: List<String>): List<String> = words.zipWithNext { a, b -> "$a $b" }
    }

    /** The phone begins saying `text` at `at` (seconds, the clock heard sentences are timed by), until `stopped`. */
    fun spoke(text: String, at: Double) {
        val words = words(text)
        if (words.isEmpty()) return
        spoken.add(Said(at, Double.POSITIVE_INFINITY, words.toSet(), pairs(words).toSet()))
        spoken.removeAll { at - it.to > KEPT }
    }

    /** The phone stopped speaking. */
    fun stopped(at: Double) {
        for (said in spoken) if (said.to == Double.POSITIVE_INFINITY) said.to = at
    }

    /** A sentence said from `from` to `to` (seconds): the phone's voice heard back, when said while the phone said the same words. */
    fun isEcho(text: String, from: Double, to: Double): Boolean {
        val heard = words(text)
        val during = spoken.filter { from <= it.to + TAIL && to >= it.from }
        if (heard.isEmpty() || during.isEmpty()) return false
        // A word or two: all of them said by the phone.
        if (heard.size < 3) {
            val said = HashSet<String>()
            for (s in during) said.addAll(s.words)
            return heard.all { it in said }
        }
        val said = HashSet<String>()
        for (s in during) said.addAll(s.pairs)
        val pairs = pairs(heard)
        val matched = pairs.count { it in said }
        // Half of a long sentence (what the recognizer heard of the phone's voice has a mistake here and
        // there), all of a short one: "这个呢" after the phone said "这个价格" is someone asking.
        return matched * 2 >= pairs.size && matched >= minOf(pairs.size, 3)
    }
}
