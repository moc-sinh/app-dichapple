package vn.mocsinh.mstrans.core.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenBlocksTest {
    @Test
    fun `the lines of a wrapped sentence are one text`() {
        val texts = ScreenBlocks.texts(
            listOf("本产品采用优质材料制作" to Box(40, 100, 440, 140), "经久耐用" to Box(40, 150, 200, 190)),
        )
        val text = texts.single()
        assertEquals("本产品采用优质材料制作经久耐用", text.text)
        assertEquals(Box(40, 100, 440, 190), text.bounds)
        assertEquals(listOf(Box(40, 100, 440, 140), Box(40, 150, 200, 190)), text.geometry!!.lines)
        assertEquals(40f, text.geometry!!.sizePx)
        assertTrue(text.fromImage)
    }

    @Test
    fun `a headline over a smaller line stays two texts`() {
        val texts = ScreenBlocks.texts(
            listOf("一秒认出苹果全系" to Box(40, 100, 360, 140), "专属守护即刻起航" to Box(40, 150, 300, 180)),
        )
        assertEquals(listOf("一秒认出苹果全系", "专属守护即刻起航"), texts.map { it.text })
        assertEquals(30f, texts[1].geometry!!.sizePx)
    }

    @Test
    fun `two short lines of their own stay two texts`() {
        val texts = ScreenBlocks.texts(
            listOf("处处贴心" to Box(40, 100, 200, 140), "绝配超乎想象" to Box(40, 150, 280, 190)),
        )
        assertEquals(2, texts.size)
    }

    @Test
    fun `one line is one text at its own place`() {
        val text = ScreenBlocks.texts(listOf("低温快充中" to Box(120, 400, 320, 436))).single()
        assertEquals(Box(120, 400, 320, 436), text.bounds)
        assertEquals(TextGeometry(listOf(Box(120, 400, 320, 436)), 36f), text.geometry)
    }

    @Test
    fun `words in other letters keep a space between lines`() {
        val text = ScreenBlocks.texts(
            listOf("适用于苹果华为小米 iPhone" to Box(40, 100, 440, 140), "Pro 系列" to Box(40, 150, 200, 190)),
        ).single()
        assertEquals("适用于苹果华为小米 iPhone Pro 系列", text.text)
    }
}
