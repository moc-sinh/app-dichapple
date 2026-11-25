package vn.mocsinh.mstrans.core.screen

/**
 * When translating stops and the overlay goes: a password field, the keyboard up, a window the app
 * protects (FLAG_SECURE, known on Android 11+ when its screenshot is refused), or payment words.
 * Words are only an extra layer, not a guarantee.
 */
object SensitiveScreen {
    enum class Reason(val label: String) {
        SECURE_WINDOW("màn hình được app bảo vệ"),
        PASSWORD_FIELD("có ô mật khẩu"),
        KEYBOARD("bàn phím đang mở"),
        KEYWORD("màn hình thanh toán hoặc mật khẩu"),
    }

    /** Payment and identity words: stop wherever they are. */
    val ALWAYS = listOf("支付密码", "交易密码", "付款码", "收款码", "安全键盘", "密码键盘", "银行卡号", "身份证号", "安全码", "cvv", "cvc")

    /** Weaker words: stop only when the screen has a field to type in (a menu item "修改密码" is fine). */
    val WITH_FIELD = listOf("密码", "验证码", "银行卡", "身份证", "卡号", "有效期")

    fun check(
        texts: List<String>,
        passwordField: Boolean = false,
        editableField: Boolean = false,
        keyboard: Boolean = false,
        secureWindow: Boolean = false,
    ): Reason? {
        if (secureWindow) return Reason.SECURE_WINDOW
        if (passwordField) return Reason.PASSWORD_FIELD
        if (keyboard) return Reason.KEYBOARD
        val words = texts.map { ChineseText.toSimplified(it).lowercase() }
        if (words.any { text -> ALWAYS.any { it in text } }) return Reason.KEYWORD
        if (editableField && words.any { text -> WITH_FIELD.any { it in text } }) return Reason.KEYWORD
        return null
    }
}
