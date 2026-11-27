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
        dictionary.lookup(text, putTogether = false)?.let { return Result(it, Source.DICTIONARY) }
        synchronized(cache) { cache[key] ?: shaped(key) }?.let { return Result(it, Source.CACHE) }
        // Known words only, put together: after what was translated before (the AI's, which saw the line whole).
        dictionary.together(text)?.let { return Result(it, Source.DICTIONARY) }
        return null
    }

    /** Text read in a picture with a letter of a known word misread, put right (`CommerceDictionary.correct`). */
    fun corrected(text: String): String = dictionary.correct(text)

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
        // Vietnamese on the line (an address): the translator would mangle it. Only the Chinese is translated.
        if (VIETNAMESE.containsMatchIn(simplified)) return inPieces(simplified)?.best?.let { remember(text, Result(it, Source.CHUNKS)) }
        val (replaced, used) = substitute(simplified)
        if (!ChineseText.hasHan(replaced)) return remember(text, Result(tidy(replaced), Source.DICTIONARY))
        // An answer good but for a word of English or pinyin left in it (a name: "Sima An"): kept,
        // and shown if nothing better comes. A line left in Chinese tells the user nothing.
        var rough: String? = null
        suspend fun engine(input: String, kept: List<String>): String? {
            val answer = ask(input)?.let { fix(simplified, it) } ?: return null
            return when (verdict(simplified, answer, kept)) {
                Verdict.GOOD -> answer
                Verdict.LEFT_OVER -> {
                    if (rough == null) rough = answer
                    null
                }
                Verdict.BAD -> null
            }
        }
        engine(replaced, used)?.let { return remember(text, Result(it, Source.ENGINE)) }
        // The translator lost a word put in for it: once more with only the words that must be kept
        // (the user's glossary, the required terms), the rest left for it to translate.
        val (plainer, kept) = substitute(simplified, onlyRequired = true)
        if (plainer != replaced) engine(plainer, kept)?.let { return remember(text, Result(it, Source.ENGINE)) }
        // It keeps none of the words put in: the sentence as it is, in the translator's own words
        // (still checked: its numbers, no Chinese left, and never one account action for the other).
        // A sentence left in Chinese because 运费 did not come out as the list has it helps nobody.
        if (simplified != plainer) engine(simplified, emptyList())?.let { return remember(text, Result(it, Source.ENGINE)) }
        // Every word of it is in the dictionary, only in the Chinese order: better than pieces.
        dictionary.compose(ChineseText.key(simplified), strict = false)?.let { return remember(text, Result(it, Source.DICTIONARY)) }
        // Things set apart by spaces (a shop's name between "7年老店" and "进店"): each on its own.
        phrases(simplified)?.let { parts -> inPhrases(parts)?.let { return remember(text, Result(it, Source.CHUNKS)) } }
        val pieces = inPieces(simplified)
        pieces?.strict?.takeUnless { ChineseText.hasHan(it) }?.let { return remember(text, Result(it, Source.CHUNKS)) }
        rough?.let { return remember(text, Result(it, Source.ENGINE)) }
        return pieces?.best?.let { remember(text, Result(it, Source.CHUNKS)) }
    }

    /** Each phrase of a line on its own, joined again; null when one of them stays in Chinese. */
    private suspend fun inPhrases(parts: List<String>): String? {
        val done = coroutineScope {
            parts.map { part -> async { if (!ChineseText.hasHan(part)) part else translate(part)?.text } }.awaitAll()
        }
        if (done.any { it == null || ChineseText.hasHan(it) }) return null
        return join(parts, done.map { it!! })
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
        if (!trusted(ChineseText.toSimplified(source.trim()), clean, emptyList(), fromEngine = from == Source.ENGINE)) return null
        return remember(source, Result(clean, from))
    }

    /**
     * Lines of one screen. Each is translated offline first (on the screen at once). With `ai`, what
     * the dictionary does not know then goes to the AI in batches, once, and its answers replace the
     * offline ones; where it cannot (refused answer, limit, no network) the offline ones stay.
     * Returns the AI's error, if any, for the settings page.
     */
    suspend fun translateAll(texts: List<String>, ai: ScreenAi?, screen: List<String> = emptyList(), onResult: (String, Result?) -> Unit): String? {
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
                val answers = ai.translate(batch, screen)
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

    private enum class Verdict { GOOD, LEFT_OVER, BAD }

    private fun trusted(source: String, translation: String, used: List<String>, fromEngine: Boolean = true): Boolean =
        verdict(source, translation, used, fromEngine) == Verdict.GOOD

    /** BAD: never shown (Chinese left, a number changed, a word put in lost, an account action swapped). */
    private fun verdict(source: String, translation: String, used: List<String>, fromEngine: Boolean = true): Verdict {
        if (translation.isBlank() || ChineseText.hasHan(translation)) return Verdict.BAD
        if (!NumberGuard.kept(source, translation)) return Verdict.BAD
        val vietnamese = vietnamese(translation)
        if (used.any { vietnamese(it) !in vietnamese }) return Verdict.BAD
        if (dangerous(source, translation)) return Verdict.BAD
        return if (fromEngine && leftOver(source, translation)) Verdict.LEFT_OVER else Verdict.GOOD
    }

    /**
     * The offline translator's answer with the line's numbers as the line writes them ("0,01 g" is
     * "0.01g" again) and the slips it is known to make put right (秤, a scale for weighing, came out
     * "quy mô", the size of something).
     */
    private fun fix(source: String, answer: String): String {
        var out = NumberGuard.repair(source, answer.trim())
        for (slip in SLIPS) {
            if (slip.word !in source || slip.unless.any { it in source }) continue
            out = slip.wrong.replace(out) { match -> if (match.value[0].isUpperCase()) slip.right.replaceFirstChar { it.uppercaseChar() } else slip.right }
        }
        return out
    }

    /**
     * A line in pieces. `strict`: only answers with nothing left over, the rest in Chinese as it was.
     * `best`: also answers with a word of pinyin or English left in them (a shop's name), where nothing better came.
     */
    private class Pieces(val strict: String, val best: String)

    /** Each Chinese piece alone (side by side), the rest (numbers, codes, symbols) as written. */
    private suspend fun inPieces(simplified: String): Pieces? {
        val runs = ChineseText.runs(simplified)
        if (runs.count { it.second } == runs.size) return null
        // For each piece: the answer, and whether something was left over in it.
        val done = coroutineScope {
            runs.map { (piece, han) ->
                async {
                    if (!han) null else quick(piece)?.text?.let { it to false } ?: run {
                        val (replaced, used) = substitute(piece)
                        if (!ChineseText.hasHan(replaced)) replaced.replace(MARK.toString(), "") to false
                        else ask(replaced)?.let { fix(piece, it) }?.let { answer ->
                            when (verdict(piece, answer, used)) {
                                Verdict.GOOD -> answer to false
                                Verdict.LEFT_OVER -> answer to true
                                Verdict.BAD -> null
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        if (done.none { it != null }) return null
        fun join(loose: Boolean): String {
            val out = StringBuilder()
            for ((i, run) in runs.withIndex()) {
                val known = done[i]?.takeIf { loose || !it.second }?.first
                if (known == null) out.append(run.first) else out.append(MARK).append(known).append(MARK)
            }
            return tidy(out.toString())
        }
        return Pieces(join(false), join(true))
    }

    companion object {
        /** Where a Vietnamese piece meets the text around it: a space goes there when needed. */
        internal const val MARK = '\u0000'

        /**
         * The offline translator goes through English, and leaves some behind ("Màu Sắc Jing Note",
         * "BLU-RAY"): a word that is no Vietnamese syllable, not written in the line, and not a word
         * Vietnamese shops borrow, means the translation is not to be trusted.
         */
        fun leftOver(source: String, translation: String): Boolean {
            val written = source.lowercase()
            return LATIN_WORD.findAll(translation).any { match ->
                val word = match.value.lowercase()
                word.length >= 2 && word !in written && word !in ALLOWED &&
                    !vn.mocsinh.mstrans.core.telex.SyllableValidator.isValidSyllable(word, teencode = false)
            }
        }

        private val LATIN_WORD = Regex("\\p{IsLatin}+")
        private val ALLOWED: Set<String> = IndustryTerms.BORROWED.map { it.lowercase() }.toSet() + IndustryTerms.LOANWORDS + (
            "size ship freeship shop sale online offline video live voucher combo set mini max pro plus ultra smart wifi wi fi " +
                "bluetooth usb type led lcd oled ram rom cpu gpu app game gaming mode mm cm kg ml mah inch ok hd sim nfc gps ai pc tv " +
                "gb tb mb kb hz ios android iphone ipad laptop tablet email logo tmall taobao alipay wechat jd sf ems vip"
            ).split(' ').toSet()

        /** Letters only Vietnamese uses (ă â đ ê ô ơ ư, and the tones). */
        internal val VIETNAMESE = Regex("[ăâđêôơưĂÂĐÊÔƠƯàáảãạằắẳẵặầấẩẫậèéẻẽẹềếểễệìíỉĩịòóỏõọồốổỗộờớởỡợùúủũụừứửữựỳýỷỹỵ]")
        /** Where a number goes in a sentence kept without its numbers. */
        private const val HOLE = '\u0001'
        private val NUMBER = Regex("\\d+(?:[.,:]\\d+)*")
        private val SPACES = Regex("\\s+")

        private val LOG_OUT = listOf("退出登录", "退出当前账户", "退出当前账号", "退出账号", "退出账户")

        /** A word the offline translator gets wrong through English, unless the line also has a word that means what it wrote. */
        private class Slip(val word: String, val unless: List<String>, wrong: String, val right: String) {
            val wrong = Regex("(?<!\\p{L})(?:$wrong)(?!\\p{L})", setOf(RegexOption.IGNORE_CASE))
        }

        private val SLIPS = listOf(
            // 秤 "scale": a scale for weighing, not the size of something nor a scale on a map.
            Slip("秤", listOf("规模", "比例", "比率", "刻度", "平衡"), "quy mô|thang đo|tỷ lệ|tỉ lệ|cân bằng", "cân"),
            Slip("称重", listOf("规模", "比例"), "quy mô|tỷ lệ|tỉ lệ", "cân"),
            // 天平 "balance": the small precise scale, not a bank balance.
            Slip("天平", listOf("平衡", "余额"), "cân bằng|số dư|thăng bằng", "cân tiểu ly"),
            // 衡器 "weighing apparatus".
            Slip("衡器", listOf("平衡"), "cân bằng", "thiết bị cân"),
            // 店铺 "store": a shop, not keeping something (Taobao, 27/11/2026: "Lưu trữ cân điện tử").
            Slip("店铺", listOf("存储", "储存", "保存", "内存"), "lưu trữ", "cửa hàng"),
            // 加装 "fitted in addition", not "plus".
            Slip("加装", emptyList(), "cộng với|cộng thêm", "lắp thêm"),
            // 元: shops write "tệ".
            Slip("元", listOf("人民币"), "nhân dân tệ", "tệ"),
        )

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
