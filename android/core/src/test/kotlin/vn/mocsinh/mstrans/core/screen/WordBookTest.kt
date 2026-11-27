package vn.mocsinh.mstrans.core.screen

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The large dictionary and the Sino-Vietnamese readings (a small one here: the real one is not in the repository). */
class WordBookTest {
    private val book = WordBook(
        words = listOf(
            "上市\tđưa ra thị trường\t1",
            "同步\tđồng bộ\t1",
            "比较\tso sánh\t1",
            "科技\tkhoa học kỹ thuật\t1",
            "老鹰队\tđại bàng\t0",
        ).sortedBy { it.substringBefore('\t') }.joinToString("\n"),
        readings = "舜\tthuấn\n鑫\thâm\n莞\thoàn\n龙\tlong\n宏\thoành\n运\tvận\n陆\tlục\n峰\tphong\n金\tkim\n珂\tkha\n华\thoa",
    )

    @Test
    fun `words are found by halving, with whether people wrote them`() {
        assertEquals(WordBook.Meaning("đưa ra thị trường", true), book.meaning("上市"))
        assertEquals(WordBook.Meaning("đại bàng", false), book.meaning("老鹰队"))
        assertNull(book.meaning("上"))
        assertNull(book.meaning("上市公司"))
        assertEquals("thuấn", book.reading('舜'))
        assertEquals(3, book.longest)
        assertTrue(WordBook.EMPTY.isEmpty)
        assertNull(WordBook.EMPTY.meaning("上市"))
    }

    @Test
    fun `a line is put together word by word, a name read in Sino-Vietnamese`() {
        val dictionary = CommerceDictionary(book = { book })
        assertEquals("Thuấn Hâm khoa học kỹ thuật", dictionary.gloss("舜鑫科技"))
        // Our words first, the large dictionary's where ours have none, a name where neither has a word.
        assertEquals("Hoàn Long trọng lượng số lượng ngưỡng trên dưới âm thanh và ánh sáng đồng bộ báo động",
            dictionary.gloss("莞龙重量数量上下限声光同步报警"))
        // A letter alone that is a word: its meaning ("店" a shop); letters of a name together, a
        // common one among them too ("金珂华" is "Kim Kha Hoa", not gold); "的" says nothing.
        assertEquals("Lục Phong thiết bị cân cửa hàng", dictionary.gloss("陆峰衡器店"))
        assertEquals("Kim Kha Hoa chính hãng cửa hàng", dictionary.gloss("金珂华官方店"))
        assertEquals("Cân tiểu ly điện tử cân chuyên dụng", dictionary.gloss("电子天平秤称专用"))
        assertEquals("Thuấn Hâm khoa học kỹ thuật cửa hàng", dictionary.gloss("舜鑫科技的店"))
        // A letter with neither a word nor a reading: nothing.
        assertNull(dictionary.gloss("舜鑫科技囧"))
        assertNull(CommerceDictionary().gloss("舜鑫科技"))
    }

    @Test
    fun `the large dictionary comes in where the offline translator gives nothing to show`() = runBlocking {
        val engine = TextEngine { text ->
            when (text) {
                "上市" -> "Liệt kê"
                // Mostly English: not shown.
                "舜鑫科技" -> "Shunxin Technology"
                "老鹰队" -> "Eagles"
                else -> null
            }
        }
        val translator = ScreenTranslator(CommerceDictionary(book = { book }), engine)
        // A short word written by people: before the translator.
        assertEquals("Đưa ra thị trường", translator.translate("上市")?.text)
        assertEquals("Thuấn Hâm khoa học kỹ thuật", translator.translate("舜鑫科技")?.text)
        // Only a word made by machine, and the translator's English: that word, rather than the Chinese.
        assertEquals("Đại bàng", translator.translate("老鹰队")?.text)
        // A shop's name between known words, its phrases one by one.
        assertEquals("Cửa hàng 10 năm · Quảng Đông Hoành Vận thiết bị cân", translator.translate("10年老店 广东宏运衡器")?.text)
    }
}
