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
    /**
     * Clearly reversible local UI confirmations (choosing dates, filters, location, or options)
     * that shouldn't interrupt app exploration unless a payment/high-risk context is present.
     */
    private val SAFE_UI_CONFIRM = Regex(
        "\\bconfirm\\s+(?:dates?|filter|filters|location|address|selection|choice|choices|guests?|rooms?|time|city|pickup|delivery)\\b" +
            "|確認(?:日期|篩選|地點|地址|位置|選擇|選項|人數|房型|時間|城市|外送|自取)" +
            "|确认(?:日期|筛选|地点|地址|位置|选择|选项|人数|房型|时间|城市)",
        RegexOption.IGNORE_CASE
    )
    private val PICKER_CONTEXT = Regex(
        "\\b(?:filter|sort by|select dates?|check-?in|check-?out|choose your|pick a|date picker|location|guests?)\\b" +
            "|篩選|排序|選擇日期|入住|退房|選擇地點|選擇地址|人數",
        RegexOption.IGNORE_CASE
    )
    private val HIGH_RISK_CONTEXT = Regex(
        "\\b(?:delete|remove|transfer|payment|checkout|order summary|total|sign out|log ?out|compose|new message|new post|permission)\\b" +
            "|刪除|轉帳|付款|結帳|總金額|登出|權限|發文|傳送訊息",
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

    /** How to treat a tap on an element with these [labels], optionally considering [screenLabels]. */
    fun forTap(labels: List<String>, screenLabels: List<String> = emptyList()): Verdict {
        val short = labels.filter { it.length <= MAX_LABEL_LENGTH }
        if (short.any { SPENDS_MONEY.containsMatchIn(it) }) return Verdict.BLOCK
        if (short.none { NEEDS_YES.containsMatchIn(it) }) return Verdict.ALLOW

        // Clearly reversible picker/filter confirmations (e.g. "Confirm dates", "確認篩選") flow through.
        val allSafeConfirm = short.filter { NEEDS_YES.containsMatchIn(it) }.all { SAFE_UI_CONFIRM.containsMatchIn(it) }
        if (allSafeConfirm) return Verdict.ALLOW

        // A bare "Confirm"/"確認" on an unmistakable filter/date-picker screen (with no high-risk cues) is safe.
        if (screenLabels.isNotEmpty()) {
            val bareConfirmOnly = short.filter { NEEDS_YES.containsMatchIn(it) }
                .all { it.trim().matches(Regex("^(?:confirm|確認|确认)$", RegexOption.IGNORE_CASE)) }
            if (bareConfirmOnly &&
                screenLabels.any { PICKER_CONTEXT.containsMatchIn(it) } &&
                screenLabels.none { HIGH_RISK_CONTEXT.containsMatchIn(it) }
            ) {
                return Verdict.ALLOW
            }
        }
        return Verdict.CONFIRM
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
