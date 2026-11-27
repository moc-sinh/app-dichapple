package vn.mocsinh.mstrans.core.screen

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Chỉ dịch phần chính" (a phone on Taobao and 1688, 27/11/2026): offers and counts are left as they are. */
class ScreenFocusTest {
    @Test
    fun `offers, delivery promises, counts and shop badges are minor`() {
        for (line in listOf(
            "满¥99包邮", "¥99跨境包邮", "红包省20元", "红包省20元 抵1.2元", "超级立减27.35元", "约 $ 45.66", "券后约 $ 27.22-205.63",
            "¥302 优惠后 43人付款", "200+人付款", "已售 300+", "平台加补后", "优惠前", "包邮 48小时内发货", "预计发货后12-15日送达",
            "官方跨境服务·丢损必赔", "官方直邮-陆运", "满¥21减¥20红包限时可用", "支付宝立减20元", "下单运费最高立减￥30", "可再享:",
            "10年老店 金珂华官方店 >", "安衡官方店 进店 >", "店铺19年老店", "店铺电子秤热销第1名", "好评人数100+", "3.15优惠后",
            // A sale at an hour (Taobao, 27/11/2026: "秒杀限时降39元" came out "2 vụ giết 39 tệ").
            "今晚8点秒杀价", "秒杀限时降39元", "今晚8点开卖",
        )) assertTrue(ScreenFocus.minor(line), line)
    }

    @Test
    fun `what a thing is and how to choose it is main`() {
        for (line in listOf(
            "无线蓝牙电子秤可定制厂家直营", "夏季新款连衣裙包邮", "儿童书包 包邮", "已选“加装RS232通讯（选配功能）”", "称重3kg/精度0.01g", "有货",
            "加入购物车", "领券购买", "进店", "进店逛逛", "评价·100+", "问大家·2", "已购 称重3kg/精度0.01g", "适应多种场景", "放阳台",
            "宝贝", "详情", "店铺", "及时发货率98%", "降噪蓝牙耳机 限时特价",
        )) assertFalse(ScreenFocus.minor(line), line)
    }

    @Test
    fun `slips of the offline translator seen on Taobao are put right`() = runBlocking {
        val engine = TextEngine { text ->
            when (text) {
                "店铺电子秤回购榜" -> "Bảng xếp hạng mua lại cân điện tử lưu trữ"
                "加装蓝牙通讯模块" -> "Cộng với mô-đun giao tiếp Bluetooth"
                "电子秤台 150.30元" -> "Nền tảng cân điện tử 150.30 nhân dân tệ"
                else -> null
            }
        }
        val translator = ScreenTranslator(engine = engine)
        assertEquals("Bảng xếp hạng mua lại cân điện tử cửa hàng", translator.translate("店铺电子秤回购榜")?.text)
        assertEquals("Lắp thêm mô-đun giao tiếp Bluetooth", translator.translate("加装蓝牙通讯模块")?.text)
        assertEquals("Nền tảng cân điện tử 150.30 tệ", translator.translate("电子秤台 150.30元")?.text)
        assertEquals("Cửa hàng 19 năm", translator.quick("店铺19年老店")?.text)
        assertEquals("Đánh giá · 100+", translator.quick("评价·100+")?.text)
    }
}
