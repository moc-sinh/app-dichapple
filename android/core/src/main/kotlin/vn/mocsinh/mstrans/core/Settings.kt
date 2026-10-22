package vn.mocsinh.mstrans.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Where settings live: SharedPreferences on the phone, a map in tests. Every value is a string. */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

class MemoryStore : KeyValueStore {
    private val map = HashMap<String, String>()
    @Synchronized override fun get(key: String): String? = map[key]
    @Synchronized override fun set(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/** Settings and context shared by the app and the keyboard (one process on Android). */
class Settings(val store: KeyValueStore, private val clock: () -> Long = System::currentTimeMillis) {
    object Key {
        const val API_KEY = "apiKey"
        const val SONIOX_KEY = "sonioxKey"
        const val GOOGLE_KEY = "googleKey"
        const val MODEL = "model"
        const val TARGET_CODE = "targetCode"
        const val LAST_PARTNER_CODE = "lastPartnerCode"
        const val TONE = "tone"
        const val PROFILE = "profile"
        const val ADDRESSING = "addressing"
        const val GENDER = "gender"
        const val GLOSSARY = "glossary"
        const val CHECK_MEANING = "checkMeaning"
        const val INSTANT_INCOMING = "instantIncoming"
        const val HAPTICS = "haptics"
        const val MEMORY = "memory"
        const val INCOMING_CACHE = "incomingCache"
        const val HANDLED_CLIPBOARD = "handledClipboard"
        const val CONTEXT_CLEARED_AT = "contextClearedAt"
        const val KEY_SOUNDS = "keySounds"
        const val KEY_POPUPS = "keyPopups"
        const val KEYBOARD_SIZE = "keyboardSize"
        const val HISTORY = "history"
        const val KEEP_HISTORY = "keepHistory"
    }

    private fun string(key: String): String? = store.get(key)
    private fun bool(key: String, default: Boolean): Boolean = store.get(key)?.toBooleanStrictOrNull() ?: default
    private fun int(key: String, default: Int): Int = store.get(key)?.toIntOrNull() ?: default
    private fun trimmedKey(key: String): String? = string(key)?.trim()?.ifEmpty { null }

    // Settings

    /** The OpenAI key, on this phone only. */
    var apiKey: String?
        get() = trimmedKey(Key.API_KEY)
        set(value) = store.set(Key.API_KEY, value)

    /** The Soniox key (the Hội thoại tab's cheapest live translation). */
    var sonioxKey: String?
        get() = trimmedKey(Key.SONIOX_KEY)
        set(value) = store.set(Key.SONIOX_KEY, value)

    /** The Google Cloud key (Google's voices in the Hội thoại tab). */
    var googleKey: String?
        get() = trimmedKey(Key.GOOGLE_KEY)
        set(value) = store.set(Key.GOOGLE_KEY, value)

    var model: TranslationModel
        get() = TranslationModel.of(string(Key.MODEL)) ?: TranslationModel.SOL
        set(value) = store.set(Key.MODEL, value.id)

    /** A language code, or `Language.AUTO` to follow the partner's language. */
    var targetCode: String
        get() = string(Key.TARGET_CODE) ?: Language.AUTO
        set(value) = store.set(Key.TARGET_CODE, value)

    /** The partner language seen most recently, used when "auto" has no context yet. */
    var lastPartnerCode: String
        get() = string(Key.LAST_PARTNER_CODE) ?: "zh"
        set(value) = store.set(Key.LAST_PARTNER_CODE, value)

    var tone: Tone
        get() = Tone.entries.getOrNull(int(Key.TONE, 0)) ?: Tone.POLITE
        set(value) = store.set(Key.TONE, value.ordinal.toString())

    var profile: String
        get() = string(Key.PROFILE) ?: ""
        set(value) = store.set(Key.PROFILE, value)

    var addressing: String
        get() = string(Key.ADDRESSING) ?: ""
        set(value) = store.set(Key.ADDRESSING, value)

    /** 0 not given, 1 male, 2 female. Some languages word things by gender. */
    var gender: Int
        get() = int(Key.GENDER, 0)
        set(value) = store.set(Key.GENDER, value.toString())

    var glossary: String
        get() = string(Key.GLOSSARY) ?: ""
        set(value) = store.set(Key.GLOSSARY, value)

    var checkMeaning: Boolean
        get() = bool(Key.CHECK_MEANING, true)
        set(value) = store.set(Key.CHECK_MEANING, value.toString())

    /** Read the copied message as the keyboard opens, no tap. */
    var instantIncoming: Boolean
        get() = bool(Key.INSTANT_INCOMING, false)
        set(value) = store.set(Key.INSTANT_INCOMING, value.toString())

    var haptics: Boolean
        get() = bool(Key.HAPTICS, true)
        set(value) = store.set(Key.HAPTICS, value.toString())

    var keySounds: Boolean
        get() = bool(Key.KEY_SOUNDS, true)
        set(value) = store.set(Key.KEY_SOUNDS, value.toString())

    /** The enlarged letter above a pressed key. */
    var keyPopups: Boolean
        get() = bool(Key.KEY_POPUPS, true)
        set(value) = store.set(Key.KEY_POPUPS, value.toString())

    /** 0 standard, 1 a little taller, 2 taller. */
    var keyboardSize: Int
        get() = int(Key.KEYBOARD_SIZE, 0).coerceIn(0, 2)
        set(value) = store.set(Key.KEYBOARD_SIZE, value.toString())

    /** The clipboard text the keyboard already offered to translate (its hash). */
    var handledClipboard: Int
        get() = int(Key.HANDLED_CLIPBOARD, 0)
        set(value) = store.set(Key.HANDLED_CLIPBOARD, value.toString())

    // Context

    /** Lines the keyboard saw: messages the user copied and translations it inserted. */
    fun remember(line: ChatLine) {
        val lines = recentMemory().toMutableList()
        if (lines.lastOrNull()?.text != line.text) lines.add(line)
        saveLines(lines.takeLast(Config.CONTEXT_LIMIT))
    }

    /**
     * What the keyboard remembers of the chat, trimmed for a request: the newest lines within the
     * character budget. A line longer than what is left keeps its end (where the question usually is).
     */
    fun context(): List<ChatLine> {
        val kept = ArrayList<ChatLine>()
        var room = Config.CONTEXT_CHARACTER_LIMIT
        for (original in recentMemory().takeLast(Config.CONTEXT_LIMIT).asReversed()) {
            if (room <= 0) break
            var line = original
            if (line.text.length > room) line = line.copy(text = "…" + line.text.takeLast(room))
            room -= line.text.length
            kept.add(0, line)
        }
        return kept
    }

    /** Takes back the line remembered last, if it is `text`: a translation undone was never sent. */
    fun forgetLast(text: String) {
        val lines = recentMemory().toMutableList()
        if (lines.lastOrNull()?.text != text) return
        lines.removeAt(lines.size - 1)
        saveLines(lines)
    }

    /** "Cuộc mới": a new conversation, with nothing from the one before. */
    fun clearContext() {
        store.set(Key.MEMORY, null)
        store.set(Key.INCOMING_CACHE, null)
        store.set(Key.CONTEXT_CLEARED_AT, clock().toString())
    }

    val contextClearedAt: Long? get() = string(Key.CONTEXT_CLEARED_AT)?.toLongOrNull()

    // Incoming translations (never pay twice for the same message)

    fun cachedIncoming(text: String): String? {
        val key = cacheKey(text)
        return incomingCache().firstOrNull { it.first == key }?.second
    }

    fun cacheIncoming(text: String, translation: String) {
        // Partner messages are not kept on the phone when history is off.
        if (!keepHistory) return
        val key = cacheKey(text)
        val pairs = incomingCache().filter { it.first != key }.toMutableList()
        pairs.add(key to translation)
        val json = buildJsonArray {
            for ((k, v) in pairs.takeLast(40)) add(buildJsonArray { add(JsonPrimitive(k)); add(JsonPrimitive(v)) })
        }
        store.set(Key.INCOMING_CACHE, json.toString())
    }

    /** The glossary or the user's details changed: a message copied again is translated again. */
    fun clearIncomingCache() = store.set(Key.INCOMING_CACHE, null)

    private fun incomingCache(): List<Pair<String, String>> {
        val raw = string(Key.INCOMING_CACHE) ?: return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull {
                val pair = it.jsonArray
                if (pair.size == 2) pair[0].jsonPrimitive.content to pair[1].jsonPrimitive.content else null
            }
        }.getOrDefault(emptyList())
    }

    private fun cacheKey(text: String) = text.lowercase().filter { !it.isWhitespace() }

    private fun recentMemory(): List<ChatLine> = loadLines().filter { clock() - it.date < Config.MEMORY_MAX_AGE_MS }

    private fun loadLines(): List<ChatLine> {
        val raw = string(Key.MEMORY) ?: return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val o = element.jsonObject
                val side = if (o["side"]?.jsonPrimitive?.content == "me") ChatLine.Side.ME else ChatLine.Side.PARTNER
                val text = o["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ChatLine(side, text, o["date"]?.jsonPrimitive?.longOrNull ?: 0)
            }
        }.getOrDefault(emptyList())
    }

    private fun saveLines(lines: List<ChatLine>) {
        val json = buildJsonArray {
            for (line in lines) add(buildJsonObject {
                put("side", if (line.side == ChatLine.Side.ME) "me" else "partner")
                put("text", line.text)
                put("date", line.date)
            })
        }
        store.set(Key.MEMORY, json.toString())
    }

    // History (newest first)

    /** Off: nothing new is saved (the user may not want chats kept on the phone). */
    var keepHistory: Boolean
        get() = bool(Key.KEEP_HISTORY, true)
        set(value) = store.set(Key.KEEP_HISTORY, value.toString())

    fun history(): List<HistoryItem> = storedHistory() ?: emptyList()

    /** Translations from the conversation going on: as recent as the context, and since "Cuộc mới". */
    fun recentHistory(): List<HistoryItem> {
        val since = maxOf(clock() - Config.MEMORY_MAX_AGE_MS, contextClearedAt ?: 0)
        return history().filter { it.date > since }
    }

    /**
     * Null when saved history cannot be read, so the writers below leave it untouched instead of
     * replacing it with an empty list. One item that cannot be read is left out.
     */
    @Synchronized
    private fun storedHistory(): List<HistoryItem>? {
        val raw = string(Key.HISTORY) ?: return emptyList()
        val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return null
        return array.mapNotNull { element -> runCatching { historyItem(element.jsonObject) }.getOrNull() }
    }

    @Synchronized
    fun addHistory(item: HistoryItem) {
        if (!keepHistory) return
        val items = storedHistory()?.toMutableList() ?: return
        val first = items.firstOrNull()
        if (first != null && first.source == item.source && first.translation == item.translation) return
        items.add(0, item)
        // Starred items are never dropped to make room.
        while (items.size > HISTORY_LIMIT) {
            val last = items.indexOfLast { !it.starred }
            if (last < 0) break
            items.removeAt(last)
        }
        saveHistory(items)
    }

    @Synchronized
    fun saveHistory(items: List<HistoryItem>) {
        store.set(Key.HISTORY, JsonArray(items.map { historyJson(it) }).toString())
    }

    @Synchronized
    fun updateHistory(item: HistoryItem) {
        val items = storedHistory()?.toMutableList() ?: return
        val index = items.indexOfFirst { it.id == item.id }
        if (index < 0) return
        items[index] = item
        saveHistory(items)
    }

    @Synchronized
    fun removeHistory(id: String) {
        val items = storedHistory() ?: return
        saveHistory(items.filter { it.id != id })
    }

    companion object {
        private const val HISTORY_LIMIT = 300

        fun historyJson(item: HistoryItem): JsonObject = buildJsonObject {
            put("id", item.id)
            put("side", if (item.side == ChatLine.Side.ME) "me" else "partner")
            put("source", item.source)
            put("translation", item.translation)
            put("languageCode", item.languageCode)
            put("meaning", item.meaning?.let { JsonPrimitive(it) } ?: JsonNull)
            put("note", item.note?.let { JsonPrimitive(it) } ?: JsonNull)
            put("date", item.date)
            put("starred", item.starred)
        }

        fun historyItem(o: JsonObject): HistoryItem {
            fun s(key: String) = o[key]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }
            return HistoryItem(
                id = s("id") ?: java.util.UUID.randomUUID().toString(),
                side = if (s("side") == "me") ChatLine.Side.ME else ChatLine.Side.PARTNER,
                source = s("source") ?: error("no source"),
                translation = s("translation") ?: error("no translation"),
                languageCode = s("languageCode") ?: "",
                meaning = s("meaning"),
                note = s("note"),
                date = o["date"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
                starred = o["starred"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }
}
