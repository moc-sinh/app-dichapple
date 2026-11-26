package vn.mocsinh.mstrans.core.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What real shopping apps do that a page of plain text views does not (seen on a phone, 26/11/2026). */
class TextFitTest {
    /** 16 sp on a phone of density 2.75. */
    private val normal = 44f

    @Test
    fun `a Chinese letter is one em, a Latin letter or a digit about half`() {
        assertEquals(5f, TextFit.ems("加入购物车"), 0.01f)
        assertEquals(2f + 0.3f + 2 * 0.55f, TextFit.ems("已售 16"), 0.01f)
        assertEquals(0f, TextFit.ems("  "), 0.01f)
    }

    @Test
    fun `a card described in words is not a piece of text`() {
        // A product card of a search page: one node, its title and price as its description.
        val card = Box(540, 600, 1035, 1410)
        assertTrue(TextFit.isRegion("公主-可水洗牛皮纸袋厨房冰箱蔬菜收纳袋防水加厚食品袋环保 6.80元", card, normal))
        // A banner described in four words.
        assertTrue(TextFit.isRegion("双11大促", Box(0, 300, 1080, 700), normal))
    }

    @Test
    fun `a label, a button and a paragraph are text`() {
        assertFalse(TextFit.isRegion("立即购买", Box(700, 2200, 1040, 2320), normal))
        assertFalse(TextFit.isRegion("宝贝", Box(0, 150, 270, 280), normal))
        val review = "匿名买家 已购 前100件亏本冲量 收货后试了下，里层防水效果很好，放心放湿物。还会再来买的，推荐给大家。"
        assertFalse(TextFit.isRegion(review, Box(30, 1300, 1050, 1530), normal))
    }

    @Test
    fun `the label under an icon is the text, the icon is not`() {
        // 店铺: an icon 50 by 46, and under it two letters 22 high.
        val rows = listOf(Box(40, 2210, 90, 2256), Box(44, 2262, 86, 2284))
        val found = TextFit.pick("店铺", rows)!!
        assertEquals(listOf(rows[1]), found.lines)
        assertEquals(22 / 0.93f, found.sizePx, 0.1f)
    }

    @Test
    fun `a sentence on two rows is both rows`() {
        val rows = listOf(Box(40, 100, 1000, 140), Box(40, 150, 520, 190))
        // 34 letters at 43 px: 1462 px of text, on a row of 960 and one of 480.
        val found = TextFit.pick("加厚食品级牛皮纸袋包装袋冰箱果蔬收纳袋面包防油打包纸袋子防水加厚食品", rows)!!
        assertEquals(rows, found.lines)
    }

    @Test
    fun `rows that cannot be this text are none of it`() {
        // A card: the rows of its picture, then a title the app cut short.
        val rows = listOf(Box(540, 600, 1035, 1000), Box(560, 1020, 900, 1060), Box(560, 1075, 700, 1105))
        assertNull(TextFit.pick("公主-可水洗牛皮纸袋厨房冰箱蔬菜收纳袋防水加厚食品袋环保 6.80元", rows))
        assertNull(TextFit.pick("店铺", emptyList()))
    }
}

class SpokenTextTest {
    @Test
    fun `what a screen reader says of the view itself is not its label`() {
        assertEquals("品牌", SpokenText.label("品牌，可选，按钮"))
        assertEquals("图集", SpokenText.label("图集，已选中"))
        assertEquals("外卖打包", SpokenText.label("外卖打包,按钮"))
        assertEquals("综合", SpokenText.label("综合，已选中，双击可切换排序"))
    }

    @Test
    fun `a sentence with commas stays whole`() {
        assertEquals("收货后试了下，里层防水效果很好，放心放湿物。", SpokenText.label("收货后试了下，里层防水效果很好，放心放湿物。"))
    }

    @Test
    fun `a view that only says what it is has no label`() {
        assertEquals("", SpokenText.label("图片"))
        assertEquals("", SpokenText.label("按钮，可选"))
    }
}

class PictureTextTest {
    @Test
    fun `one letter read off an icon is not text of a picture`() {
        // A cart read as 口, a camera as 回, with a digit beside it.
        assertFalse(PictureText.worth("口", null, null))
        assertFalse(PictureText.worth("回0", null, null))
        assertTrue(PictureText.worth("新品上市", null, null))
        assertTrue(PictureText.worth("满99减10", null, null))
    }

    @Test
    fun `words far apart on a line stay apart`() {
        // A price, and further along how many bought: read as one line, never as one number.
        val words = listOf("¥14.20" to Box(560, 1300, 690, 1340), "500+人付款" to Box(730, 1306, 880, 1336))
        assertEquals("¥14.20 500+人付款", PictureText.line(words))
        // The letters of a sentence follow each other closely.
        val sentence = listOf("牛皮纸袋" to Box(560, 1240, 700, 1276), "厨房冰箱" to Box(703, 1240, 843, 1276))
        assertEquals("牛皮纸袋厨房冰箱", PictureText.line(sentence))
        // Latin words keep the space they are read with.
        assertEquals("PD 140W 充电器", PictureText.line(listOf("PD" to Box(0, 0, 50, 40), "140W" to Box(62, 0, 160, 40), "充电器" to Box(172, 0, 290, 40))))
    }

    /** Letters 30 wide, 36 high, one after another from `left`, each with its place. */
    private fun letters(text: String, left: Int, top: Int = 1300) = text.mapIndexed { i, c -> c.toString() to Box(left + i * 30, top, left + i * 30 + 28, top + 36) }

    @Test
    fun `a line read in a picture is as many texts as it has things set apart`() {
        // A price, then well apart how many bought: the reader gave them as one word.
        val row = listOf(letters("¥6.80", 560) + letters("800+人付款", 760))
        val pieces = PictureText.pieces(row)
        assertEquals(listOf("¥6.80", "800+人付款"), pieces.map { it.first })
        assertEquals(Box(760, 1300, 968, 1336), pieces[1].second)
        // A sentence is one text, and so are words in Latin letters next to it.
        assertEquals(listOf("牛皮纸袋厨房冰箱"), PictureText.pieces(listOf(letters("牛皮纸袋", 560), letters("厨房冰箱", 680))).map { it.first })
        assertEquals(listOf("PD 140W 充电器"), PictureText.pieces(listOf(letters("PD", 0), letters("140W", 70), letters("充电器", 200))).map { it.first })
    }

    @Test
    fun `the row of a price is not the second line of a title`() {
        val title = "公主-可水洗牛皮纸袋厨" to Box(560, 1240, 960, 1276)
        val price = "¥6.80 800+人付款" to Box(560, 1296, 900, 1332)
        assertEquals(listOf("公主-可水洗牛皮纸袋厨", "¥6.80 800+人付款"), ScreenBlocks.texts(listOf(title, price)).map { it.text })
        // The ¥ is often read as a Y.
        val misread = "Y5.48 2万+人付款" to Box(560, 1296, 900, 1332)
        assertEquals(2, ScreenBlocks.texts(listOf(title, misread)).size)
        // A sentence that goes on is still one text.
        val more = "房冰箱蔬菜收纳袋防水" to Box(560, 1296, 940, 1332)
        assertEquals(listOf("公主-可水洗牛皮纸袋厨房冰箱蔬菜收纳袋防水"), ScreenBlocks.texts(listOf(title, more)).map { it.text })
    }

    @Test
    fun `what the reader is not sure of, or reads on a slant, is left alone`() {
        assertFalse(PictureText.worth("官方正品", 0.3f, 0f))
        assertFalse(PictureText.worth("官方正品", 0.9f, 25f))
        assertTrue(PictureText.worth("官方正品", 0.9f, -3f))
    }
}

class PaintOrderTest {
    private val window = Box(0, 0, 1080, 2400)

    /** A page: a list that scrolls, and after it a bar with a name in it, over the list's top. */
    private class Page {
        val parents = ArrayList<Int>()
        val orders = ArrayList<Int>()
        val boxes = ArrayList<Box>()
        val scrolls = ArrayList<Boolean>()
        val texts = ArrayList<Boolean>()

        fun add(parent: Int, box: Box, order: Int = parents.count { it == parent }, scrolls: Boolean = false, text: Boolean = false): Int {
            parents.add(parent)
            orders.add(order)
            boxes.add(box)
            this.scrolls.add(scrolls)
            texts.add(text)
            return parents.lastIndex
        }

        fun under(window: Box) = PaintOrder.underPanels(parents.toIntArray(), orders.toIntArray(), boxes, scrolls.toBooleanArray(), texts.toBooleanArray(), window)
    }

    @Test
    fun `a parent is painted before its children, brothers in their drawing order`() {
        //        0
        //   1 (drawn 2nd)   2 (drawn 1st)
        //   3                4
        val sequence = PaintOrder.sequence(intArrayOf(-1, 0, 0, 1, 2), intArrayOf(0, 1, 0, 0, 0))
        assertEquals(listOf(0, 3, 1, 4, 2), sequence.toList())
    }

    @Test
    fun `text of a list that has scrolled under a bar is under that bar`() {
        val page = Page()
        val root = page.add(-1, window)
        val list = page.add(root, window, scrolls = true)
        val item = page.add(list, Box(0, 100, 1080, 400))
        val hidden = page.add(item, Box(40, 120, 600, 170), text = true)
        val shown = page.add(item, Box(40, 300, 600, 350), text = true)
        val bar = page.add(root, Box(0, 0, 1080, 220))
        page.add(bar, Box(40, 130, 300, 180), text = true)
        val under = page.under(window)
        assertTrue(under[hidden])
        assertFalse(under[shown])
    }

    @Test
    fun `a bar painted before the list hides nothing`() {
        val page = Page()
        val root = page.add(-1, window)
        val bar = page.add(root, Box(0, 0, 1080, 220))
        page.add(bar, Box(40, 130, 300, 180), text = true)
        val list = page.add(root, window, scrolls = true)
        val line = page.add(list, Box(40, 120, 600, 170), text = true)
        assertFalse(page.under(window)[line])
    }

    @Test
    fun `a layer as large as the page is not a bar`() {
        // A see-through layer over everything, holding one floating button.
        val page = Page()
        val root = page.add(-1, window)
        val list = page.add(root, window, scrolls = true)
        val line = page.add(list, Box(40, 1200, 600, 1250), text = true)
        val layer = page.add(root, window)
        page.add(layer, Box(900, 2000, 1040, 2140), text = true)
        assertFalse(page.under(window)[line])
    }

    @Test
    fun `the bar inside a layer as large as the page is found`() {
        val page = Page()
        val root = page.add(-1, window)
        val list = page.add(root, window, scrolls = true)
        val line = page.add(list, Box(40, 2250, 600, 2300), text = true)
        val layer = page.add(root, window)
        val bottom = page.add(layer, Box(0, 2200, 1080, 2400))
        page.add(bottom, Box(700, 2240, 1000, 2330), text = true)
        assertTrue(page.under(window)[line])
    }

    @Test
    fun `a label in the same card as a title does not hide the title`() {
        val page = Page()
        val root = page.add(-1, window)
        val list = page.add(root, window, scrolls = true)
        val card = page.add(list, Box(0, 300, 540, 1100))
        val title = page.add(card, Box(20, 900, 520, 990), text = true)
        val badges = page.add(card, Box(0, 300, 540, 1100))
        page.add(badges, Box(20, 900, 110, 940), text = true)
        assertFalse(page.under(window)[title])
    }

    @Test
    fun `text that is in no list is under nothing`() {
        val page = Page()
        val root = page.add(-1, window)
        val line = page.add(root, Box(40, 120, 600, 170), text = true)
        val bar = page.add(root, Box(0, 0, 1080, 220))
        page.add(bar, Box(40, 130, 300, 180), text = true)
        assertFalse(page.under(window)[line])
    }
}
