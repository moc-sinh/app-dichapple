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

    /**
     * Every token of `source` is in `translation`, as many times, not inside a longer number. A code
     * may come back in other capitals ("ProMax", "Promax"); a number of 万 (ten thousands) may come
     * back written out ("1万" as "10.000").
     */
    fun kept(source: String, translation: String): Boolean {
        val target = normalize(translation)
        val written = writtenOut(normalize(source))
        return tokens(source).groupingBy { it }.eachCount().all { (token, count) ->
            (listOf(token) + written[token].orEmpty()).sumOf { occurrences(target, it) } >= count
        }
    }

    private val tenThousands = Regex("(?<![0-9.,])(\\d+(?:\\.\\d+)?)万")

    /** A number before 万, with the ways the same value is written out: 10000, 10.000, 10,000. */
    private fun writtenOut(source: String): Map<String, List<String>> {
        val out = HashMap<String, List<String>>()
        for (match in tenThousands.findAll(source)) {
            val value = java.math.BigDecimal(match.groupValues[1]).multiply(java.math.BigDecimal(10_000)).stripTrailingZeros()
            if (value.scale() > 0) continue
            val plain = value.toBigInteger().toString()
            val grouped = plain.reversed().chunked(3).joinToString(".").reversed()
            out[match.groupValues[1]] = listOf(plain, grouped, grouped.replace('.', ','))
        }
        return out
    }

    /** Not part of a longer number either: "1" is not in "1.000". */
    private fun occurrences(text: String, token: String): Int =
        Regex("(?<![0-9A-Za-z])(?<![0-9][.,])" + Regex.escape(token) + "(?![0-9A-Za-z])(?![.,][0-9])", RegexOption.IGNORE_CASE)
            .findAll(text).count()
}
