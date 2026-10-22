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

    /** More syllables than the sound could hold: nobody says eight a second for long. */
    fun isImplausiblyFast(text: String, seconds: Double): Boolean {
        val syllables = syllableCount(text)
        return syllables >= 8 && syllables > 8 * maxOf(0.5, seconds - 0.35) + 1
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
 * The phone's own voice heard again: a sentence made of the words the phone said a moment ago is
 * that echo, not someone speaking.
 */
class EchoGuard {
    private class Said(val at: Double, val words: Set<String>)

    private val recent = ArrayList<Said>()

    companion object {
        private const val WINDOW = 15.0
        private const val SHORT_WINDOW = 5.0

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
    }

    /** The phone said `text` at `at` (seconds, any clock). */
    fun spoke(text: String, at: Double) {
        val found = words(text).toSet()
        if (found.isEmpty()) return
        recent.add(Said(at, found))
        recent.removeAll { at - it.at > WINDOW }
    }

    fun isEcho(text: String, at: Double): Boolean {
        val heard = words(text)
        if (heard.isEmpty()) return false
        val short = heard.size < 3
        val window = if (short) SHORT_WINDOW else WINDOW
        val said = HashSet<String>()
        for (s in recent) if (at - s.at <= window) said.addAll(s.words)
        if (said.isEmpty()) return false
        val matched = heard.count { it in said }
        return if (short) matched == heard.size else matched.toDouble() / heard.size >= 0.6
    }
}
