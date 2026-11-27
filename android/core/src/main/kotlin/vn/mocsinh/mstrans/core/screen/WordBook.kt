package vn.mocsinh.mstrans.core.screen

/**
 * The large Chinese–Vietnamese dictionary (some 75 000 words) and the Sino-Vietnamese reading of
 * each character. `android/tools/dictionary.py` builds both on the owner's computer from
 * dictionaries they have; they are not in the repository, and without them this is empty and
 * everything works as before.
 *
 * The words are kept as their text and where each line begins (a few MB, not a map of strings
 * several times that), and looked up by halving.
 */
class WordBook(
    /** "word<TAB>meaning<TAB>trust" lines, sorted as Java sorts strings; trust 1: written by people. */
    private val words: String,
    /** "character<TAB>reading" lines. */
    readings: String,
) {
    /** A meaning, and whether it was written by people (`trusted`) or may have been made by machine. */
    data class Meaning(val text: String, val trusted: Boolean)

    private val starts: IntArray
    private val tabs: IntArray
    private val readingOf: Map<Char, String>

    /** The longest word, in letters. */
    val longest: Int
    val isEmpty: Boolean get() = starts.isEmpty()

    init {
        val lineStarts = ArrayList<Int>()
        val firstTabs = ArrayList<Int>()
        var most = 0
        var at = 0
        while (at < words.length) {
            val end = words.indexOf('\n', at).let { if (it < 0) words.length else it }
            val tab = words.indexOf('\t', at)
            if (tab in (at + 1) until end && words[at] != '#') {
                lineStarts.add(at)
                firstTabs.add(tab)
                most = maxOf(most, tab - at)
            }
            at = end + 1
        }
        starts = lineStarts.toIntArray()
        tabs = firstTabs.toIntArray()
        longest = most
        val map = HashMap<Char, String>()
        for (line in readings.lineSequence()) {
            if (line.length < 3 || line[1] != '\t' || line.startsWith("#")) continue
            map[line[0]] = line.substring(2).trim()
        }
        readingOf = map
    }

    /** The meaning of `word` (Simplified, no spaces), or null. */
    fun meaning(word: String): Meaning? {
        var low = 0
        var high = starts.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val order = compare(middle, word)
            when {
                order < 0 -> low = middle + 1
                order > 0 -> high = middle - 1
                else -> {
                    val start = tabs[middle] + 1
                    val end = words.indexOf('\n', start).let { if (it < 0) words.length else it }
                    val second = words.indexOf('\t', start).let { if (it < 0 || it > end) end else it }
                    val trusted = second < end && words[second + 1] == '1'
                    return Meaning(words.substring(start, second), trusted)
                }
            }
        }
        return null
    }

    /** The Sino-Vietnamese reading of a character (Simplified), or null. */
    fun reading(c: Char): String? = readingOf[c]

    /** The word of line `i` against `word`, as `String.compareTo`, without making a string of it. */
    private fun compare(i: Int, word: String): Int {
        val start = starts[i]
        val length = tabs[i] - start
        val common = minOf(length, word.length)
        for (k in 0 until common) {
            val a = words[start + k]
            val b = word[k]
            if (a != b) return a - b
        }
        return length - word.length
    }

    companion object {
        private const val WORDS = "/vn/mocsinh/mstrans/core/screen/zh_vi_words.txt"
        private const val READINGS = "/vn/mocsinh/mstrans/core/screen/han_viet.txt"

        val EMPTY = WordBook("", "")

        /** The one in the APK, read once (a few hundred milliseconds: not on the main thread). Empty without it. */
        val DEFAULT: WordBook by lazy {
            runCatching {
                val words = WordBook::class.java.getResourceAsStream(WORDS)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@runCatching EMPTY
                val readings = WordBook::class.java.getResourceAsStream(READINGS)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                WordBook(words, readings)
            }.getOrDefault(EMPTY)
        }
    }
}
