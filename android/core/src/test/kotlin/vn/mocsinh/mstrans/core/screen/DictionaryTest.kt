package vn.mocsinh.mstrans.core.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChineseTextTest {
    @Test
    fun recognisesHan() {
        assertTrue(ChineseText.hasHan("购物车"))
        assertTrue(ChineseText.hasHan("¥199 包邮"))
        assertFalse(ChineseText.hasHan("¥199.00 -20%"))
        assertFalse(ChineseText.hasHan("，。！？"))
        assertFalse(ChineseText.hasHan("Giỏ hàng"))
    }

    @Test
    fun traditionalBecomesSimplified() {
        assertEquals("购物车", ChineseText.toSimplified("購物車"))
        assertEquals("退出登录", ChineseText.toSimplified("退出登錄"))
        assertEquals("注销账号", ChineseText.toSimplified("註銷賬號"))
        assertEquals("¥199 运费", ChineseText.toSimplified("¥199 運費"))
        // Already simplified, Latin and digits are left alone.
        assertEquals("Taobao 123 设置", ChineseText.toSimplified("Taobao 123 设置"))
    }

    @Test
    fun keyIgnoresSpacesAndTrailingColon() {
        assertEquals("收货地址", ChineseText.key("  收货 地址： "))
        assertEquals("规格", ChineseText.key("規格:"))
    }

    @Test
    fun runsSplitHanFromTheRest() {
        assertEquals(
            listOf("颜色" to true, ": XL / " to false, "红色" to true),
            ChineseText.runs("颜色: XL / 红色"),
        )
    }

    @Test
    fun signatureIgnoresDigits() {
        val a = ChineseText.signature(listOf("距结束 01:59:58", "秒杀"))
        val b = ChineseText.signature(listOf("距结束 01:59:57", "秒杀"))
        val c = ChineseText.signature(listOf("距结束 01:59:57", "预售"))
        assertEquals(a, b)
        assertTrue(a != c)
    }
}

class CommerceDictionaryTest {
    private val dictionary = CommerceDictionary()

    /** The 30 terms of the request, word for word. */
    private val required = linkedMapOf(
        "我的淘宝" to "Taobao của tôi", "设置" to "Cài đặt", "退出登录" to "Đăng xuất",
        "退出当前账户" to "Thoát tài khoản hiện tại", "切换账户" to "Chuyển tài khoản", "注销账号" to "Xóa tài khoản",
        "登录" to "Đăng nhập", "我的订单" to "Đơn hàng của tôi", "待付款" to "Chờ thanh toán",
        "待发货" to "Chờ gửi hàng", "待收货" to "Chờ nhận hàng", "收货地址" to "Địa chỉ nhận hàng",
        "购物车" to "Giỏ hàng", "加入购物车" to "Thêm vào giỏ hàng", "立即购买" to "Mua ngay",
        "起批量" to "Số lượng nhập tối thiểu", "一件代发" to "Gửi hàng lẻ trực tiếp", "混批" to "Nhập sỉ kết hợp nhiều mẫu",
        "现货" to "Hàng có sẵn", "预售" to "Hàng đặt trước", "包邮" to "Miễn phí vận chuyển",
        "不含税" to "Chưa bao gồm thuế", "含税" to "Đã bao gồm thuế", "规格" to "Quy cách",
        "库存" to "Tồn kho", "型号" to "Mã mẫu", "物流" to "Vận chuyển",
        "运费" to "Phí vận chuyển", "退款" to "Hoàn tiền", "售后" to "Hậu mãi",
    )

    @Test
    fun allThirtyRequiredTerms() {
        assertEquals(30, required.size)
        assertEquals(required, CommerceDictionary.REQUIRED)
        for ((chinese, vietnamese) in required) assertEquals(vietnamese, dictionary.lookup(chinese), chinese)
    }

    @Test
    fun traditionalAndSpacesFindTheSameEntry() {
        assertEquals("Giỏ hàng", dictionary.lookup(" 購物車 "))
        assertEquals("Xóa tài khoản", dictionary.lookup("註銷賬號"))
        assertEquals("Đăng xuất", dictionary.lookup("退出登錄"))
        assertEquals("Quy cách", dictionary.lookup("规格："))
    }

    @Test
    fun patternsKeepNumbersAsWritten() {
        assertEquals("Mua đủ 199 giảm 20", dictionary.lookup("满199减20"))
        assertEquals("1.2 vạn+ người đã mua", dictionary.lookup("1.2万+人付款"))
        assertEquals("Đã bán 3000+", dictionary.lookup("已售3000+"))
        assertEquals("Bán 500+/tháng", dictionary.lookup("月销500+"))
        assertEquals("Sỉ từ 2 cái", dictionary.lookup("2件起批"))
        assertEquals("Gửi hàng trong 48 giờ", dictionary.lookup("48小时内发货"))
        assertEquals("Gửi hàng trong 7 ngày", dictionary.lookup("7天内发货"))
        assertEquals("Tổng 3 sản phẩm", dictionary.lookup("共3件"))
        assertEquals("1,299 đánh giá", dictionary.lookup("1,299条评价"))
        assertEquals("Sau phiếu giảm ¥89.90", dictionary.lookup("券后¥89.90"))
    }

    @Test
    fun unknownTextIsNotGuessed() {
        assertNull(dictionary.lookup("这款衣服很好看"))
        assertNull(dictionary.lookup("满减"))
    }

    @Test
    fun termsLongestFirst() {
        val terms = dictionary.terms()
        assertTrue(terms.indexOfFirst { it.first == "加入购物车" } < terms.indexOfFirst { it.first == "购物车" })
        assertTrue(terms.all { ChineseText.hasHan(it.first) })
    }
}

class NumberGuardTest {
    @Test
    fun findsPricesPercentagesUnitsAndCodes() {
        assertEquals(
            listOf("¥199.00", "-20%", "50*30cm", "2.5kg", "XL", "A12-B"),
            NumberGuard.tokens("价格¥199.00，优惠-20%，尺寸50*30cm，重量2.5kg，尺码XL，型号A12-B"),
        )
        assertEquals(listOf("¥89"), NumberGuard.tokens("券后￥89"))
        assertEquals(listOf("1,299"), NumberGuard.tokens("１,２９９条"))
    }

    @Test
    fun everyTokenMustSurvive() {
        assertTrue(NumberGuard.kept("满199减20 尺码XL", "Mua đủ 199 giảm 20, cỡ XL"))
        assertFalse(NumberGuard.kept("价格¥1,299", "Giá ¥1.299"))
        assertFalse(NumberGuard.kept("优惠-20%", "Giảm 20%"))
        assertFalse(NumberGuard.kept("型号A12-B", "Mẫu A12"))
        assertFalse(NumberGuard.kept("2件 2件", "2 cái"))
        assertTrue(NumberGuard.kept("价格", "Giá"))
    }

    @Test
    fun aCodeWrittenInOtherCapitalsIsTheSameCode() {
        // Seen on the emulator: a good title thrown away for "ProMax" written "Promax".
        assertTrue(NumberGuard.kept("适用iPhone18ProMax手机", "Áp dụng điện thoại iPhone18Promax"))
        assertTrue(NumberGuard.kept("尺码XL", "Cỡ xl"))
        assertFalse(NumberGuard.kept("型号A12-B", "Mẫu A12-C"))
    }

    @Test
    fun tenThousandsMayBeWrittenOut() {
        assertTrue(NumberGuard.kept("店铺年销售超1万件", "Cửa hàng bán trên 10.000 sản phẩm"))
        assertTrue(NumberGuard.kept("1.2万+人付款", "Hơn 12,000+ người đã mua"))
        assertTrue(NumberGuard.kept("超3万", "hơn 30000"))
        assertTrue(NumberGuard.kept("超1万件", "trên 1 vạn"))
        assertFalse(NumberGuard.kept("超1万件", "trên 1.000"))
        // A "+" is kept: "400+" is not "400".
        assertFalse(NumberGuard.kept("近7天400+人逛过", "Gần 7 ngày 400 người đã qua"))
    }
}
