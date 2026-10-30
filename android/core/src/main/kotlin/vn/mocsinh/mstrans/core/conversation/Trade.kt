package vn.mocsinh.mstrans.core.conversation

/**
 * Hội thoại's own terms for the user's trade: the import and export of frozen seafood, frozen meat and fresh
 * fruit, with Chinese and English partners. GPT gets all of them after its instructions (the same in every
 * request of a meeting, so OpenAI reads them from its cache: faster, and cheaper than their length); Soniox,
 * for the partners' language, as many as its context has room for after the user's own glossary: the words
 * to hear right, and how the trade translates them.
 */
object Trade {
    /** `english` is empty when English has no word for it of its own (a Chinese border town). */
    class Term(val vietnamese: String, val chinese: String, val english: String)

    /** Most needed first: Soniox gets those its context has room for. */
    val terms = listOf(
        // Frozen goods
        Term("hàng đông lạnh", "冻品", "frozen goods"),
        Term("cấp đông rời (IQF)", "单冻", "IQF"),
        Term("đông block", "块冻", "block frozen"),
        Term("mạ băng", "包冰", "glazing"),
        Term("trọng lượng tịnh", "净重", "net weight"),
        Term("trọng lượng cả bì", "毛重", "gross weight"),
        Term("container lạnh", "冷柜", "reefer container"),
        Term("kho lạnh", "冷库", "cold storage"),
        Term("chuỗi lạnh", "冷链", "cold chain"),
        // Seafood and meat
        Term("tôm thẻ chân trắng", "南美白对虾", "vannamei shrimp"),
        Term("tôm sú", "黑虎虾", "black tiger shrimp"),
        Term("tôm nguyên con (HOSO)", "带头虾", "HOSO"),
        Term("tôm bỏ đầu (HLSO)", "去头虾", "HLSO"),
        Term("tôm nõn (PD)", "虾仁", "peeled deveined"),
        Term("cá tra (basa)", "巴沙鱼", "pangasius"),
        Term("phi lê", "鱼片", "fillet"),
        Term("mực ống", "鱿鱼", "squid"),
        Term("mực nang", "墨鱼", "cuttlefish"),
        Term("bạch tuộc", "章鱼", "octopus"),
        Term("cá ngừ", "金枪鱼", "tuna"),
        Term("ghẹ", "梭子蟹", "blue swimming crab"),
        Term("tôm hùm", "龙虾", "lobster"),
        Term("chân gà", "鸡爪", "chicken feet"),
        Term("cánh gà", "鸡翅", "chicken wings"),
        Term("chân giò", "猪蹄", "pork trotters"),
        // Fruit
        Term("sầu riêng", "榴莲", "durian"),
        Term("sầu riêng Monthong", "金枕榴莲", "Monthong durian"),
        Term("sầu riêng Musang King", "猫山王", "Musang King"),
        Term("thanh long", "火龙果", "dragon fruit"),
        Term("thanh long ruột đỏ", "红心火龙果", "red dragon fruit"),
        Term("nhãn", "龙眼", "longan"),
        Term("vải", "荔枝", "lychee"),
        Term("măng cụt", "山竹", "mangosteen"),
        Term("chôm chôm", "红毛丹", "rambutan"),
        Term("chanh leo (chanh dây)", "百香果", "passion fruit"),
        Term("mít", "菠萝蜜", "jackfruit"),
        Term("dứa", "菠萝", "pineapple"),
        Term("bưởi", "柚子", "pomelo"),
        Term("na (mãng cầu)", "释迦", "sugar apple"),
        Term("độ chín", "熟度", "ripeness"),
        Term("độ ngọt (Brix)", "糖度", "Brix"),
        Term("quả hỏng", "烂果", "rotten fruit"),
        Term("tỷ lệ hao hụt", "损耗率", "loss rate"),
        Term("ủ chín", "催熟", "ripening"),
        // Customs and documents
        Term("vận đơn", "提单", "bill of lading"),
        Term("thông quan", "清关", "customs clearance"),
        Term("khai báo hải quan", "报关", "customs declaration"),
        Term("Tổng cục Hải quan Trung Quốc (GACC)", "海关总署", "GACC"),
        Term("giấy chứng nhận kiểm dịch thực vật", "植物检疫证书", "phytosanitary certificate"),
        Term("chứng thư vệ sinh (health certificate)", "卫生证书", "health certificate"),
        Term("giấy chứng nhận xuất xứ (C/O)", "原产地证", "certificate of origin"),
        Term("mã số vùng trồng", "果园注册号", "orchard registration code"),
        Term("mã số cơ sở đóng gói", "包装厂注册号", "packing house code"),
        Term("xông hơi khử trùng", "熏蒸", "fumigation"),
        Term("kiểm tra xác suất", "抽检", "random inspection"),
        // Border trade with China
        Term("cửa khẩu", "口岸", "border gate"),
        Term("chính ngạch", "正关", "official customs channel"),
        Term("tiểu ngạch", "边贸", "border trade"),
        Term("ùn ứ xe ở cửa khẩu", "压车", ""),
        Term("cửa khẩu Hữu Nghị", "友谊关", ""),
        Term("Bằng Tường", "凭祥", ""),
        Term("Pò Chài (đối diện Tân Thanh)", "浦寨", ""),
        Term("Đông Hưng (đối diện Móng Cái)", "东兴", ""),
        Term("Hà Khẩu (đối diện Lào Cai)", "河口", ""),
        // Buying and selling
        Term("chủ hàng", "货主", "cargo owner"),
        Term("báo giá", "报价", "quotation"),
        Term("tiền cọc", "定金", "deposit"),
        Term("tiền thanh toán nốt", "尾款", "balance payment"),
        Term("thư tín dụng (L/C)", "信用证", "letter of credit"),
        Term("chuyển tiền T/T", "电汇", "T/T"),
        Term("giá CIF", "到岸价", "CIF price"),
        Term("giá FOB", "离岸价", "FOB price"),
        Term("số lượng đặt tối thiểu (MOQ)", "起订量", "MOQ"),
        Term("thời gian giao hàng", "交期", "lead time"),
        Term("lịch tàu", "船期", "shipping schedule"),
        Term("quy cách", "规格", "specification"),
        Term("hàng loại A", "A级", "grade A"),
        Term("khiếu nại bồi thường", "索赔", "claim"),
        Term("hạn sử dụng", "保质期", "shelf life"),
        Term("hàng lẻ ghép container (LCL)", "拼柜", "LCL"),
        Term("container 40 feet cao", "40尺高柜", "40ft high cube"),
    )

    /** For Soniox's `general` context. */
    val about = "domain" to "Import and export of frozen seafood, frozen meat and fresh fruit: products, prices, containers, customs and quality"

    /** After GPT's instructions. */
    val prompt: String = buildString {
        append("The meeting is about the import and export of frozen seafood, frozen meat and fresh fruit. Translate in the words of this trade:\n")
        append("- 柜 (一柜, 一个柜) is a container, in this trade usually a reefer (冷柜); 件 and 箱 are cartons; 一票 and 一批 are a shipment (lô hàng).\n")
        append("- Prices go per kg, per ton (吨) or per carton, in USD (美金, 美元) or 元 (tệ). Keep trade terms and codes as they are said: ")
        append("CIF, FOB, CFR, EXW, T/T, L/C, IQF, HOSO, Ri6, grades (A级 = loại A) and shrimp sizes such as 16/20 or 21/25.\n")
        append("Standard translations (Chinese / English = Vietnamese), in whichever direction you translate:")
        for (term in terms) {
            append("\n").append(term.chinese)
            if (term.english.isNotEmpty()) append(" / ").append(term.english)
            append(" = ").append(term.vietnamese)
        }
    }

    /** The terms for a Soniox session whose other language is `code` (Soniox's), Vietnamese first: none but for Chinese and English. */
    fun pairs(code: String): List<Pair<String, String>> = when (code) {
        "zh" -> terms.map { it.vietnamese to it.chinese }
        "en" -> terms.filter { it.english.isNotEmpty() }.map { it.vietnamese to it.english }
        else -> emptyList()
    }
}
