package vn.mocsinh.mstrans.core.screen

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * "AI nâng cao": lines the dictionary does not know go to OpenAI as text (never a picture), at most
 * 40 lines or 3,000 characters a request, one request at a time, and at most `limitPer10Minutes`
 * requests in any 10 minutes. `write` is `Translator.write` (instructions, input) → answer.
 */
class ScreenAi(
    private val write: suspend (String, String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    val limitPer10Minutes: Int = 30,
) {
    class LimitReached : Exception("Đã tới giới hạn yêu cầu AI")

    private val mutex = Mutex()
    private val sent = ArrayDeque<Long>()

    /** Requests left in the current 10 minutes. */
    val remaining: Int
        get() = synchronized(sent) {
            drop(clock())
            (limitPer10Minutes - sent.size).coerceAtLeast(0)
        }

    /** Chinese line → Vietnamese, for the lines the answer had. Throws `LimitReached` over the limit. */
    suspend fun translate(texts: List<String>): Map<String, String> = mutex.withLock {
        synchronized(sent) {
            val now = clock()
            drop(now)
            if (sent.size >= limitPer10Minutes) throw LimitReached()
            sent.addLast(now)
        }
        val byId = parse(write(INSTRUCTIONS, input(texts)))
        texts.withIndex().mapNotNull { (i, text) -> byId[i]?.let { text to it } }.toMap()
    }

    private fun drop(now: Long) {
        while (sent.isNotEmpty() && now - sent.first() > WINDOW_MS) sent.removeFirst()
    }

    companion object {
        const val MAX_ITEMS = 40
        const val MAX_CHARS = 3_000
        const val WINDOW_MS = 10 * 60_000L

        val INSTRUCTIONS = """
            You translate Chinese text shown on a shopping app's screen (Taobao, 1688, Pinduoduo, JD, WeChat) into Vietnamese.
            The input is JSON {"items":[{"id":0,"zh":"..."}]}. Answer only JSON {"items":[{"id":0,"vi":"..."}]}, one item per input id.
            Short button and menu labels stay short. Keep every number, price, currency sign, %, minus sign, unit, size and
            product code exactly as written. 注销 is deleting or closing the account (Xóa / Hủy), never logging out;
            退出登录 is logging out (Đăng xuất). Use these terms: ${CommerceDictionary.REQUIRED.entries.joinToString("; ") { "${it.key} = ${it.value}" }}.
            If unsure of an item, copy its Chinese unchanged.
        """.trimIndent()

        /** Lines in requests of at most 40 lines and 3,000 characters (a longer line goes alone). */
        fun batches(texts: List<String>): List<List<String>> {
            val out = ArrayList<List<String>>()
            var current = ArrayList<String>()
            var chars = 0
            for (text in texts) {
                if (current.isNotEmpty() && (current.size >= MAX_ITEMS || chars + text.length > MAX_CHARS)) {
                    out.add(current)
                    current = ArrayList()
                    chars = 0
                }
                current.add(text)
                chars += text.length
            }
            if (current.isNotEmpty()) out.add(current)
            return out
        }

        fun input(texts: List<String>): String = buildJsonObject {
            putJsonArray("items") {
                texts.forEachIndexed { i, text ->
                    addJsonObject {
                        put("id", i)
                        put("zh", text)
                    }
                }
            }
        }.toString()

        /** The answer's items by id; a fence or words around the JSON are skipped. */
        fun parse(output: String): Map<Int, String> {
            val start = output.indexOf('{')
            val end = output.lastIndexOf('}')
            if (start < 0 || end <= start) return emptyMap()
            return runCatching {
                Json.parseToJsonElement(output.substring(start, end + 1)).jsonObject["items"]!!.jsonArray.mapNotNull { item ->
                    val o = item.jsonObject
                    val id = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val vi = o["vi"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null } ?: return@mapNotNull null
                    id to vi
                }.toMap()
            }.getOrDefault(emptyMap())
        }
    }
}
