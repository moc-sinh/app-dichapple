// The keyboard's typing checks, the same as the iPhone app's Tests/main.swift (the parts that do not
// depend on how iOS reports keys).
package vn.mocsinh.mstrans.core

import vn.mocsinh.mstrans.core.telex.Composer
import kotlin.test.Test

private fun StringBuilder.apply(edits: List<Composer.Edit>) {
    for (edit in edits) {
        when (edit) {
            is Composer.Edit.Delete -> repeat(edit.count) { if (isNotEmpty()) setLength(Composer.lastGraphemeStart(this)) }
            is Composer.Edit.Insert -> append(edit.text)
        }
    }
}

private fun String.applying(edits: List<Composer.Edit>): String = StringBuilder(this).also { it.apply(edits) }.toString()

class ComposerTest {
    /** What typing `keys` and then a space leaves in an empty field, without that space. */
    private fun typed(keys: String): String {
        val composer = Composer()
        composer.start("", "", 0.0)
        val text = StringBuilder()
        for (key in keys) text.apply(composer.letter(key, 0.0))
        text.apply(composer.space(0.0))
        return text.dropLast(1).toString()
    }

    @Test
    fun words() {
        val c = Checks()
        val cases = listOf(
            "guwir" to "gửi", "giups" to "giúp", "Saus" to "Sáu", "nhes" to "nhé",
            "truwowcs" to "trước", "thuws" to "thứ", "ddi" to "đi", "camr" to "cảm",
            "own" to "ơn", "quas" to "quá", "hoaf" to "hoà", "vieetj" to "việt",
            "Ddaay" to "Đây", "nguwowif" to "người", "chaof" to "chào", "khoong" to "không",
            "dduwowcj" to "được", "tieesng" to "tiếng", "tuaanf" to "tuần", "hangf" to "hàng",
            "baos" to "báo", "nhw" to "như", "w" to "ư", "tw" to "tư", "gif" to "gì",
            "quoocs" to "quốc", "dduocwj" to "được", "nguoiwf" to "người", "khuyeen" to "khuyên",
            "giuwax" to "giữa", "Vieejt" to "Việt", "NAM" to "NAM", "DDAAY" to "ĐÂY", "xooong" to "xoong",
            "ww" to "w", "Ww" to "W", "WW" to "W", "tww" to "tw", "www" to "www", "chuaw" to "chưa", "muaw" to "mưa",
            "ngwoif" to "người", "nguwoif" to "người", "twowng" to "tương", "hoaw" to "hoă", "quaw" to "quă",
            "tuw" to "tư", "uwow" to "ươ", "Uw" to "Ư",
            "ass" to "as", "aaa" to "aa", "tesst" to "test", "sorrry" to "sorry", "passs" to "pass",
            "facebook" to "facebook", "good" to "good", "thanks" to "thanks", "Shopee" to "Shopee",
            "meeting" to "meeting", "we" to "we", "hello" to "hello", "ok" to "ok", "Zalo" to "Zalo",
            "price" to "price", "order" to "order", "email" to "email", "iPhone" to "iPhone",
            "WeChat" to "WeChat", "class" to "class", "access" to "access", "process" to "process",
            "staff" to "staff", "check" to "check", "file" to "file",
            "aaaaaaaaaaaaaaaaaaaa" to "aaaaaaaaaaaaaaaaaaa",
            "hahahahahahahahahahahahahahahahahahahaha" to "hahahahahahahahahahahahahahahahahahahaha",
            "kkkkkkkkkkkkkkkk" to "kkkkkkkkkkkkkkkk", "ddddddd" to "dddddd", "facebookfacebookfacebook" to "facebookfacebookfacebook",
            "nghieengs" to "nghiếng", "khuyeenr" to "khuyển",
            "hofang" to "hoàng", "tosan" to "toán", "lojai" to "loại", "gisa" to "giá", "qusy" to "quý",
            "google" to "google", "github" to "github", "enterprise" to "enterprise",
            "ruouwj" to "rượu", "huouw" to "hươu", "buouws" to "bướu", "khuouws" to "khướu",
        )
        for ((keys, want) in cases) {
            val got = typed(keys)
            c.check(got == want, "$keys -> $got, want $want")
        }
        for (w in listOf(
            "người", "được", "tiếng", "Việt", "khuyên", "giữa", "quốc", "Đây", "tuần", "hoà", "gửi",
            "trước", "nhé", "không", "chào", "thứ", "cảm", "ơn", "hàng", "báo", "phương", "nghiêng", "ngoài",
        )) {
            val engine = Composer.makeEngine()
            c.check(engine.seed(w) && engine.composed == w, "taking up $w -> ${engine.composed}")
        }
        c.done()
    }

    @Test
    fun whileTyping() {
        val c = Checks()
        val live = listOf(
            "good" to "good", "we" to "we", "meeting" to "meeting", "WeChat" to "WeChat", "add" to "add",
            "hello" to "hello", "price" to "price", "ok" to "ok", "thanks" to "thanks", "Deffault" to "Default",
            "vieejt" to "việt", "nguwowif" to "người", "dduocwj" to "được", "ddc" to "đc", "w" to "ư",
            "dduwngd" to "đưngd", "dduq" to "đuq", "vieetjk" to "việtk", "new" to "new", "power" to "power",
            "ddddddd" to "dddddd", "mootjt" to "mộtt",
        )
        for ((keys, want) in live) {
            val composer = Composer()
            composer.start("", "", 0.0)
            val text = StringBuilder()
            for (key in keys) text.apply(composer.letter(key, 0.0))
            c.check(text.toString() == want, "while typing $keys -> $text, want $want")
        }
        c.done()
    }

    /** Keys, accents picked from the row and ⌫ in a row, into a field that starts with `before`. */
    private fun sequence(before: String, steps: List<String>): String {
        val composer = Composer()
        composer.start(before, "", 0.0)
        val text = StringBuilder(before)
        var now = 0.0
        for (step in steps) {
            now += 0.2
            val edits = when {
                step == "⌫" -> composer.deleteBackward(now)
                step == " " -> composer.space(now)
                step.length > 1 || step[0].code >= 128 -> composer.insert(step.removePrefix("+"), now)
                else -> composer.letter(step[0], now)
            }
            text.apply(edits)
        }
        return text.toString()
    }

    @Test
    fun sequences() {
        val c = Checks()
        fun s(vararg steps: String) = steps.toList()
        val cases = listOf(
            Triple("", s("t", "h", "a", "y", "s", " ", "⌫", "a"), "thấy"),
            Triple("Chào toan", s("s"), "Chào toán"),
            Triple("", s("c", "+á", "n"), "cán"),
            Triple("google", s("s"), "googles"),
            Triple("", s("g", "o", "o", "d", "⌫", "s"), "goos"),
            Triple("", s("k", "h", "o", "o", "⌫", "o", "o", "n", "g"), "không"),
            Triple("", s("k", "h", "o", "o", "n", "g", "k", "⌫", " "), "không "),
            Triple("", s("t", "i", "e", "e", "n", "g", "s", "p", "⌫", " "), "tiếng "),
            Triple("", s("n", "g", "u", "w", "o", "w", "i", "f", "k", "⌫", " "), "người "),
            Triple("", s("d", "d", "u", "w", "n", "g", "d", "⌫", "f", " "), "đừng "),
            Triple("", s("d", "d", "u", "q", "⌫", "w", "n", "g", "s", " "), "đứng "),
            Triple("", s("d", "d", "u", "w", "n", "g", "s", "d", "⌫", " "), "đứng "),
            Triple("", s("d", "d", "u", "w", "n", "g", "d", "x", "⌫", "⌫", "f", " "), "đừng "),
            Triple("", s("d", "d", "u", "w", "n", "g", "d", " "), "dduwngd "),
            Triple("", s("+d", "+d", "+u", "+w", "+o", "+w", "+c", "+j", "⌫", "+s", "+@", "+a", "+a", "+.", "+v", "+n", " "), "dduwowcs@aa.vn "),
            Triple("ab", s("⌫", "+d", "+d", "+s"), "adds"),
            Triple("", s("a", "r", "e", "⌫"), "ar"),
            Triple("", s("a", "f", "t", "e", "r", "⌫", "⌫"), "aft"),
            Triple("", s("o", "r", "d", "e", "r", "⌫", "⌫", "⌫"), "or"),
            Triple("việt", s("k", " "), "việtk "),
            Triple("Đây", s("l"), "Đâyl"),
            Triple("", s("b", "+ê", "t", " "), "bêt "),
        )
        for ((before, steps, want) in cases) {
            val got = sequence(before, steps)
            c.check(got == want, "$before|${steps.joinToString("")} -> $got, want $want")
        }
        c.done()
    }

    /** An app that shows each edit a few keys late. */
    private class LaggingField {
        var text = ""
        val states = arrayListOf("")
        var reported = 0

        fun apply(edits: List<Composer.Edit>) {
            for (edit in edits) {
                text = text.applying(listOf(edit))
                states.add(text)
            }
        }

        fun seen(lag: Int): String {
            reported = maxOf(reported, states.size - 1 - lag)
            return states[reported]
        }

        fun replace(new: String) {
            text = new
            states.add(new)
            reported = states.size - 1
        }
    }

    private class Clock(var now: Double = 0.0)

    private fun typeFast(keys: String, lag: Int, field: LaggingField, composer: Composer, clock: Clock) {
        for (key in keys) {
            composer.fieldChanged(field.seen(lag), "", clock.now)
            field.apply(if (key == ' ') composer.space(clock.now) else composer.letter(key, clock.now))
            clock.now += 0.06
        }
    }

    @Test
    fun laggingApps() {
        val c = Checks()
        for ((keys, want) in listOf(
            "Tooi khoong bieets laf banj thees naof" to "Tôi không biết là bạn thế nào",
            "ww we will work wow" to "w we will work ươ",
            "nguwowif nuwowcs ngoaif muaw chuaw" to "người nước ngoài mưa chưa",
        )) {
            for (lag in 0..4) {
                val field = LaggingField()
                val composer = Composer()
                val clock = Clock()
                composer.start("", "", 0.0)
                typeFast(keys, lag, field, composer, clock)
                c.check(field.text == want, "fast typing (lag $lag): $keys -> ${field.text}, want $want")
            }
        }

        run {
            val field = LaggingField()
            val composer = Composer()
            val clock = Clock()
            composer.start("", "", 0.0)
            typeFast("ok", 2, field, composer, clock)
            clock.now += 2
            field.replace("")
            composer.fieldChanged("", "", clock.now)
            typeFast("chaof banj", 2, field, composer, clock)
            c.check(field.text == "chào bạn", "after sending: ${field.text}, want chào bạn")
        }

        run {
            val field = LaggingField()
            val composer = Composer()
            val clock = Clock()
            composer.start("", "", 0.0)
            typeFast("ok  vaang", 3, field, composer, clock)
            c.check(field.text == "ok. vâng", "double space: ${field.text}, want ok. vâng")
        }

        for (lag in 1..4) {
            val field = LaggingField()
            val composer = Composer()
            val clock = Clock()
            composer.start("", "", 0.0)
            clock.now = 5.0
            typeFast("ddi nguwowif", lag, field, composer, clock)
            c.check(field.text == "đi người", "fast typing after a pause (lag $lag): ${field.text}, want đi người")
        }

        run {
            val field = LaggingField()
            val composer = Composer()
            val clock = Clock()
            composer.start("", "", 0.0)
            typeFast("ok", 2, field, composer, clock)
            composer.fieldChanged(field.seen(0), "", clock.now)
            clock.now += 0.3
            field.replace("")
            composer.fieldChanged("", "", clock.now)
            clock.now += 0.3
            typeFast("chaof", 2, field, composer, clock)
            c.check(field.text == "chào", "sent right after typing: ${field.text}, want chào")
        }

        run {
            val composer = Composer()
            var now = 0.0
            composer.start("ok ", "", now)
            composer.letter('d', now)
            now += 0.06
            val edits = composer.letter('d', now)
            val waiting = composer.fieldChanged("ok ", "", now + 0.6)
            val caughtUp = composer.fieldChanged("ok ", "", now + 1.6)
            c.check(edits == listOf(Composer.Edit.Delete(1), Composer.Edit.Insert("đ")) && !waiting && caughtUp && composer.before == "ok ",
                "dropped edit: $edits, waiting $waiting, caught up $caughtUp, before ${composer.before}")
        }

        run {
            val field = LaggingField()
            val composer = Composer()
            var now = 0.0
            composer.start("", "", now)
            for (key in "ok") {
                field.apply(composer.letter(key, now))
                now += 0.1
            }
            now += 0.2
            field.replace("")
            val atOnce = composer.fieldChanged("", "", now)
            now = 0.1 + 0.55
            val afterPause = composer.fieldChanged("", "", now)
            val clock = Clock(now + 0.3)
            typeFast("chaof", 2, field, composer, clock)
            c.check(!atOnce && afterPause && field.text == "chào", "sent quickly: at once $atOnce, after the pause $afterPause, then ${field.text}")
        }

        var seed = 42UL
        fun random(n: Int): Int {
            seed = seed * 6364136223846793005UL + 1442695040888963407UL
            return ((seed shr 33) % n.toULong()).toInt()
        }
        val messages = listOf("hahaha haha ddi ddaau", "Tooi khoong bieets", "ww nguwowif muaw", "dd ddd dddd", "aa aaa uwow")
        for (round in 0 until 200) {
            val keys = messages[round % messages.size]
            val want = keys.split(" ").joinToString(" ") { typed(it) }
            val field = LaggingField()
            val composer = Composer()
            var now = random(10).toDouble()
            composer.start("", "", 0.0)
            for (key in keys) {
                if (random(20) == 0) {
                    now += 0.6 + random(20) / 10.0
                    composer.fieldChanged(field.seen(0), "", now)
                } else if (random(3) != 0) {
                    composer.fieldChanged(field.seen(random(5)), "", now)
                }
                field.apply(if (key == ' ') composer.space(now) else composer.letter(key, now))
                now += 0.03 + random(12) / 100.0
            }
            c.check(field.text == want, "uneven reports (round $round): $keys -> ${field.text}, want $want")
        }
        c.done()
    }

    @Test
    fun cursorAndReturn() {
        val c = Checks()
        fun moveThenType(before: String, after: String, offset: Int, keys: String): String {
            var fieldBefore = before
            var fieldAfter = after
            val composer = Composer()
            composer.start(before, after, 0.0)
            composer.moveCursor(offset, 1.0)
            val text = fieldBefore + fieldAfter
            val at = (fieldBefore.length + offset).coerceIn(0, text.length)
            fieldBefore = text.substring(0, at)
            fieldAfter = text.substring(at)
            for (key in keys) {
                if (composer.before == null) composer.start(fieldBefore, fieldAfter, 1.0)
                fieldBefore = fieldBefore.applying(composer.letter(key, 1.1))
            }
            return "$fieldBefore|$fieldAfter"
        }
        for ((case, want) in listOf(
            listOf("chao ban", "", "-4", "f") to "chào| ban", listOf("chao", " ban", "4", "f") to "chao bàn|",
            listOf("ok", "", "-5", "a") to "a|ok", listOf("viêt", "", "1", "j") to "việt|",
            listOf("tôi đi", "", "-3", "s") to "tối| đi",
        )) {
            val got = moveThenType(case[0], case[1], case[2].toInt(), case[3])
            c.check(got == want, "cursor moved ${case[2]} in ${case[0]}|${case[1]}, then ${case[3]}: $got, want $want")
        }

        fun sendWithReturn(keys: String, more: String, early: String): String {
            val field = LaggingField()
            val composer = Composer()
            var now = 0.0
            composer.start("", "", now)
            for (key in keys) {
                field.apply(composer.letter(key, now))
                now += 0.1
            }
            composer.newline(now)
            field.replace("")
            now += 0.1
            for (key in early) {
                field.apply(composer.letter(key, now))
                now += 0.1
            }
            composer.fieldChanged("", "", now)
            for (key in more) {
                if (composer.before == null) composer.start(field.text, "", now)
                field.apply(
                    when (key) {
                        '\b' -> composer.deleteBackward(now)
                        ' ' -> composer.space(now)
                        else -> composer.letter(key, now)
                    },
                )
                now += 0.1
            }
            return field.text
        }
        for ((case, want) in listOf(
            Triple("ok", "\bddi", "") to "đi", Triple("ok", "\b\bchaof", "") to "chào",
            Triple("vaang", "nhs", "a") to "ánh", Triple("ok", "nh ddi", "a") to "anh đi",
        )) {
            val got = sendWithReturn(case.first, case.second, case.third)
            c.check(got == want, "after ⏎ sent ${case.first}, then ${case.third}|${case.second}: $got, want $want")
        }
        c.done()
    }

    @Test
    fun unitsForTheField() {
        val c = Checks()
        // An emoji is two UTF-16 units; ⌫ takes it whole.
        val edits = Composer.toUnits(listOf(Composer.Edit.Delete(1)), "ok 😀")
        c.check(edits == listOf(Composer.Edit.Delete(2)), "emoji deleted whole: $edits")
        val composer = Composer()
        composer.start("ok 😀", "", 0.0)
        val back = composer.deleteBackward(1.0)
        c.check(back == listOf(Composer.Edit.Delete(1)) && composer.before == "ok ", "the keyboard's copy loses the emoji: ${composer.before}")
        c.done()
    }

    @Test
    fun finishWordWithoutSpace() {
        val c = Checks()
        // Android's Send or Search key ends the word as a space would, without typing one.
        for ((keys, want) in listOf("google" to "google", "guwir" to "gửi", "hofang" to "hoàng")) {
            val composer = Composer()
            composer.start("", "", 0.0)
            val text = StringBuilder()
            for (key in keys) text.apply(composer.letter(key, 0.0))
            text.apply(composer.finishWord(0.0))
            c.check(text.toString() == want, "$keys then Send: $text, want $want")
            c.check(composer.before == want, "$keys then Send, the keyboard's copy: ${composer.before}")
        }
        c.done()
    }
}
