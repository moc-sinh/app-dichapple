package vn.mocsinh.mstrans.core

/**
 * On-device language detection, so "Tự động" can answer in the partner's language. Writing
 * systems tell most languages apart (Chinese, Japanese, Korean, Thai, Russian, Arabic, Hindi);
 * Latin-script ones are told by their common words. `latinGuess` may refine that (the app hands in
 * Android's own detector where the phone has one).
 */
object LanguageDetector {
    /** A better guess for Latin-script text, as a language code; null when it cannot tell. */
    @Volatile var latinGuess: ((String) -> String?)? = null

    /** The language the partner writes in, from their latest messages. */
    fun partnerLanguage(lines: List<ChatLine>): Language? {
        val text = lines.filter { it.side == ChatLine.Side.PARTNER }.takeLast(4).joinToString("\n") { it.text }
        return language(text)
    }

    /**
     * The language of `text`, unless it is Vietnamese or too short to tell. A foreign message that
     * names a Vietnamese person or place is still foreign.
     */
    fun language(text: String): Language? {
        val code = detect(text) ?: return null
        if (code == Language.vietnamese.code) return null
        return Language.named(code) ?: Language.other(code)
    }

    /** Vietnamese as people write it: Latin letters with marks on many of them. */
    fun isVietnamese(text: String): Boolean = detect(text) == Language.vietnamese.code && hasVietnameseMarks(text)

    /**
     * About a quarter of the letters in Vietnamese carry a mark (ă â đ ê ô ơ ư or a tone); an English
     * message with a Vietnamese name in it has only a few.
     */
    fun hasVietnameseMarks(text: String): Boolean {
        var letters = 0
        var marked = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isAlphabetic(cp)) continue
            if (cp < 0x80) {
                letters++
            } else if (cp in 0xC0..0x1EFF) {
                letters++
                marked++
            }
        }
        return letters > 0 && marked.toDouble() / letters >= 0.1
    }

    /** "vi", "zh", "en"…; null when too short to tell. */
    fun detect(text: String): String? {
        if (text.trim().length < 2) return null
        var latin = 0
        var han = 0
        var kana = 0
        var hangul = 0
        var thai = 0
        var cyrillic = 0
        var arabic = 0
        var devanagari = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp in 0x3040..0x30FF || cp in 0x31F0..0x31FF || cp in 0xFF66..0xFF9F -> kana++
                cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF -> han++
                cp in 0xAC00..0xD7AF || cp in 0x1100..0x11FF || cp in 0x3130..0x318F -> hangul++
                cp in 0x0E00..0x0E7F -> thai++
                cp in 0x0400..0x04FF -> cyrillic++
                cp in 0x0600..0x06FF || cp in 0x0750..0x077F -> arabic++
                cp in 0x0900..0x097F -> devanagari++
                Character.isLetter(cp) && (cp < 0x250 || cp in 0x1E00..0x1EFF) -> latin++
            }
        }
        // A character of Chinese, Japanese or Korean says about as much as a word of letters.
        val scripts = listOf(
            "cjk" to (han + kana) * 3,
            "ko" to hangul * 2,
            "th" to thai,
            "ru" to cyrillic,
            "ar" to arabic,
            "hi" to devanagari,
            "latin" to latin,
        )
        val (script, weight) = scripts.maxBy { it.second }
        if (weight == 0) return null
        return when (script) {
            "cjk" -> when {
                kana > 0 && kana * 10 >= han + kana -> "ja"
                isTraditional(text) -> "zh-Hant"
                else -> "zh"
            }
            "latin" -> latinLanguage(text)
            else -> script
        }
    }

    /** Letters only Vietnamese writes: ă đ ĩ ũ ơ ư, and the toned vowels of Latin Extended Additional. */
    private fun hasVietnameseLetters(text: String): Boolean = text.any {
        it.code in 0x1EA0..0x1EF9 || it in "ăĂđĐĩĨũŨơƠưƯ"
    }

    private fun folded(word: String): String =
        java.text.Normalizer.normalize(word.replace('đ', 'd'), java.text.Normalizer.Form.NFD).filter { it.code < 0x300 || it.code > 0x36F }

    private fun latinLanguage(text: String): String? {
        if (hasVietnameseLetters(text) && hasVietnameseMarks(text)) return Language.vietnamese.code
        val words = text.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val scores = HashMap<String, Int>()
        for (word in words) {
            for ((code, list) in commonWords) {
                if (word in list || (code == Language.vietnamese.code && folded(word) in list)) scores.merge(code, 1, Int::plus)
            }
        }
        for (c in text.lowercase()) {
            when (c) {
                'ñ', '¿', '¡' -> scores.merge("es", 2, Int::plus)
                'ß', 'ä', 'ö', 'ü' -> scores.merge("de", 2, Int::plus)
                'ç', 'œ', 'è', 'ê', 'î', 'ï', 'û', 'ë' -> scores.merge("fr", 1, Int::plus)
            }
        }
        val best = scores.maxByOrNull { it.value }
        val vietnamese = scores[Language.vietnamese.code] ?: 0
        if (vietnamese > 0 && scores.all { it.key == Language.vietnamese.code || it.value < vietnamese }) return Language.vietnamese.code
        latinGuess?.invoke(text)?.let { guess ->
            val code = guess.substringBefore('-').lowercase()
            if (code.isNotEmpty() && code != "und") return if (code == "in") "id" else code
        }
        if (best == null) return if (words.sumOf { it.length } >= 3) "en" else null
        // Malay and Indonesian share most words: Indonesian unless a word only Malay uses tips it.
        if (best.key == "ms" && (scores["id"] ?: 0) >= best.value) return "id"
        return best.key
    }

    private val commonWords: Map<String, Set<String>> = mapOf(
        "en" to setOf(
            "the", "and", "is", "are", "you", "we", "i", "to", "of", "for", "it", "this", "that", "have", "has", "will",
            "can", "please", "thanks", "thank", "what", "how", "with", "be", "not", "do", "does", "our", "your", "my",
            "me", "on", "in", "at", "yes", "hello", "hi", "price", "order", "send", "when", "would", "could", "need",
            "there", "they", "was", "were", "if", "just", "let", "know", "sure", "good", "morning",
        ),
        "fr" to setOf(
            "le", "la", "les", "des", "est", "et", "je", "vous", "nous", "pour", "pas", "une", "un", "du", "que", "qui",
            "avec", "merci", "bonjour", "sur", "dans", "ce", "cette", "mais", "oui", "non", "très", "votre", "notre",
        ),
        "de" to setOf(
            "der", "die", "das", "und", "ist", "ich", "sie", "wir", "nicht", "mit", "für", "ein", "eine", "danke", "bitte",
            "ja", "nein", "auf", "zu", "den", "dem", "es", "haben", "sind", "wie", "auch", "noch", "ihr", "ihnen",
        ),
        "es" to setOf(
            "el", "la", "los", "las", "es", "y", "que", "de", "en", "por", "para", "con", "no", "sí", "gracias", "hola",
            "usted", "una", "un", "del", "pero", "muy", "está", "como", "su", "le", "lo", "al",
        ),
        "id" to setOf(
            "yang", "dan", "di", "ini", "itu", "saya", "anda", "kami", "tidak", "ada", "untuk", "dengan", "bisa",
            "sudah", "akan", "terima", "kasih", "apa", "ke", "dari", "juga", "karena", "harga", "bapak", "ibu", "mau",
            "belum", "sekarang", "barang", "kirim", "kita", "dong", "nggak", "gak",
        ),
        "ms" to setOf(
            "yang", "dan", "di", "ini", "itu", "saya", "anda", "kami", "tidak", "ada", "untuk", "dengan", "boleh",
            "sudah", "akan", "terima", "kasih", "apa", "ke", "dari", "juga", "kerana", "harga", "encik", "puan", "mahu",
            "sahaja", "awak", "kita", "belum", "sekarang", "barang", "hantar", "tak",
        ),
        "vi" to setOf(
            "anh", "em", "chi", "ban", "khong", "ko", "duoc", "dc", "roi", "nhe", "nha", "oi", "gi", "la", "cua", "cho",
            "voi", "toi", "minh", "nao", "bao", "nhieu", "gia", "hang", "gui", "lam", "co", "va", "cac", "nhung", "mot",
            "nay", "thi", "se", "da", "dang", "ve", "ben", "chua", "sao", "vay", "ak", "luon", "ma", "chao", "cam", "xin",
            "oke", "hom", "mai", "qua", "nua", "duoc", "biet", "lai", "ngay", "tuan", "thang",
        ),
    )

    private val simplifiedOnly: Set<Char>
    private val traditionalOnly: Set<Char>

    init {
        val simplified = "们这说个来时会对发为后过还么样问请谢东买卖钱货价运单订产质关开门见号话现经给应该实际联系书车长马万与专业" +
            "两义乐习乡乱亚亲从众优伙传体儿党兰兴养写军农决况净减处击则刚创别办务动劳势区医华协厂历压厅县参双变叶听启员围国" +
            "图圆场坏块坚报壮声复够头夹奖妈学宝实审宽寻导将尔尽层岁岛币师带帮广庆库张弹强归录总恶惊惯态战户执扩扫扬护担拥择" +
            "挂换据损摆数断无旧显晓暂术机杀杂权条杨极构标树桥检楼欢欧气汇汉沟没泽洁浅测济浓涨温湾满滚灭灯灵炼烟热爱牵状独猪" +
            "环电画疗盖监盘确础礼离种积称稳穷竞笔简类粮紧红级纪纳纸线练组细织终结络绝统继续维综绿网罗罚职联肃肠肤胜脑脚脸节" +
            "艺药获营萝虽补装观规视览觉计认让议讨记讲许论设访证评识诉词译试详语误读课谁调谈谋谱贝负财责败账购贯费贵贸资赔赛" +
            "赶跃践转轮软轻载较辆输边达进远违连迟适选递遗邮释针钟钢铁银锁错键间闻阳阴阶陆陈险随难雾静韩页顶项顺须顾预领频题" +
            "颜额风飞饭饮驾验鱼鸟鸡麦黄齐齿龙们"
        val traditional = "們這說個來時會對發為後過還麼樣問請謝東買賣錢貨價運單訂產質關開門見號話現經給應該實際聯繫書車長馬萬與專業" +
            "兩義樂習鄉亂亞親從眾優夥傳體兒黨蘭興養寫軍農決況淨減處擊則剛創別辦務動勞勢區醫華協廠歷壓廳縣參雙變葉聽啟員圍國" +
            "圖圓場壞塊堅報壯聲復夠頭夾獎媽學寶實審寬尋導將爾盡層歲島幣師帶幫廣慶庫張彈強歸錄總惡驚慣態戰戶執擴掃揚護擔擁擇" +
            "掛換據損擺數斷無舊顯曉暫術機殺雜權條楊極構標樹橋檢樓歡歐氣匯漢溝沒澤潔淺測濟濃漲溫灣滿滾滅燈靈煉煙熱愛牽狀獨豬" +
            "環電畫療蓋監盤確礎禮離種積稱穩窮競筆簡類糧緊紅級紀納紙線練組細織終結絡絕統繼續維綜綠網羅罰職聯肅腸膚勝腦腳臉節" +
            "藝藥獲營蘿雖補裝觀規視覽覺計認讓議討記講許論設訪證評識訴詞譯試詳語誤讀課誰調談謀譜貝負財責敗賬購貫費貴貿資賠賽" +
            "趕躍踐轉輪軟輕載較輛輸邊達進遠違連遲適選遞遺郵釋針鐘鋼鐵銀鎖錯鍵間聞陽陰階陸陳險隨難霧靜韓頁頂項順須顧預領頻題" +
            "顏額風飛飯飲駕驗魚鳥雞麥黃齊齒龍"
        val s = simplified.toSet()
        val t = traditional.toSet()
        simplifiedOnly = s - t
        traditionalOnly = t - s
    }

    private fun isTraditional(text: String): Boolean {
        var simplified = 0
        var traditional = 0
        for (c in text) {
            if (c in simplifiedOnly) simplified++ else if (c in traditionalOnly) traditional++
        }
        return traditional > simplified
    }
}

/**
 * The language to write to the partner in: the one chosen, or with "Tự động" the partner's own
 * (from the conversation, else the one seen last).
 */
fun Settings.target(code: String, context: List<ChatLine>): Language {
    if (code != Language.AUTO) return Language.named(code) ?: Language.all[0]
    val detected = LanguageDetector.partnerLanguage(context)
    if (detected != null) {
        if (Language.named(detected.code) != null) lastPartnerCode = detected.code
        return detected
    }
    return Language.named(lastPartnerCode) ?: Language.all[0]
}
