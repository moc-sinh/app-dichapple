package vn.mocsinh.mstrans.core.screen

/**
 * Numbers must come through a translation exactly: prices, %, minus signs, units, sizes, product
 * codes. The translator may write "1.299" for "1,299" or drop a minus; then the line is not trusted.
 */
object NumberGuard {
    /** Full-width forms (１２３，￥) as ASCII, so "￥89" and "¥89" are the same price. */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            out.append(
                when (c) {
                    in '！'..'～' -> c - 0xFEE0
                    '　' -> ' '
                    '￥' -> '¥'
                    '−' -> '-'
                    else -> c
                },
            )
        }
        return out.toString()
    }

    private val separators = Regex("[\\s\\p{IsHan}。、【】《》「」『』“”‘’…·;()\\[\\]{}<>\"'!?|]+")
    private val code = Regex("[A-Z]{2,}[A-Z0-9-]*")

    /** What must survive, in order: anything with a digit, and codes in capitals (XL, SKU). */
    fun tokens(text: String): List<String> = separators.split(normalize(text)).mapNotNull { raw ->
        val token = raw.trim(',', '.', ':', ';', '~')
        when {
            token.isEmpty() -> null
            token.any { it in '0'..'9' } -> token
            code.matches(token) -> token
            else -> null
        }
    }

    /** Every token of `source` is in `translation`, as many times, not inside a longer number. */
    fun kept(source: String, translation: String): Boolean {
        val target = normalize(translation)
        return tokens(source).groupingBy { it }.eachCount().all { (token, count) -> occurrences(target, token) >= count }
    }

    private fun occurrences(text: String, token: String): Int =
        Regex("(?<![0-9A-Za-z])" + Regex.escape(token) + "(?![0-9A-Za-z])").findAll(text).count()
}
