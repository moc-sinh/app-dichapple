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
) {
    private val mustKeep: List<Pair<String, String>> =
        required.entries.map { it.key to it.value }.sortedByDescending { it.first.length }

    private val byLength: List<Pair<String, String>> =
        terms.entries.map { it.key to it.value }.sortedByDescending { it.first.length }

    /** The translation of the whole line, or null when it is not a known word or pattern. */
    fun lookup(text: String): String? {
        val key = ChineseText.key(text)
        if (key.isEmpty()) return null
        return terms[key] ?: labels[key] ?: pattern(key) ?: withArrow(key)
    }

    /** A chip or a row that ends with an arrow ("超200人加购 >"): what is before it, the arrow kept. */
    private fun withArrow(key: String): String? {
        val arrow = key.takeLastWhile { it in ARROWS }
        if (arrow.isEmpty() || arrow.length == key.length) return null
        val body = key.dropLast(arrow.length)
        val done = terms[body] ?: labels[body] ?: pattern(body) ?: return null
        return "$done $arrow"
    }

    /** A shorter word for a place too narrow for the usual one (a label under an icon); null: none. */
    fun short(text: String): String? = SHORT[ChineseText.key(text)]

    /** The terms a translation must keep word for word, longest first. */
    fun required(): List<Pair<String, String>> = mustKeep

    /** Terms to replace inside a line, longest first ("加入购物车" before "购物车"). */
    fun terms(): List<Pair<String, String>> = byLength

    private fun pattern(key: String): String? {
        for ((regex, template) in PATTERNS) {
            val match = regex.matchEntire(key) ?: continue
            var out = template
            match.groupValues.drop(1).forEachIndexed { i, value -> out = out.replace("{${i + 1}}", number(value)) }
            return out
        }
        return null
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
            "约" to "Khoảng", "已售" to "Đã bán", "详情" to "Chi tiết",
        )

        /** Shorter words for places too narrow for the usual ones: labels under icons, tabs. */
        val SHORT: Map<String, String> = linkedMapOf(
            "客服" to "CSKH", "收藏" to "Lưu", "店铺" to "Shop", "消息" to "Tin", "分类" to "Mục", "购物车" to "Giỏ",
            "加入购物车" to "Thêm vào giỏ", "我的淘宝" to "Của tôi",
        )

        private const val ARROWS = ">›»"

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
            "(?:超级)?立减${N}" to "Giảm ngay {1}",
            "官方立减${N}" to "Giảm chính hãng {1}",
            "到手价${N}(?:元)?" to "Giá cuối {1}",
            "限时${N}" to "Ưu đãi có hạn {1}",
        ).map { (pattern, template) -> Regex(pattern) to template }
    }
}
