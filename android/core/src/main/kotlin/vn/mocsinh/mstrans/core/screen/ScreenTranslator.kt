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
 * 3. what was translated before (in memory only);
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

    fun clearCache() = synchronized(cache) { cache.clear() }

    /** Without the translator: glossary, dictionary, memory. Fast enough to draw at once. */
    fun quick(text: String): Result? {
        if (!ChineseText.hasHan(text)) return null
        val key = ChineseText.key(text)
        glossary[key]?.let { return Result(it.ifEmpty { text.trim() }, Source.GLOSSARY) }
        dictionary.lookup(key)?.let { return Result(it, Source.DICTIONARY) }
        synchronized(cache) { cache[key] }?.let { return Result(it, Source.CACHE) }
        return null
    }

    suspend fun translate(text: String): Result? {
        if (!ChineseText.hasHan(text)) return null
        quick(text)?.let { return it }
        val simplified = ChineseText.toSimplified(text.trim())
        val (replaced, used) = substitute(simplified)
        if (!ChineseText.hasHan(replaced)) return remember(text, Result(tidy(replaced), Source.DICTIONARY))
        val whole = ask(replaced)
        if (whole != null && trusted(simplified, whole, used)) return remember(text, Result(whole.trim(), Source.ENGINE))
        val pieces = inPieces(simplified) ?: return null
        return remember(text, Result(pieces, Source.CHUNKS))
    }

    /** An answer from elsewhere (the AI mode), put through the same checks. */
    fun accept(source: String, translation: String, from: Source = Source.AI): Result? {
        if (!ChineseText.hasHan(source)) return null
        val clean = translation.trim()
        if (!trusted(ChineseText.toSimplified(source.trim()), clean, emptyList())) return null
        return remember(source, Result(clean, from))
    }

    /**
     * Lines of one screen. With `ai`, what the dictionary and memory do not know goes to the AI in
     * batches; what it cannot do (refused answer, limit, no network) is translated offline. Returns
     * the AI's error, if any, for the settings page.
     */
    suspend fun translateAll(texts: List<String>, ai: ScreenAi?, onResult: (String, Result?) -> Unit): String? {
        val pending = ArrayList<String>()
        for (text in texts.distinct()) {
            val known = quick(text)
            when {
                known != null -> onResult(text, known)
                ChineseText.hasHan(text) -> pending.add(text)
                else -> onResult(text, null)
            }
        }
        var error: String? = null
        val offline = ArrayList<String>()
        if (ai == null) offline.addAll(pending) else for (batch in ScreenAi.batches(pending)) {
            if (error != null) {
                offline.addAll(batch)
                continue
            }
            try {
                val answers = ai.translate(batch)
                for (text in batch) {
                    val result = answers[text]?.let { accept(text, it, Source.AI) }
                    if (result != null) onResult(text, result) else offline.add(text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScreenAi.LimitReached) {
                error = "Đã tới giới hạn ${ai.limitPer10Minutes} lần gọi AI trong 10 phút, đang dịch ngoại tuyến."
                offline.addAll(batch)
            } catch (e: Exception) {
                error = "AI nâng cao lỗi (${e.message ?: "không rõ"}), đang dịch ngoại tuyến."
                offline.addAll(batch)
            }
        }
        coroutineScope { offline.map { text -> async { onResult(text, translate(text)) } }.awaitAll() }
        return error
    }

    // Steps

    private fun remember(text: String, result: Result): Result {
        synchronized(cache) { cache[ChineseText.key(text)] = result.text }
        return result
    }

    /** Known terms replaced by their Vietnamese (glossary first), with the Vietnamese ones used. */
    private fun substitute(text: String): Pair<String, List<String>> {
        var out = text
        val used = ArrayList<String>()
        for ((chinese, vietnamese) in glossaryTerms + dictionary.terms()) {
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

    /** Each Chinese piece alone, the rest (numbers, codes, symbols) as written. */
    private suspend fun inPieces(simplified: String): String? {
        val runs = ChineseText.runs(simplified)
        if (runs.count { it.second } == runs.size) return null
        var translated = 0
        val out = StringBuilder()
        for ((piece, han) in runs) {
            if (!han) {
                out.append(piece)
                continue
            }
            val known = quick(piece)?.text ?: run {
                val (replaced, used) = substitute(piece)
                if (!ChineseText.hasHan(replaced)) replaced.replace(MARK.toString(), "")
                else ask(replaced)?.takeIf { trusted(piece, it, used) }
            }
            if (known == null) out.append(piece) else {
                translated++
                out.append(MARK).append(known).append(MARK)
            }
        }
        return if (translated == 0) null else tidy(out.toString())
    }

    companion object {
        /** Where a Vietnamese piece meets the text around it: a space goes there when needed. */
        private const val MARK = '\u0000'

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
