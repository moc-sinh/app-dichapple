package vn.mocsinh.mstrans.core

import java.util.Locale

object Config {
    const val ENDPOINT = "https://api.openai.com/v1/responses"

    /** Messages sent along as context. Enough for pronouns and references, small enough to stay fast. */
    const val CONTEXT_LIMIT = 10
    const val CONTEXT_CHARACTER_LIMIT = 2_500
    /** Remembered (copied or sent) messages expire after this. */
    const val MEMORY_MAX_AGE_MS = 45L * 60 * 1000
}

/** Which OpenAI model translates. */
enum class TranslationModel(val id: String) {
    /** Best quality: near the top model at a fraction of its cost. */
    SOL("gpt-6.1-sol"),
    /** Fastest and cheapest. */
    LUNA("gpt-6-luna");

    val label: String
        get() = when (this) {
            SOL -> "Chuẩn nhất (GPT-6.1 Sol)"
            LUNA -> "Nhanh (GPT-6 Luna)"
        }

    /** Sol does not accept "none"; a little reasoning helps nuance without slowing replies much. */
    val reasoningEffort: String
        get() = when (this) {
            SOL -> "low"
            LUNA -> "none"
        }

    /** US dollars per million tokens: input, input read from OpenAI's cache, output (reasoning included). */
    val price: Triple<Double, Double, Double>
        get() = when (this) {
            SOL -> Triple(2.0, 0.1, 10.0)
            LUNA -> Triple(0.1, 0.01, 0.5)
        }

    companion object {
        fun of(id: String?): TranslationModel? = entries.firstOrNull { it.id == id }
    }
}

/** How formal outgoing messages sound. */
enum class Tone {
    POLITE, FORMAL, FRIENDLY;

    val label: String
        get() = when (this) {
            POLITE -> "Lịch sự"
            FORMAL -> "Trang trọng"
            FRIENDLY -> "Thân mật"
        }

    val instruction: String
        get() = when (this) {
            POLITE -> "polite and warm, the way professionals write in a business chat"
            FORMAL -> "formal and respectful, as in correspondence with an important client or a superior"
            FRIENDLY -> "friendly and casual, as between partners who know each other well"
        }
}

data class Language(
    val code: String,
    /** Name in the language itself, shown on keys. */
    val label: String,
    /** Vietnamese name, shown in sentences. */
    val name: String,
    /** English name, used in prompts. */
    val englishName: String,
) {
    companion object {
        /** Translate into whatever language the partner writes in. */
        const val AUTO = "auto"

        val vietnamese = Language("vi", "Tiếng Việt", "tiếng Việt", "Vietnamese")

        val all = listOf(
            Language("zh", "中文", "tiếng Trung", "Simplified Chinese (Mainland China)"),
            Language("zh-Hant", "繁體中文", "tiếng Trung phồn thể", "Traditional Chinese (Taiwan)"),
            Language("en", "English", "tiếng Anh", "English"),
            Language("ko", "한국어", "tiếng Hàn", "Korean"),
            Language("ja", "日本語", "tiếng Nhật", "Japanese"),
            Language("th", "ไทย", "tiếng Thái", "Thai"),
            Language("id", "Indonesia", "tiếng Indonesia", "Indonesian"),
            Language("ms", "Melayu", "tiếng Mã Lai", "Malay"),
            Language("ru", "Русский", "tiếng Nga", "Russian"),
            Language("fr", "Français", "tiếng Pháp", "French"),
            Language("de", "Deutsch", "tiếng Đức", "German"),
            Language("es", "Español", "tiếng Tây Ban Nha", "Spanish"),
            Language("hi", "हिन्दी", "tiếng Hindi", "Hindi"),
            Language("ar", "العربية", "tiếng Ả Rập", "Arabic"),
        )

        fun named(code: String?): Language? = all.firstOrNull { it.code == code }

        /** Vietnamese for "vi", else a known language, else Vietnamese. */
        fun ofCode(code: String): Language = if (code == vietnamese.code) vietnamese else named(code) ?: vietnamese

        /** A language not in the list, named by the system ("tiếng Bồ Đào Nha"). */
        fun other(code: String): Language {
            val locale = Locale.forLanguageTag(code)
            val name = locale.getDisplayLanguage(Locale.forLanguageTag("vi")).ifEmpty { code }
            val english = locale.getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }
            return Language(code, name.replaceFirstChar { it.uppercase() }, name, english)
        }

        /** "Tiếng Anh" for labels: a known language, or the system's name for the code. */
        fun displayName(code: String): String {
            if (code == vietnamese.code) return vietnamese.label
            named(code)?.let { return it.label }
            if (code.isEmpty()) return ""
            return other(code).label
        }
    }
}

/** One chat message used as translation context. */
data class ChatLine(val side: Side, val text: String, val date: Long) {
    enum class Side { PARTNER, ME }
}

/** One translation kept for the History tab. */
data class HistoryItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    /** ME: a message you sent; PARTNER: one you received. */
    val side: ChatLine.Side,
    val source: String,
    val translation: String,
    /** The partner's language: what you translated into, or what they wrote in. */
    val languageCode: String,
    val meaning: String? = null,
    val note: String? = null,
    val date: Long = System.currentTimeMillis(),
    val starred: Boolean = false,
) {
    val language: Language? get() = Language.named(languageCode)

    /** "Tiếng Anh", for labels; empty when the language was not recognised. */
    val languageName: String
        get() {
            val name = language?.name ?: if (languageCode.isEmpty()) "" else Language.other(languageCode).name
            return name.replaceFirstChar { it.uppercase() }
        }

    /** What the user reads first: their own words, or the partner's in Vietnamese. */
    val vietnamese: String get() = if (side == ChatLine.Side.ME) source else translation
    val foreign: String get() = if (side == ChatLine.Side.ME) translation else source
}
