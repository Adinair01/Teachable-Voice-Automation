package com.tva.teachable.core

/** Why automation must stop and give the phone back to the user. */
enum class BoundaryKind { PAYMENT, OTP, LOGIN, PASSWORD }

data class Boundary(val kind: BoundaryKind, val reason: String)

/**
 * Runtime credential/payment boundary detection, independent of what was flagged while teaching.
 * Two checks:
 *  - [screen]: the CURRENT screen is a payment / OTP / login / password screen -> stop before touching anything.
 *  - [action]: the node about to be tapped would commit a payment or order ("Pay ₹249", "Place order").
 * Fails towards stopping: a false positive costs one "your turn", a false negative can spend money.
 */
object ScreenGuard {
    // Apps that only ever show payment/credential UI once foregrounded (UPI apps, wallets, payment gateways).
    private val PAYMENT_PACKAGES = setOf(
        "com.phonepe.app", "net.one97.paytm", "com.google.android.apps.nbu.paisa.user", "in.org.npci.upiapp",
        "in.amazon.mShop.android.shopping.pay", "com.mobikwik_new", "com.freecharge.android", "com.dreamplug.androidapp",
        "com.csam.icici.bank.imobile", "com.sbi.upi", "com.axis.mobile", "com.snapwork.hdfc",
    )
    // Embedded gateway SDKs show up in resource ids even when the host app is the one in the foreground.
    private val GATEWAY_ID = Regex("razorpay|juspay|payu|cashfree|paytm|phonepe|billdesk|ccavenue|stripe|hyperpay", RegexOption.IGNORE_CASE)

    private val OTP = Regex("\\b(otp|one[- ]time (password|code)|verification code|enter (the )?code|upi pin|mpin|enter pin|resend (otp|code))\\b", RegexOption.IGNORE_CASE)
    private val CARD_FIELD = Regex("\\b(card ?number|cvv|cvc|expiry|valid thru|mm ?/ ?yy|name on card)\\b", RegexOption.IGNORE_CASE)
    private val PAYMENT_TITLE = Regex("\\b(payment options|select (a )?payment (method|mode|option)|choose (a )?payment|complete (your )?payment|confirm payment|payment methods)\\b", RegexOption.IGNORE_CASE)
    // Several of these listed together = a payment-method picker, even without a title.
    private val PAYMENT_METHOD = Regex("\\b(upi|credit card|debit card|net ?banking|wallets?|pay later|add new card|saved cards?|cash on delivery|pay on delivery)\\b", RegexOption.IGNORE_CASE)
    private val LOGIN_TITLE = Regex("\\b(log ?in|sign ?in|login|sign up|create (an )?account|continue with (google|email|phone)|enter (your )?(phone|mobile) number|welcome back)\\b", RegexOption.IGNORE_CASE)
    private val LOGIN_FIELD = Regex("\\b(phone|mobile|email|e-mail|username|user name)\\b", RegexOption.IGNORE_CASE)
    // Bare "pay" is deliberate: "Pay ₹249", "Pay using UPI" and "Pay now" all stop the run.
    private val COMMIT = Regex("\\b(pay(ing)?|place (the )?order|order now|proceed to (pay|payment|checkout)|confirm (and pay|order|payment|booking)|make payment|complete (order|payment|purchase)|buy now|swipe to (pay|place order)|slide to pay|checkout)\\b", RegexOption.IGNORE_CASE)

    fun screen(nodes: List<UiNode>): Boundary? {
        if (nodes.isEmpty()) return null
        nodes.firstOrNull { it.password }?.let { return Boundary(BoundaryKind.PASSWORD, "password field") }
        nodes.firstOrNull { it.packageName in PAYMENT_PACKAGES }?.let { return Boundary(BoundaryKind.PAYMENT, "payment app ${it.packageName}") }
        nodes.firstOrNull { GATEWAY_ID.containsMatchIn(it.resourceId) }?.let { return Boundary(BoundaryKind.PAYMENT, "payment gateway ${it.resourceId}") }

        val inputs = nodes.filter { it.editable }
        inputs.firstOrNull { OTP.containsMatchIn(label(it)) }?.let { return Boundary(BoundaryKind.OTP, "OTP/PIN input") }
        inputs.firstOrNull { CARD_FIELD.containsMatchIn(label(it)) }?.let { return Boundary(BoundaryKind.PAYMENT, "card details input") }
        // OTP screens often render one box per digit: 4-6 tiny single-character inputs next to an OTP caption.
        if (inputs.size in 4..8 && nodes.any { OTP.containsMatchIn(visible(it)) }) return Boundary(BoundaryKind.OTP, "OTP digit boxes")

        val texts = nodes.map(::visible).filter { it.isNotBlank() }
        texts.firstOrNull { PAYMENT_TITLE.containsMatchIn(it) }?.let { return Boundary(BoundaryKind.PAYMENT, "payment screen: \"${it.take(40)}\"") }
        val methods = texts.flatMap { t -> PAYMENT_METHOD.findAll(t).map { m -> m.value.lowercase().replace(" ", "").removeSuffix("s") }.toList() }.toSet()
        if (methods.size >= 3) return Boundary(BoundaryKind.PAYMENT, "payment methods listed: ${methods.joinToString()}")

        if (texts.any { LOGIN_TITLE.containsMatchIn(it) } && inputs.any { LOGIN_FIELD.containsMatchIn(label(it)) })
            return Boundary(BoundaryKind.LOGIN, "login screen")
        return null
    }

    /**
     * Tapping [node] would commit money or an order. Checked on the real target, including LLM-fallback picks.
     * A clickable container usually carries no text itself, so its subtree's text counts too.
     */
    fun action(nodes: List<UiNode>, node: UiNode): Boundary? =
        actionText((listOf(node) + subtree(nodes, node)).joinToString(" ") { visible(it) })

    /** Same rule on a caption alone (a step's selector), so a payment step hands over before anyone is asked. */
    fun actionText(text: String): Boundary? =
        if (COMMIT.containsMatchIn(text)) Boundary(BoundaryKind.PAYMENT, "would tap \"${text.trim().take(40)}\"") else null

    // Nodes are a pre-order walk, so a node's subtree is the run of following nodes that sit deeper than it.
    private fun subtree(nodes: List<UiNode>, node: UiNode) =
        nodes.drop(node.index + 1).takeWhile { it.depth > node.depth }.take(30)

    private fun visible(n: UiNode) = if (n.password) "" else "${n.text} ${n.contentDesc}".trim()
    // Empty inputs expose their hint as text; the resource id often names the field ("et_otp", "card_number").
    private fun label(n: UiNode) = "${visible(n)} ${n.resourceId.substringAfter('/').replace('_', ' ')}"
}
