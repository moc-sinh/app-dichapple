package vn.mocsinh.mstrans.core.screen

/**
 * "Chỉ dịch phần chính": what a shopper needs translated is what a thing is and how to choose it
 * (its name, its variants, its specifications, the reviews, the buttons). The rest of a list of
 * goods is offers and counts the user said they need no translation for (a phone, 27/11/2026: "kể cả
 * những chỗ freeship"): free delivery, red packets, coupons, discounts, subsidies, the price in
 * dollars, how many bought, how old the shop is. Translated, they cover the screen with patches.
 */
object ScreenFocus {
    /**
     * A line that is only an offer, a delivery promise or a count. A product's title with 包邮 in
     * it is not: more than one letter of other Chinese is left once the words of offers are taken out.
     */
    fun minor(text: String): Boolean {
        val key = ChineseText.key(text)
        if (!ChineseText.hasHan(key)) return false
        // A badge of a shop ("10年老店 金珂华官方店 >", "安衡官方店 进店 >") or a rank ("热销第1名"): minor whatever follows.
        if (SHOP_BADGE.containsMatchIn(key)) return true
        if (TRIGGERS.none { it in key } && !COUNTS.containsMatchIn(key) && !DOLLARS.containsMatchIn(key)) return false
        var rest = key
        for (word in WORDS) rest = rest.replace(word, "")
        return rest.count { ChineseText.isHan(it.code) } <= 1
    }

    /** Words only offers, delivery promises and counts are made of (longest first). */
    private val WORDS: List<String> = (
        "跨境包邮 包邮 顺丰 免运费 运费险 退货包运费 运费 下单 最高 官方 跨境 服务 丢损必赔 直邮 陆运 空运 海运 预计 发货 送达 小时 " +
            "红包 立减 超级 直降 降价 优惠券 优惠 领券 券后 券 补贴 百亿 平台 加补 可再享 再享 支付宝 首单 新人 专享 限时 可用 折扣 " +
            "约 元 省 抵 满 减 后 前 起 内 天 日 人付款 付款 人 已买 已购 加购 逛过 收藏 看过 已售 月销 回头客 回头率 好评人数 好评"
        ).split(' ').sortedByDescending { it.length }

    /** At least one of these: the line is about an offer or delivery (not a title that happens to have 包邮 in it). */
    private val TRIGGERS = listOf(
        "包邮", "运费", "红包", "立减", "直降", "降价", "券", "补贴", "加补", "优惠", "折扣", "跨境", "直邮", "送达", "可再享",
        "人付款", "已售", "月销", "加购", "逛过", "回头客", "回头率", "好评人数",
    )
    private val COUNTS = Regex("\\d+\\+?人")
    private val DOLLARS = Regex("^约[¥￥$]")
    /** "进店" alone is the button to the shop (kept); after a shop's name it is the badge of a shop in a list. */
    private val SHOP_BADGE = Regex("^(?:店铺)?\\d+年老?店|第\\d+名|.进店[>›]?$|^入选.*榜$|^店长主推$")
}
