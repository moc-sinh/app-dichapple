package vn.mocsinh.mstrans.core.screen

/**
 * Shopping words with a fixed Vietnamese translation, read only. Looked up whole (after spaces are
 * taken out and Traditional becomes Simplified), then the patterns with numbers.
 *
 * `terms` are also replaced inside a longer line before the offline translator sees it; `labels`
 * (short everyday words: 确定, 删除…) only ever match a whole line, since inside a sentence they
 * often mean something else.
 */
class CommerceDictionary(
    private val terms: Map<String, String> = REQUIRED + TERMS,
    private val labels: Map<String, String> = LABELS,
    private val required: Map<String, String> = REQUIRED,
    private val industry: Map<String, String> = IndustryTerms.ALL,
) {
    /** What a line may be put together from: every word but the one-letter labels. */
    private val parts: Map<String, String> = labels.filterKeys { it.length >= 2 } + industry + terms
    private val longest = parts.keys.maxOf { it.length }

    private val mustKeep: List<Pair<String, String>> =
        required.entries.map { it.key to it.value }.sortedByDescending { it.first.length }

    private val byLength: List<Pair<String, String>> =
        terms.entries.map { it.key to it.value }.sortedByDescending { it.first.length }

    /** The translation of the whole line, or null when it is not a known word or pattern. */
    fun lookup(text: String, putTogether: Boolean = true): String? {
        val key = ChineseText.key(text)
        if (key.isEmpty()) return null
        if (key in IndustryTerms.KEEP) return key
        return terms[key] ?: labels[key] ?: industry[key] ?: pattern(key) ?: withArrow(key) ?: if (putTogether) together(text) else null
    }

    /** Words of three letters or more, by each one with one letter left out (as `?`): what a misread one may have been. */
    private val nearly: Map<String, String?> = HashMap<String, String?>().also { map ->
        for (word in parts.keys) {
            if (word.length < 3 || !word.all { ChineseText.isHan(it.code) }) continue
            for (k in word.indices) {
                val shape = word.substring(0, k) + "?" + word.substring(k + 1)
                // Two words one letter apart: not a sign of either.
                map[shape] = if (map.containsKey(shape) && map[shape] != word) null else word
            }
        }
    }
    private val nearlyLongest = parts.keys.filter { it.length >= 3 }.maxOf { it.length }

    /**
     * Text read in a picture with one letter of a known word misread (on a dark page the reader takes
     * 请 for 靖, 瑁 for 環): the word as it is in the dictionary. Words of three letters or more,
     * one letter off, nothing else like them; text already right is left as it is.
     */
    fun correct(text: String): String {
        val simplified = ChineseText.toSimplified(text)
        if (!ChineseText.hasHan(simplified)) return text
        val out = StringBuilder(simplified)
        var changed = false
        var i = 0
        while (i < out.length) {
            var fixed = 0
            for (n in minOf(nearlyLongest, out.length - i) downTo 3) {
                val window = out.substring(i, i + n)
                if (!window.all { ChineseText.isHan(it.code) } || window in parts) continue
                val found = (0 until n).mapNotNull { k -> nearly[window.substring(0, k) + "?" + window.substring(k + 1)] }.distinct()
                if (found.size != 1) continue
                out.replace(i, i + n, found[0])
                changed = true
                fixed = n
                break
            }
            i += if (fixed > 0) fixed else 1
        }
        // Only where the line, so put right, is all known words: a real word one letter away from
        // a known one (保温桶 is not 保温杯) in a line of other words is left as read.
        val fixed = out.toString()
        if (!changed) return text
        val known = fixed.trim().split(SPACED).filter { ChineseText.hasHan(it) }.all { phrase ->
            lookup(phrase) != null || compose(ChineseText.key(phrase), strict = false) != null
        }
        return if (known) fixed else text
    }

    /**
     * A line of one phrase put together from known words (`compose`, strict). A line of several
     * phrases set apart by spaces is not: each phrase is looked up on its own.
     */
    fun together(text: String): String? {
        if (SPACED.containsMatchIn(text.trim())) return null
        return compose(ChineseText.key(text), strict = true)
    }

    /**
     * A line made only of known words, numbers with their units, signs and Latin letters, put
     * together word by word: "镜框【颜色请备注】+1.56非球面" is "Gọng kính [ghi chú màu] +1.56 phi cầu".
     * Null when a Chinese letter is not covered. `strict`: not when more than two words stand
     * together with nothing between them; that is a phrase whose Vietnamese order is the other
     * way round ("男士纯棉T恤"), better left to the translator, and only then put together.
     */
    fun compose(key: String, strict: Boolean): String? {
        // The same lines are drawn again and again: each is put together once.
        val tag = (if (strict) "1" else "0") + key
        synchronized(composed) { composed[tag] }?.let { return it.takeUnless { it == NONE } }
        val made = composeNow(key, strict)
        synchronized(composed) { composed[tag] = made ?: NONE }
        return made
    }

    private val composed = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 2_000
    }

    private fun composeNow(key: String, strict: Boolean): String? {
        if (!ChineseText.hasHan(key)) return null
        // The line as words, numbers with units, and the rest as written.
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < key.length) {
            val numberStarts = i == 0 || !key[i - 1].isDigit() && key[i - 1] != '.'
            // A known sentence with its numbers, inside the line ("红包省20元" in "红包省20元抵1.2元").
            val phrase = if (numberStarts) phraseAt(key, i) else null
            if (phrase != null) {
                tokens.add(Token(phrase.second, phrase = true))
                i = phrase.first
                continue
            }
            val unit = UNIT.matchAt(key, i)
            if (unit != null && numberStarts) {
                val number = unit.groupValues[1]
                val name = UNIT_NAMES.getValue(unit.groupValues[2])
                tokens.add(Token(if (name == "size") "size $number" else "$number $name", unit = true))
                i = unit.range.last + 1
                continue
            }
            var n = minOf(longest, key.length - i)
            var found: String? = null
            while (n >= 2 && found == null) {
                val part = key.substring(i, i + n)
                found = if (ChineseText.hasHan(part)) parts[part] else null
                if (found == null) n--
            }
            if (found != null) {
                tokens.add(Token(found, head = key.substring(i, i + n) in IndustryTerms.HEADS))
                i += n
                continue
            }
            val cp = key.codePointAt(i)
            if (ChineseText.isHan(cp)) {
                tokens.add(Token(IndustryTerms.GLUE[key[i]] ?: return null))
                i++
                continue
            }
            val raw = String(Character.toChars(cp))
            if (tokens.lastOrNull()?.raw == true) tokens[tokens.lastIndex] = Token(tokens.last().text + raw, raw = true) else tokens.add(Token(raw, raw = true))
            i += Character.charCount(cp)
        }
        // Words standing together: the thing they describe goes first, as Vietnamese says it.
        var start = 0
        while (start < tokens.size) {
            if (!tokens[start].word) {
                start++
                continue
            }
            var end = start
            while (end < tokens.size && tokens[end].word) end++
            if (strict && end - start > MAX_TOGETHER) return null
            val run = tokens.subList(start, end)
            if (run.size > 1 && run.last().head && run.dropLast(1).none { it.head }) {
                val head = run.removeAt(run.lastIndex)
                run.add(0, head)
            }
            start = end
        }
        val out = StringBuilder()
        for (token in tokens) if (token.raw) out.append(token.text) else word(out, token.text, openLeft = token.unit)
        return ScreenTranslator.tidy(out.toString())
    }

    /**
     * A piece of a line being put together. `word`: from the dictionary (not a number, not as
     * written, not a known sentence with its numbers).
     */
    private class Token(val text: String, val head: Boolean = false, val unit: Boolean = false, val raw: Boolean = false, val phrase: Boolean = false) {
        val word: Boolean get() = !unit && !raw && !phrase
    }

    /** The longest known sentence with numbers (`PATTERNS`) that begins at `at`: where it ends, and its Vietnamese. */
    private fun phraseAt(key: String, at: Int): Pair<Int, String>? {
        var best: Pair<Int, String>? = null
        for ((regex, template) in PATTERNS) {
            val matcher = regex.toPattern().matcher(key).region(at, key.length)
            if (!matcher.lookingAt()) continue
            val end = matcher.end()
            if (best != null && end <= best.first) continue
            if (!ChineseText.hasHan(key.substring(at, end)) || !key.substring(at, end).any { it.isDigit() }) continue
            best = end to fill(template, (1..matcher.groupCount()).map { matcher.group(it).orEmpty() })
        }
        return best
    }

    /**
     * A word into a line being put together: with a capital at the start of the line and after a
     * name in capitals ("ASTATRE Đen vàng"), small inside ("gọng kính [ghi chú màu]"); a borrowed
     * word (Bluetooth, Cotton) as it is. `openLeft`: no space before it ("+0 độ").
     */
    private fun word(out: StringBuilder, word: String, openLeft: Boolean = false) {
        val last = out.lastOrNull { it.isLetterOrDigit() }
        val first = word.substringBefore(' ')
        // A value after a colon or a slash starts again: "Màu sắc: Đen".
        val after = out.lastOrNull { !it.isWhitespace() && it != ScreenTranslator.MARK }
        val text = when {
            last == null || after != null && after in ":：/|" -> word.replaceFirstChar { it.uppercaseChar() }
            last.code < 0x80 && last.isUpperCase() -> word
            first in IndustryTerms.BORROWED || first.count { it.isUpperCase() } > 1 -> word
            else -> word.replaceFirstChar { it.lowercaseChar() }
        }
        if (!openLeft) out.append(ScreenTranslator.MARK)
        out.append(text).append(ScreenTranslator.MARK)
    }

    /** A chip or a row that ends with an arrow ("超200人加购 >"): what is before it, the arrow kept. */
    private fun withArrow(key: String): String? {
        val arrow = key.takeLastWhile { it in ARROWS }
        if (arrow.isEmpty() || arrow.length == key.length) return null
        val body = key.dropLast(arrow.length)
        val done = terms[body] ?: labels[body] ?: industry[body] ?: pattern(body) ?: return null
        return "$done $arrow"
    }

    /** A shorter word or sentence for a place too narrow for the usual one (a label under an icon, a chip); null: none. */
    fun short(text: String): String? {
        val key = ChineseText.key(text)
        SHORT[key]?.let { return it }
        IndustryTerms.SHORT[key]?.let { return it }
        pattern(key, SHORT_PATTERNS)?.let { return it }
        val arrow = key.takeLastWhile { it in ARROWS }
        if (arrow.isEmpty() || arrow.length == key.length) return null
        val body = key.dropLast(arrow.length)
        return (SHORT[body] ?: pattern(body, SHORT_PATTERNS))?.let { "$it $arrow" }
    }

    /** The terms a translation must keep word for word, longest first. */
    fun required(): List<Pair<String, String>> = mustKeep

    /** Terms to replace inside a line, longest first ("加入购物车" before "购物车"). */
    fun terms(): List<Pair<String, String>> = byLength

    private fun pattern(key: String, patterns: List<Pair<Regex, String>> = PATTERNS): String? {
        for ((regex, template) in patterns) {
            val match = regex.matchEntire(key) ?: continue
            return fill(template, match.groupValues.drop(1))
        }
        return null
    }

    /** `{1}`: the first number as written; `{U2}`: the second group, a unit, in Vietnamese ("台" is "chiếc"). */
    private fun fill(template: String, groups: List<String>): String {
        var out = template
        groups.forEachIndexed { i, value ->
            out = out.replace("{${i + 1}}", number(value)).replace("{U${i + 1}}", UNIT_NAMES[value] ?: value)
        }
        return out
    }

    /** Digits as written; 万 (ten thousand) is said "vạn". */
    private fun number(value: String) = value.replace("万", " vạn").replace("千", " nghìn")

    companion object {
        /** The 30 terms of the request, word for word. */
        val REQUIRED: Map<String, String> = linkedMapOf(
            "我的淘宝" to "Taobao của tôi",
            "设置" to "Cài đặt",
            "退出登录" to "Đăng xuất",
            "退出当前账户" to "Thoát tài khoản hiện tại",
            "切换账户" to "Chuyển tài khoản",
            "注销账号" to "Xóa tài khoản",
            "登录" to "Đăng nhập",
            "我的订单" to "Đơn hàng của tôi",
            "待付款" to "Chờ thanh toán",
            "待发货" to "Chờ gửi hàng",
            "待收货" to "Chờ nhận hàng",
            "收货地址" to "Địa chỉ nhận hàng",
            "购物车" to "Giỏ hàng",
            "加入购物车" to "Thêm vào giỏ hàng",
            "立即购买" to "Mua ngay",
            "起批量" to "Số lượng nhập tối thiểu",
            "一件代发" to "Gửi hàng lẻ trực tiếp",
            "混批" to "Nhập sỉ kết hợp nhiều mẫu",
            "现货" to "Hàng có sẵn",
            "预售" to "Hàng đặt trước",
            "包邮" to "Miễn phí vận chuyển",
            "不含税" to "Chưa bao gồm thuế",
            "含税" to "Đã bao gồm thuế",
            "规格" to "Quy cách",
            "库存" to "Tồn kho",
            "型号" to "Mã mẫu",
            "物流" to "Vận chuyển",
            "运费" to "Phí vận chuyển",
            "退款" to "Hoàn tiền",
            "售后" to "Hậu mãi",
        )

        /** Further shopping terms (reviewed 26/11/2026), replaced inside lines too. */
        val TERMS: Map<String, String> = linkedMapOf(
            // Account and settings
            "账号与安全" to "Tài khoản và bảo mật", "个人信息" to "Thông tin cá nhân", "消息通知" to "Thông báo tin nhắn",
            "清除缓存" to "Xóa bộ nhớ đệm", "帮助与反馈" to "Trợ giúp và phản hồi", "修改密码" to "Đổi mật khẩu",
            "验证码" to "Mã xác minh", "退出当前账号" to "Thoát tài khoản hiện tại", "切换账号" to "Chuyển tài khoản",
            "注销账户" to "Xóa tài khoản",
            // Shopping
            "商品详情" to "Chi tiết sản phẩm", "旗舰店" to "Cửa hàng chính hãng", "进店" to "Vào cửa hàng",
            "问大家" to "Hỏi người đã mua", "优惠券" to "Phiếu giảm giá", "领券" to "Nhận phiếu giảm giá",
            "红包" to "Lì xì", "秒杀" to "Flash sale", "正品" to "Hàng chính hãng", "提交订单" to "Đặt hàng",
            "支付宝" to "Alipay", "颜色" to "Màu sắc", "尺码" to "Cỡ", "尺寸" to "Kích thước", "材质" to "Chất liệu",
            "品牌" to "Thương hiệu", "产地" to "Xuất xứ", "重量" to "Trọng lượng", "参数" to "Thông số",
            // Orders and delivery
            "待评价" to "Chờ đánh giá", "已发货" to "Đã gửi hàng", "确认收货" to "Xác nhận đã nhận hàng",
            "查看物流" to "Xem vận chuyển", "快递" to "Chuyển phát nhanh", "运费险" to "Bảo hiểm phí vận chuyển",
            "退货" to "Trả hàng", "换货" to "Đổi hàng", "七天无理由退货" to "Đổi trả trong 7 ngày không cần lý do",
            // Buying stock (1688)
            "供应商" to "Nhà cung cấp", "厂家" to "Nhà sản xuất", "源头工厂" to "Xưởng gốc", "批发" to "Bán sỉ",
            "起订量" to "Số lượng đặt tối thiểu", "代发" to "Gửi hàng hộ", "定制" to "Đặt làm riêng", "询价" to "Hỏi giá",
            "发票" to "Hóa đơn", "开票" to "Xuất hóa đơn", "增值税" to "Thuế GTGT", "样品" to "Hàng mẫu", "拿样" to "Lấy mẫu",
            // Offers and delivery (from real Taobao pages, 26/11/2026)
            "全场包邮" to "Miễn phí vận chuyển toàn shop", "已享包邮" to "Đã được miễn phí vận chuyển", "到手价" to "Giá cuối",
            "限时特价" to "Giá ưu đãi có hạn", "新品上市" to "Hàng mới ra mắt", "买二送一" to "Mua 2 tặng 1", "买一送一" to "Mua 1 tặng 1",
            "官方物流" to "Vận chuyển chính hãng",
            // Taobao's own pages (read off a phone, 26/11/2026)
            "官方跨境服务" to "Dịch vụ xuyên biên giới chính thức", "丢损必赔" to "Mất hỏng đền bù", "官方直邮" to "Gửi thẳng chính thức",
            "官方正品" to "Hàng chính hãng", "中国大陆" to "Trung Quốc đại lục",
            // Colours (the rows choosing a colour and a size need no translator then)
            "红色" to "Đỏ", "黑色" to "Đen", "白色" to "Trắng", "蓝色" to "Xanh dương", "绿色" to "Xanh lá", "黄色" to "Vàng",
            "粉色" to "Hồng", "灰色" to "Xám", "紫色" to "Tím", "橙色" to "Cam", "棕色" to "Nâu", "米色" to "Be", "卡其色" to "Kaki",
            "银色" to "Bạc", "金色" to "Vàng kim", "藏青色" to "Xanh than", "酒红色" to "Đỏ rượu vang", "天蓝色" to "Xanh da trời",
            "颜色分类" to "Phân loại màu",
            // Names of the shops themselves. Names of goods are left to the translator: tried on a
            // phone, the more words are put into a product's title for it, the more of them it loses.
            "淘宝" to "Taobao", "天猫" to "Tmall", "京东" to "JD", "拼多多" to "Pinduoduo",
        )

        /** Short everyday words: only a whole line ("确定" alone is the OK button). */
        val LABELS: Map<String, String> = linkedMapOf(
            "隐私" to "Quyền riêng tư", "通用" to "Cài đặt chung", "注册" to "Đăng ký", "密码" to "Mật khẩu",
            "确定" to "Đồng ý", "确认" to "Xác nhận", "取消" to "Hủy", "返回" to "Quay lại", "完成" to "Xong",
            "保存" to "Lưu", "编辑" to "Sửa", "删除" to "Xóa", "搜索" to "Tìm kiếm", "分享" to "Chia sẻ",
            "首页" to "Trang chủ", "推荐" to "Đề xuất", "分类" to "Danh mục", "全部" to "Tất cả", "筛选" to "Lọc",
            "综合" to "Tổng hợp", "销量" to "Lượt bán", "价格" to "Giá", "新品" to "Hàng mới", "店铺" to "Cửa hàng",
            "宝贝" to "Sản phẩm", "商品" to "Sản phẩm", "评价" to "Đánh giá", "收藏" to "Lưu yêu thích",
            "关注" to "Theo dõi", "足迹" to "Đã xem", "结算" to "Thanh toán", "付款" to "Thanh toán", "数量" to "Số lượng",
            "我的" to "Của tôi", "消息" to "Tin nhắn", "客服" to "Chăm sóc khách hàng",
            "约" to "≈", "已售" to "Đã bán", "详情" to "Chi tiết",
            // Taobao's own pages (read off a phone, 26/11/2026). My Taobao:
            "关注店铺" to "Cửa hàng theo dõi", "红包卡券" to "Lì xì & phiếu giảm giá", "跨境工具" to "Công cụ mua hàng quốc tế",
            "待集运" to "Chờ gom hàng", "直邮包裹" to "Kiện gửi thẳng", "支付设置" to "Cài đặt thanh toán", "物流客服" to "CSKH vận chuyển",
            "物流中心" to "Trung tâm vận chuyển", "淘金币" to "Xu Taobao", "芭芭农场" to "Nông trại Baba", "短剧" to "Phim ngắn",
            "官方客服" to "CSKH chính thức", "退款/售后" to "Hoàn tiền/Hậu mãi", "领券购买" to "Nhận phiếu & mua",
            "咨询客服" to "Hỏi CSKH",
            // Searching
            "历史搜索" to "Lịch sử tìm kiếm", "猜你想搜" to "Gợi ý tìm kiếm", "为你推荐" to "Đề xuất cho bạn", "语音搜索" to "Tìm bằng giọng nói",
            "反馈" to "Góp ý", "女装" to "Thời trang nữ", "男装" to "Thời trang nam", "住宅家具" to "Nội thất nhà ở", "潮玩盲盒" to "Đồ chơi & hộp mù",
            "手机配件" to "Phụ kiện điện thoại", "口腔护理" to "Chăm sóc răng miệng", "女士休闲裤" to "Quần nữ thường ngày", "盲盒" to "Hộp mù",
            "跑步鞋" to "Giày chạy bộ", "男士夹克" to "Áo khoác nam", "穿搭" to "Phối đồ", "家具" to "Nội thất", "运动户外" to "Thể thao & dã ngoại",
            "百货" to "Bách hóa", "数码" to "Đồ điện tử", "美妆" to "Mỹ phẩm", "母婴" to "Mẹ & bé", "食品" to "Thực phẩm", "家电" to "Điện gia dụng",
            "鞋包" to "Giày & túi", "外卖打包" to "Đóng gói mang đi", "防油" to "Chống dầu", "耐高温" to "Chịu nhiệt",
            // A product's page
            "单品购买" to "Mua lẻ", "组合套装" to "Mua theo bộ", "推荐搭配清单" to "Gợi ý phối bộ", "图集" to "Bộ ảnh", "宝贝讲解" to "Giới thiệu sản phẩm",
            "可再享" to "Ưu đãi thêm", "支付宝支付满减" to "Trả bằng Alipay được giảm", "看对比" to "So sánh", "省份" to "Tỉnh", "地市" to "Thành phố",
            "查看全部" to "Xem tất cả", "查看更多" to "Xem thêm", "匿名买家" to "Người mua ẩn danh", "已购" to "Đã mua", "提问" to "Đặt câu hỏi",
            "进店逛逛" to "Vào xem cửa hàng", "全部宝贝" to "Tất cả sản phẩm", "宝贝质量" to "Chất lượng hàng", "物流速度" to "Tốc độ giao hàng",
            "服务保障" to "Bảo đảm dịch vụ", "粉丝" to "Người theo dõi", "顶部" to "Lên đầu", "陆运" to "Đường bộ", "空运" to "Đường hàng không",
            "海运" to "Đường biển", "高" to "Cao", "中" to "Trung bình", "低" to "Thấp", "起" to "trở lên",
            "补贴后" to "sau trợ giá", "优惠后" to "sau ưu đãi", "券后" to "sau phiếu giảm", "到手" to "giá cuối",
        )

        /** Shorter words for places too narrow for the usual ones: labels under icons, tabs. */
        val SHORT: Map<String, String> = linkedMapOf(
            "客服" to "CSKH", "收藏" to "Lưu", "店铺" to "Shop", "消息" to "Tin", "分类" to "Mục", "购物车" to "Giỏ",
            "加入购物车" to "Thêm vào giỏ", "我的淘宝" to "Của tôi", "包邮" to "Freeship", "运动户外" to "Thể thao", "关注店铺" to "Theo dõi",
            "红包卡券" to "Lì xì", "官方客服" to "CSKH", "物流客服" to "CSKH", "直邮包裹" to "Gửi thẳng", "支付设置" to "Thanh toán",
            "物流中心" to "Vận chuyển", "退款/售后" to "Hoàn tiền", "待付款" to "Chờ trả", "待发货" to "Chờ gửi", "待收货" to "Chờ nhận",
            "待评价" to "Chờ đ.giá", "立即购买" to "Mua ngay", "首页" to "T.chủ", "品牌" to "Hãng", "外卖打包" to "Mang đi",
            "推荐搭配清单" to "Gợi ý bộ", "为你推荐" to "Gợi ý", "查看全部" to "Tất cả", "查看更多" to "Thêm",
            "进店" to "Vào shop", "旗舰店" to "Shop chính hãng", "跨境包邮" to "Freeship QT", "全场包邮" to "Freeship cả shop",
            "官方跨境服务" to "Dịch vụ QT chính thức", "七天无理由退货" to "Đổi trả 7 ngày",
        )

        private const val ARROWS = ">›»"

        private val UNIT_NAMES: Map<String, String> = IndustryTerms.UNITS.toMap()
        private val SPACED = Regex("\\s")

        /** At most this many words standing together are put together without the translator. */
        private const val MAX_TOGETHER = 6

        /** A number, or a range of them, and its unit, the longest unit first. */
        private val UNIT = Regex("(\\d+(?:\\.\\d+)?(?:[-~～]\\d+(?:\\.\\d+)?)?)(" + IndustryTerms.UNITS.joinToString("|") { it.first } + ")")

        /** A number as written: sign, currency, digits with separators, 万, "+" or "%". */
        private const val N = "([-−]?[¥￥$]?\\d+(?:[.,]\\d+)*(?:万|千)?[+%]?)"

        private val PATTERNS: List<Pair<Regex, String>> = listOf(
            "满${N}(?:元)?减${N}(?:元)?" to "Mua đủ {1} giảm {2}",
            "${N}人付款" to "{1} người đã mua",
            "已售${N}(?:件)?" to "Đã bán {1}",
            "月销${N}(?:件)?" to "Bán {1}/tháng",
            "${N}件起批" to "Sỉ từ {1} cái",
            "${N}天内发货" to "Gửi hàng trong {1} ngày",
            "${N}小时内发货" to "Gửi hàng trong {1} giờ",
            "共${N}件(?:商品)?" to "Tổng {1} sản phẩm",
            "${N}条评价" to "{1} đánh giá",
            "券后(?:价)?${N}(?:元)?" to "Sau phiếu giảm {1}",
            "距结束(\\d{1,2}:\\d{2}(?::\\d{2})?)" to "Còn {1}",
            "库存${N}(?:件)?" to "Tồn kho {1}",
            "仅剩${N}(?:件)?" to "Chỉ còn {1}",
            "预计发货后(\\d+)-(\\d+)日送达" to "Dự kiến giao sau {1}-{2} ngày kể từ khi gửi",
            "超${N}人加购" to "Hơn {1} người thêm vào giỏ",
            "${N}人加购" to "{1} người thêm vào giỏ",
            "近(\\d+)天${N}人逛过" to "{2} người đã xem trong {1} ngày",
            "${N}人逛过" to "{1} người đã xem",
            "店铺年销(?:售|量)?超${N}(?:件)?" to "Cửa hàng bán hơn {1} sản phẩm/năm",
            "满${N}(?:元)?包邮" to "Đủ {1} miễn phí vận chuyển",
            "(?:超级)?立减${N}元" to "Giảm ngay {1} tệ",
            "(?:超级)?立减${N}" to "Giảm ngay {1}",
            "官方立减${N}" to "Giảm chính hãng {1}",
            "到手价${N}(?:元)?" to "Giá cuối {1}",
            "限时${N}" to "Ưu đãi có hạn {1}",
            // Taobao's own pages (read off a phone, 26/11/2026)
            "约([¥￥$]?\\d+(?:[.,]\\d+)*(?:-\\d+(?:[.,]\\d+)*)?)" to "≈ {1}",
            "店铺一年回头客${N}" to "{1} khách quay lại/năm",
            "回头客${N}" to "{1} khách quay lại",
            "(\\d+)年老店" to "Cửa hàng {1} năm",
            "下单运费最高立减${N}" to "Đặt hàng giảm phí vận chuyển tới {1}",
            "评价[·・.]?${N}" to "Đánh giá · {1}",
            "好评人数${N}" to "{1} đánh giá tốt",
            "店铺(\\d+)年老店" to "Cửa hàng {1} năm",
            "问大家[·・.]?${N}" to "Hỏi người đã mua · {1}",
            "组合再省${N}元" to "Mua bộ giảm thêm {1} tệ",
            "抵${N}元" to "trừ {1} tệ",
            "红包省${N}元" to "Lì xì giảm {1} tệ",
            "直降${N}元" to "Giảm thẳng {1} tệ",
            "首单立减${N}元" to "Đơn đầu giảm {1} tệ",
            "满(\\d+)件减${N}元" to "Mua {1} món giảm {2} tệ",
            "${N}跨境包邮" to "Miễn phí vận chuyển quốc tế từ {1}",
            "${N}粉丝" to "{1} người theo dõi",
            "官方跨境服务[·・]丢损必赔" to "Dịch vụ xuyên biên giới chính thức · Mất hỏng đền bù",
            "官方直邮[-－]陆运" to "Gửi thẳng chính thức - Đường bộ",
            "官方直邮[-－]空运" to "Gửi thẳng chính thức - Đường hàng không",
            // Lists of goods on 1688 and Taobao (read off a phone, 27/11/2026)
            "${N}(件|个|套|只|台|双|条|张|把|包|箱|盒|瓶|米|公斤|千克|斤|kg)起批" to "Sỉ từ {1} {U2}",
            "${N}(件|个|套|只|台|双|条|张|把|包|箱|盒|瓶|米|公斤|千克|斤|kg)起订" to "Đặt tối thiểu {1} {U2}",
            "${N}(件|个|套|只|台|双|条|张|把|包|箱|盒|瓶)起购" to "Mua tối thiểu {1} {U2}",
            "${N}人(?:已)?(?:购买|已买|已购|买过)" to "{1} người đã mua",
            "${N}人(?:看过|浏览|在看)" to "{1} người đã xem",
            "${N}人(?:评价|好评)" to "{1} người đánh giá",
            "${N}人收藏" to "{1} người lưu",
            "已(?:有)?${N}人(?:购买|买过|付款)" to "{1} người đã mua",
            "回头率${N}" to "Tỷ lệ khách quay lại {1}",
            "复购率${N}" to "Tỷ lệ mua lại {1}",
            "好评率${N}" to "Tỷ lệ đánh giá tốt {1}",
            "揽收率${N}" to "Tỷ lệ lấy hàng {1}",
            "履约率${N}" to "Tỷ lệ giao đúng hẹn {1}",
            "成交${N}(?:万)?元" to "Đã giao dịch {1} tệ",
            "成交${N}(?:笔|单)" to "{1} giao dịch",
            "${N}小时(?:内)?揽收" to "Lấy hàng trong {1} giờ",
            "${N}(?:小时|h|H)(?:内)?发货" to "Gửi hàng trong {1} giờ",
            "(\\d+)天(?:内)?发货" to "Gửi hàng trong {1} ngày",
            "诚信通(\\d+)年" to "Thành viên uy tín {1} năm",
            "(\\d+)年(?:质保|保修)" to "Bảo hành {1} năm",
            "(\\d+)天无理由(?:退货|退换|退换货)?" to "Đổi trả {1} ngày không cần lý do",
            "${N}元?起" to "Từ {1}",
            "(?:已降|降价|直降)${N}元?" to "Đã giảm {1}",
            "(?:共)?省${N}元" to "Tiết kiệm {1} tệ",
            "领券(?:减|立减)${N}元?" to "Nhận phiếu giảm {1}",
            "满${N}(?:元)?可用" to "Dùng cho đơn từ {1}",
            "${N}元(?:优惠)?券" to "Phiếu giảm {1} tệ",
            "${N}元/(件|个|套|只|台|双|条|张|把|包|箱|盒|瓶|米|公斤|千克|斤|kg)" to "{1} tệ/{U2}",
        ).map { (pattern, template) -> Regex(pattern) to template }

        /** The same sentences said shorter, for a chip or a tab with no room for the usual words. */
        private val SHORT_PATTERNS: List<Pair<Regex, String>> = listOf(
            "满${N}(?:元)?包邮" to "Freeship từ {1}",
            "超${N}人加购" to "{1}+ thêm giỏ",
            "${N}人加购" to "{1} thêm giỏ",
            "近(\\d+)天${N}人逛过" to "{2} xem/{1} ngày",
            "${N}人逛过" to "{1} đã xem",
            "${N}人付款" to "{1} đã mua",
            "店铺一年回头客${N}" to "{1} khách quen/năm",
            "(?:超级)?立减${N}" to "Giảm {1}",
            "已售${N}(?:件)?" to "Bán {1}",
            "组合再省${N}元" to "Bộ giảm {1} tệ",
            "店铺年销(?:售|量)?超${N}(?:件)?" to "Bán {1}+/năm",
            "下单运费最高立减${N}" to "Giảm phí vận chuyển tới {1}",
            "${N}跨境包邮" to "Freeship QT từ {1}",
            "(\\d+)年老店" to "Shop {1} năm",
            "红包省${N}元" to "Lì xì -{1} tệ",
            "(?:超级)?立减${N}元" to "Giảm {1} tệ",
            "${N}(件|个|套|只|台|双|条|张|把|包|箱|盒|瓶|米|公斤|千克|斤|kg)起批" to "Sỉ từ {1} {U2}",
            "回头率${N}" to "Quay lại {1}",
            "${N}(?:小时|h|H)(?:内)?发货" to "Gửi trong {1}h",
        ).map { (pattern, template) -> Regex(pattern) to template }

        /** What `composed` keeps for a line that cannot be put together. */
        private const val NONE = "\u0000"
    }
}
