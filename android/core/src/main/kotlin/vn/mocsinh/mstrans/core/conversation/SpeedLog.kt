package vn.mocsinh.mstrans.core.conversation

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How long each sentence of Hội thoại took, for the user to send when it feels slow: how far the
 * recognizer lagged behind the speaker, how long the translation took, how long a voice waited.
 * Numbers, languages and lengths only, never what was said. The last few hundred lines.
 */
class SpeedLog {
    private val lines = ArrayList<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(line: String, at: Long = System.currentTimeMillis()) {
        lines.add("${clock.format(Date(at))} $line")
        if (lines.size > LIMIT) lines.subList(0, lines.size - LIMIT).clear()
    }

    val text: String get() = lines.joinToString("\n")

    companion object {
        private const val LIMIT = 400

        /** "1,2 s". */
        fun seconds(value: Double): String = String.format(Locale.US, "%.1f s", maxOf(0.0, value)).replace(".", ",")
    }
}
