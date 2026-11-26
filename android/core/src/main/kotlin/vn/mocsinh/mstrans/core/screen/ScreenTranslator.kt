package vn.mocsinh.mstrans.core.screen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import vn.mocsinh.mstrans.core.Glossary
import java.text.Normalizer

/** The offline translator (ML Kit on the phone, a stand-in in tests). Null: no translation. */
fun interface TextEngine {
    suspend fun translate(text: String): String?
}

/**
 * One line of the screen, Chinese to Vietnamese, in this order:
 * 1. the user's glossary (tab Thuật ngữ), whole line;
 * 2. the shopping dictionary and its number patterns, whole line;
 * 3. what was translated before (in memory only), also the same sentence seen with other numbers;
 *    and, where every phrase of a line whose phrases are set apart by spaces is known this way
 *    (a shop's lines are lists of them: "月销500+  现货"), the phrases joined with " · ";
 * 4. the offline translator, with known terms replaced by their Vietnamese first, and its answer
 *    checked: numbers as written, replaced terms still there, no Chinese left, and 注销 (delete the
 *    account) never "đăng xuất", 退出登录 (log out) never "xóa";
 * 5. failing that, the Chinese pieces one by one, everything else left as written;
 * 6. nothing trusted: null, the Chinese stays as it is.
 */
class ScreenTranslator(
    private val dictionary: CommerceDictionary = CommerceDictionary(),
    private val engine: TextEngine? = null,
    private val cacheSize: Int = 600,
    parallel: Int = 2,
) {
    enum class Source { GLOSSARY, DICTIONARY, CACHE, ENGINE, CHUNKS, AI }

    data class Result(val text: String, val source: Source)

    /** Simplified key → Vietnamese; empty means "keep as written" (a brand). */
    @Volatile private var glossary: Map<String, String> = emptyMap()
    @Volatile private var glossaryTerms: List<Pair<String, String>> = emptyList()

    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > cacheSize
    }
    /** Sentences seen with one number, for the same with another: both with their numbers taken out. */
    private val shapes = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > cacheSize
    }
    /** Lines the offline translator made (the AI may still better them) and lines the AI was asked; guarded by `cache`. */
    private val offline = HashSet<String>()
    private val asked = HashSet<String>()
    private val gate = Semaphore(parallel)

    val cached: Int get() = synchronized(cache) { cache.size }

    /**
     * The glossary of tab Thuật ngữ, "a = b" per line. Either side may be the Chinese one
     * ("báo giá = 报价" also reads 报价 as "báo giá"). Translations made before are forgotten.
     */
    fun setGlossary(text: String) {
        val map = LinkedHashMap<String, String>()
        for (entry in Glossary.parse(text)) {
            val leftHan = ChineseText.hasHan(entry.left)
            val rightHan = ChineseText.hasHan(entry.right)
            when {
                leftHan && !rightHan -> map[ChineseText.key(entry.left)] = entry.right
                rightHan && !leftHan -> map[ChineseText.key(entry.right)] = entry.left
            }
        }
        glossary = map
        glossaryTerms = map.filter { it.value.isNotEmpty() }.map { it.key to it.value }.sortedByDescending { it.first.length }
        clearCache()
    }

    fun clearCache() = synchronized(cache) {
        cache.clear()
        shapes.clear()
        offline.clear()
        asked.clear()
    }

    /** Without the translator: glossary, dictionary, memory, line or phrase by phrase. Fast enough to draw at once. */
    fun quick(text: String): Result? {
        if (!ChineseText.hasHan(text)) return null
        whole(text)?.let { return it }
        val parts = phrases(text) ?: return null
        var source = Source.DICTIONARY
        val done = parts.map { part ->
            if (!ChineseText.hasHan(part)) part
            else (whole(part) ?: return null).also { if (it.source == Source.CACHE) source = Source.CACHE }.text
        }
        return Result(join(parts, done), source)
    }

    /** A line or a phrase as it is: glossary, dictionary, memory. */
    private fun whole(text: String): Result? {
        val key = ChineseText.key(text)
        glossary[key]?.let { return Result(it.ifEmpty { text.trim() }, Source.GLOSSARY) }
        dictionary.lookup(key)?.let { return Result(it, Source.DICTIONARY) }
        synchronized(cache) { cache[key] ?: shaped(key) }?.let { return Result(it, Source.CACHE) }
        return null
    }

    /** A shorter word for a place too narrow for the usual one (a label under an icon); null: none. */
    fun shorter(text: String): String? {
        val key = ChineseText.key(text)
        // The user's own word for it is used as it is.
        return if (key in glossary) null else dictionary.short(key)
    }

    suspend fun translate(text: String): Result? {
        if (!ChineseText.hasHan(text)) return null
        quick(text)?.let { return it }
        // The line whole: the offline translator does worse on a phrase taken out of its line.
        val simplified = ChineseText.toSimplified(text.trim())
        val (replaced, used) = substitute(simplified)
        if (!ChineseText.hasHan(replaced)) return remember(text, Result(tidy(replaced), Source.DICTIONARY))
        val whole = ask(replaced)
        if (whole != null && trusted(simplified, whole, used)) return remember(text, Result(whole.trim(), Source.ENGINE))
        // The translator lost a word put in for it: once more with only the words that must be kept
        // (the user's glossary, the required terms), the rest left for it to translate.
        val (plainer, kept) = substitute(simplified, onlyRequired = true)
        if (plainer != replaced) {
            val again = ask(plainer)
            if (again != null && trusted(simplified, again, kept)) return remember(text, Result(again.trim(), Source.ENGINE))
        }
        val pieces = inPieces(simplified) ?: return null
        return remember(text, Result(pieces, Source.CHUNKS))
    }

    /** The phrases of a line set apart by spaces (Simplified); null: it is one phrase. */
    private fun phrases(text: String): List<String>? =
        ChineseText.toSimplified(text.trim()).split(SPACES).filter { it.isNotEmpty() }.takeIf { it.size > 1 }

    /** " · " between two phrases of Chinese, a space next to a number or a sign that stood alone. */
    private fun join(parts: List<String>, done: List<String>): String {
        val out = StringBuilder()
        for (i in parts.indices) {
            if (i > 0) out.append(if (ChineseText.hasHan(parts[i]) && ChineseText.hasHan(parts[i - 1])) " · " else " ")
            out.append(done[i])
        }
        return out.toString()
    }

    /** An answer from elsewhere (the AI mode), put through the same checks. */
    fun accept(source: String, translation: String, from: Source = Source.AI): Result? {
        if (!ChineseText.hasHan(source)) return null
        val clean = translation.trim()
        if (!trusted(ChineseText.toSimplified(source.trim()), clean, emptyList())) return null
        return remember(source, Result(clean, from))
    }

    /**
     * Lines of one screen. Each is translated offline first (on the screen at once). With `ai`, what
     * the dictionary does not know then goes to the AI in batches, once, and its answers replace the
     * offline ones; where it cannot (refused answer, limit, no network) the offline ones stay.
     * Returns the AI's error, if any, for the settings page.
     */
    suspend fun translateAll(texts: List<String>, ai: ScreenAi?, onResult: (String, Result?) -> Unit): String? {
        val pending = ArrayList<String>()
        val better = ArrayList<String>()
        for (text in texts.distinct()) {
            val known = quick(text)
            when {
                known != null -> {
                    onResult(text, known)
                    val key = ChineseText.key(text)
                    if (ai != null && synchronized(cache) { key in offline && key !in asked }) better.add(text)
                }
                ChineseText.hasHan(text) -> pending.add(text)
                else -> onResult(text, null)
            }
        }
        coroutineScope { pending.map { text -> async { onResult(text, translate(text)) } }.awaitAll() }
        if (ai == null) return null
        for (batch in ScreenAi.batches(pending + better)) {
            try {
                val answers = ai.translate(batch)
                synchronized(cache) { for (text in batch) asked.add(ChineseText.key(text)) }
                for (text in batch) answers[text]?.let { accept(text, it, Source.AI) }?.let { onResult(text, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScreenAi.LimitReached) {
                return "Đã tới giới hạn ${ai.limitPer10Minutes} lần gọi AI trong 10 phút, đang dịch ngoại tuyến."
            } catch (e: Exception) {
                return "AI nâng cao lỗi (${e.message ?: "không rõ"}), đang dịch ngoại tuyến."
            }
        }
        return null
    }

    // Steps

    private fun remember(text: String, result: Result): Result {
        val key = ChineseText.key(text)
        synchronized(cache) {
            cache[key] = result.text
            when (result.source) {
                Source.ENGINE, Source.CHUNKS -> offline.add(key)
                else -> offline.remove(key)
            }
            if (offline.size > cacheSize * 2) offline.clear()
            if (asked.size > cacheSize * 2) asked.clear()
            learn(key, result.text)
        }
        return result
    }

    /**
     * Keeps the sentence without its numbers, when the translation has the same numbers in the same
     * order and no other: "限购3台" → "Giới hạn mua 3 chiếc" also answers "限购12台".
     */
    private fun learn(key: String, translation: String) {
        val numbers = NUMBER.findAll(key).map { it.value }.toList()
        if (numbers.isEmpty()) return
        val out = StringBuilder()
        var from = 0
        for (number in numbers) {
            var at = translation.indexOf(number, from)
            while (at >= 0 && (translation.getOrNull(at - 1)?.isDigit() == true || translation.getOrNull(at + number.length)?.isDigit() == true)) {
                at = translation.indexOf(number, at + 1)
            }
            if (at < 0) return
            out.append(translation, from, at).append(HOLE)
            from = at + number.length
        }
        out.append(translation, from, translation.length)
        if (out.any { it.isDigit() }) return
        shapes[NUMBER.replace(key, "#")] = out.toString()
    }

    /** A sentence known with other numbers, with this one's put in. Under the lock of `cache`. */
    private fun shaped(key: String): String? {
        val numbers = NUMBER.findAll(key).map { it.value }.toList()
        if (numbers.isEmpty()) return null
        val shape = shapes[NUMBER.replace(key, "#")] ?: return null
        var next = 0
        return buildString { for (c in shape) if (c == HOLE) append(numbers[next++]) else append(c) }
    }

    /** Known terms replaced by their Vietnamese (glossary first), with the Vietnamese ones used. */
    private fun substitute(text: String, onlyRequired: Boolean = false): Pair<String, List<String>> {
        var out = text
        val used = ArrayList<String>()
        for ((chinese, vietnamese) in glossaryTerms + (if (onlyRequired) dictionary.required() else dictionary.terms())) {
            if (chinese.codePointCount(0, chinese.length) < 2 || chinese !in out) continue
            out = out.replace(chinese, MARK + vietnamese + MARK)
            used.add(vietnamese)
        }
        return out to used
    }

    private suspend fun ask(text: String): String? {
        val engine = engine ?: return null
        return try {
            gate.withPermit { engine.translate(text.replace(MARK.toString(), "")) }?.trim()?.ifEmpty { null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun trusted(source: String, translation: String, used: List<String>): Boolean {
        if (translation.isBlank() || ChineseText.hasHan(translation)) return false
        if (!NumberGuard.kept(source, translation)) return false
        val vietnamese = vietnamese(translation)
        if (used.any { vietnamese(it) !in vietnamese }) return false
        return !dangerous(source, translation)
    }

    /** Each Chinese piece alone (side by side), the rest (numbers, codes, symbols) as written. */
    private suspend fun inPieces(simplified: String): String? {
        val runs = ChineseText.runs(simplified)
        if (runs.count { it.second } == runs.size) return null
        val done = coroutineScope {
            runs.map { (piece, han) ->
                async {
                    if (!han) null else quick(piece)?.text ?: run {
                        val (replaced, used) = substitute(piece)
                        if (!ChineseText.hasHan(replaced)) replaced.replace(MARK.toString(), "")
                        else ask(replaced)?.takeIf { trusted(piece, it, used) }
                    }
                }
            }.awaitAll()
        }
        var translated = 0
        val out = StringBuilder()
        for ((i, run) in runs.withIndex()) {
            val known = done[i]
            if (known == null) out.append(run.first) else {
                translated++
                out.append(MARK).append(known).append(MARK)
            }
        }
        return if (translated == 0) null else tidy(out.toString())
    }

    companion object {
        /** Where a Vietnamese piece meets the text around it: a space goes there when needed. */
        private const val MARK = '\u0000'
        /** Where a number goes in a sentence kept without its numbers. */
        private const val HOLE = '\u0001'
        private val NUMBER = Regex("\\d+(?:[.,:]\\d+)*")
        private val SPACES = Regex("\\s+")

        private val LOG_OUT = listOf("退出登录", "退出当前账户", "退出当前账号", "退出账号", "退出账户")

        /** Lower case, one form of each accent ("xoá" is "xóa"). */
        fun vietnamese(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFC)
            .replace("xoá", "xóa").replace("huỷ", "hủy")

        /** The two account actions must never be swapped. `source` is Simplified. */
        fun dangerous(source: String, translation: String): Boolean {
            val vi = vietnamese(translation)
            if ("注销" in source && (("xóa" !in vi && "hủy" !in vi) || "đăng xuất" in vi)) return true
            if (LOG_OUT.any { it in source } && (("đăng xuất" !in vi && "thoát" !in vi) || "xóa" in vi)) return true
            return false
        }

        private val CHINESE_PUNCTUATION = mapOf(
            '，' to ", ", '。' to ". ", '：' to ": ", '；' to "; ", '！' to "! ", '？' to "? ", '、' to ", ",
            '（' to " (", '）' to ") ", '【' to " [", '】' to "] ",
        )
        private const val NO_SPACE_AFTER = "([{“‘"
        private const val NO_SPACE_BEFORE = ",.:;!?)]}”’…%"

        /** Spaces where Vietnamese meets other text, Chinese punctuation as Vietnamese writes it. */
        fun tidy(text: String): String {
            val out = StringBuilder()
            for ((i, c) in text.withIndex()) {
                if (c == MARK) {
                    val before = out.lastOrNull()
                    val after = text.drop(i + 1).firstOrNull { it != MARK }
                    if (before != null && after != null && !before.isWhitespace() && !after.isWhitespace() &&
                        before !in NO_SPACE_AFTER && after !in NO_SPACE_BEFORE && after !in CHINESE_PUNCTUATION.keys
                    ) out.append(' ')
                    continue
                }
                out.append(CHINESE_PUNCTUATION[c] ?: c.toString())
            }
            return out.toString().replace(Regex(" {2,}"), " ").replace(Regex(" ([,.:;!?)\\]])"), "$1").trim()
        }
    }
}
