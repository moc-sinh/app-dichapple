// VietTelex's 9,091 typing cases, typed through Composer exactly as the keyboard sends keys:
// letters compose, a space or punctuation ends the word; then every Vietnamese syllable.
// Same suite and floors as the iPhone app's Tests/Suite/main.swift.
package vn.mocsinh.mstrans.core

import vn.mocsinh.mstrans.core.telex.Composer
import vn.mocsinh.mstrans.core.telex.SyllableValidator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class TelexSuiteTest {
    private fun StringBuilder.apply(edits: List<Composer.Edit>) {
        for (edit in edits) {
            when (edit) {
                is Composer.Edit.Delete -> repeat(edit.count) {
                    if (isNotEmpty()) setLength(Composer.lastGraphemeStart(this))
                }
                is Composer.Edit.Insert -> append(edit.text)
            }
        }
    }

    /** What the field holds after typing `keys` into an empty one, the last word ended as by a space. */
    private fun typed(keys: String): String {
        val composer = Composer()
        composer.start("", "", 0.0)
        val text = StringBuilder()
        // Two seconds a key: two spaces never make ". " here.
        var now = 0.0
        for (key in keys) {
            now += 2
            when {
                key == ' ' -> text.apply(composer.space(now))
                key == '\n' -> text.apply(composer.newline(now))
                key in 'a'..'z' || key in 'A'..'Z' -> text.apply(composer.letter(key, now))
                else -> text.apply(composer.insert(key.toString(), now))
            }
        }
        text.apply(composer.space(now + 2))
        return text.dropLast(1).toString()
    }

    @Test
    fun typingSuite() {
        val file = File("Tests/Suite/telex_suite.tsv")
        assertTrue(file.exists(), "missing ${file.absolutePath}")
        val pass = HashMap<String, Int>()
        val total = HashMap<String, Int>()
        val misses = HashMap<String, MutableList<String>>()
        for (line in file.readLines()) {
            if (line.startsWith("#") || line.isEmpty()) continue
            val f = line.split("\t")
            if (f.size != 3) continue
            val got = typed(f[0])
            total.merge(f[2], 1, Int::plus)
            if (got == f[1] || f[1].split(" / ").contains(got)) {
                pass.merge(f[2], 1, Int::plus)
            } else {
                misses.getOrPut(f[2]) { mutableListOf() }.add("${f[0]} -> $got, want ${f[1]}")
            }
        }
        for (kind in total.keys.sorted()) println("$kind: ${pass[kind] ?: 0}/${total[kind]}")

        val failures = StringBuilder()
        fun require(kind: String, atLeast: Int? = null) {
            val got = pass[kind] ?: 0
            val all = total[kind] ?: 0
            val want = atLeast ?: all
            if (got >= want) return
            failures.append("FAIL $kind: $got/$all, must be at least $want\n")
            for (miss in (misses[kind] ?: emptyList()).take(20)) failures.append("   $miss\n")
        }
        require("transform")
        require("keep_as_typed", atLeast = 360)
        require("cancel_keep_composed")
        require("restore_raw", atLeast = 7041)
        if (failures.isNotEmpty()) fail(failures.toString())
    }

    /**
     * Every syllable the rules allow (onset × rime × tone, standard spelling), typed as Telex keys
     * with the marks doubled and the tone last: it must end as the engine composes it, and while it
     * is typed the field must show exactly what the engine composed (no flicker mid-word).
     */
    @Test
    fun syllables() {
        val marks = mapOf('â' to "aa", 'ă' to "aw", 'ê' to "ee", 'ô' to "oo", 'ơ' to "ow", 'ư' to "uw", 'đ' to "dd")
        var syllables = 0
        var endWrong = 0
        var flicker = 0
        val report = StringBuilder()
        for (onset in (SyllableValidator.onsets - SyllableValidator.teencodeOnsets).sorted()) {
            for (rime in (SyllableValidator.rimes - SyllableValidator.teencodeRimes).sorted()) {
                val word = onset + rime
                if (!SyllableValidator.isValidSyllable(word, teencode = false)) continue
                for (toneKey in listOf("", "s", "f", "r", "x", "j")) {
                    val keys = word.map { marks[it] ?: it.toString() }.joinToString("") + toneKey
                    val engine = Composer.makeEngine()
                    val composer = Composer()
                    composer.start("", "", 0.0)
                    val text = StringBuilder()
                    var steady = true
                    for (key in keys) {
                        text.apply(composer.letter(key, 0.0))
                        engine.feed(key)
                        if (text.toString() != engine.composed) steady = false
                    }
                    val composed = engine.composed
                    // Only tones the syllable takes (a stop coda takes sắc or nặng only).
                    if (!SyllableValidator.isValidSyllable(composed, teencode = false)) continue
                    syllables++
                    text.apply(composer.space(0.0))
                    val ended = text.dropLast(1).toString()
                    if (ended != composed) {
                        endWrong++
                        if (endWrong <= 10) report.append("   $keys -> $ended, want $composed\n")
                    }
                    if (!steady) {
                        flicker++
                        if (flicker <= 10) report.append("   $keys flickers while typed\n")
                    }
                }
            }
        }
        println("syllables: $syllables, $endWrong end wrong, $flicker flicker while typed")
        if (syllables < 19_000 || endWrong > 0 || flicker > 0) fail("syllables\n$report")
    }

    @Test
    fun backspaceReopensWord() {
        val composer = Composer()
        composer.start("", "", 0.0)
        val text = StringBuilder()
        for (k in "thay") text.apply(composer.letter(k, 1.0))
        text.apply(composer.letter('s', 1.0))
        text.apply(composer.space(5.0))
        text.apply(composer.deleteBackward(9.0))
        text.apply(composer.letter('a', 10.0))
        assertEquals("thấy", text.toString())
    }
}
