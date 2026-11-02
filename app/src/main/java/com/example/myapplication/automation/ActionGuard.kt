package com.example.myapplication.automation

/**
 * Safety rules for actions chosen by the LLM. They live in code because on-screen text reaches the
 * LLM and could talk it into anything; the scripted flows only tap fixed labels and don't need them.
 *
 * Word lists can't recognise every payment button (an unusual "Continue" can still lead to one), so
 * [isPaymentScreen] also stops the agent as soon as a screen looks like checkout.
 */
object ActionGuard {

    enum class Verdict {
        ALLOW,
        /** Has an effect outside the phone or is hard to undo: the user must say yes first. */
        CONFIRM,
        /** Spends money: never done by ZeroUI, the user finishes it. */
        BLOCK
    }

    /** Longer text is a description (a hotel card's "Pay at the property…"), not a button's label. */
    private const val MAX_LABEL_LENGTH = 60

    private val SPENDS_MONEY = Regex(
        "\\b(?:pay|payment|check ?out|place (?:my |your |the )?order|order now|buy|purchase|book now|" +
            "submit order|complete (?:booking|order|purchase)|confirm (?:booking|order|payment|purchase)|top up)\\b" +
            "|付款|支付|結帳|結賬|结账|下單|下单|購買|购买|確認訂單|确认订单|立即預訂|立即预订|完成預訂|完成预订",
        RegexOption.IGNORE_CASE
    )
    private val NEEDS_YES = Regex(
        "\\b(?:send|post|publish|share|delete|remove|install|uninstall|allow|sign out|log ?out|subscribe|unsubscribe|" +
            "follow|reply|submit|transfer|confirm|reserve|book)\\b" +
            "|傳送|發送|发送|發布|发布|分享|刪除|删除|移除|安裝|安装|允許|允许|登出|訂閱|订阅|提交|轉帳|转账|確認|确认|預訂|预订",
        RegexOption.IGNORE_CASE
    )
    private val PAYMENT_SCREEN = Regex(
        "\\b(?:card number|cvv|cvc|expiry date|payment method)\\b|卡號|卡号|付款方式|支付方式",
        RegexOption.IGNORE_CASE
    )
    private val PRIVATE_FIELD = Regex(
        "\\b(?:password|passcode|pin|cvv|cvc|card|otp|verification code|first name|last name|full name|" +
            "e-?mail|phone|mobile|address|passport|date of birth)\\b" +
            "|密碼|密码|卡號|卡号|驗證碼|验证码|姓名|電話|电话|電郵|邮箱|地址|護照|护照|身分證|身份证",
        RegexOption.IGNORE_CASE
    )
    private val SEARCH = Regex("\\bsearch\\b|搜尋|搜索|搜寻", RegexOption.IGNORE_CASE)

    /** How to treat a tap on an element with these [labels]. */
    fun forTap(labels: List<String>): Verdict {
        val short = labels.filter { it.length <= MAX_LABEL_LENGTH }
        return when {
            short.any { SPENDS_MONEY.containsMatchIn(it) } -> Verdict.BLOCK
            short.any { NEEDS_YES.containsMatchIn(it) } -> Verdict.CONFIRM
            else -> Verdict.ALLOW
        }
    }

    /** The label that triggered a non-ALLOW verdict, for telling the user what was stopped. */
    fun reason(labels: List<String>): String? {
        val short = labels.filter { it.length <= MAX_LABEL_LENGTH }
        return short.firstOrNull { SPENDS_MONEY.containsMatchIn(it) } ?: short.firstOrNull { NEEDS_YES.containsMatchIn(it) }
    }

    /** True for a field ZeroUI must leave to the user. A search box may mention "email" and is fine. */
    fun isPrivateField(labels: List<String>, password: Boolean): Boolean =
        password || (labels.none { SEARCH.containsMatchIn(it) } && labels.any { PRIVATE_FIELD.containsMatchIn(it) })

    fun isPaymentScreen(labels: List<String>): Boolean =
        labels.any { it.length <= MAX_LABEL_LENGTH && PAYMENT_SCREEN.containsMatchIn(it) }
}
