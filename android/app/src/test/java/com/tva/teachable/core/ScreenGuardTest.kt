package com.tva.teachable.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenGuardTest {
    private fun screen(pkg: String = "com.application.zomato", vararg nodes: Triple<String, String, Boolean>, pwd: Boolean = false) =
        nodes.mapIndexed { i, (id, text, edit) -> UiNode(i, 1, pkg, id, text, "", "", "", !edit, edit, false, pwd && edit) }
    private fun t(text: String, id: String = "") = Triple(id, text, false)
    private fun input(hint: String, id: String = "") = Triple(id, hint, true)

    @Test fun passwordFieldIsABoundary() {
        assertEquals(BoundaryKind.PASSWORD, ScreenGuard.screen(screen("x", t("Welcome"), input("", "x:id/pwd"), pwd = true))?.kind)
    }

    @Test fun otpInputIsABoundaryEvenWithoutACaption() {
        assertEquals(BoundaryKind.OTP, ScreenGuard.screen(screen(nodes = arrayOf(t("Verify"), input("", "com.application.zomato:id/et_otp"))))?.kind)
    }

    @Test fun otpDigitBoxesAreABoundary() {
        val n = screen(nodes = arrayOf(t("Enter the 6-digit OTP sent to +91 98xxxx"), input(""), input(""), input(""), input(""), input(""), input("")))
        assertEquals(BoundaryKind.OTP, ScreenGuard.screen(n)?.kind)
    }

    @Test fun upiPaymentPageIsABoundary() {
        val n = screen(nodes = arrayOf(t("Bill total ₹349"), t("Google Pay UPI"), t("Credit & Debit cards"), t("Add new card"), t("Wallets"), t("Netbanking")))
        assertEquals(BoundaryKind.PAYMENT, ScreenGuard.screen(n)?.kind)
    }

    @Test fun cardFormIsABoundary() {
        assertEquals(BoundaryKind.PAYMENT, ScreenGuard.screen(screen(nodes = arrayOf(t("Add card"), input("Card number"))))?.kind)
    }

    @Test fun paymentAppAndGatewayAreBoundaries() {
        assertEquals(BoundaryKind.PAYMENT, ScreenGuard.screen(screen("com.phonepe.app", t("Enter amount")))?.kind)
        assertEquals(BoundaryKind.PAYMENT, ScreenGuard.screen(screen(nodes = arrayOf(t("", "com.application.zomato:id/razorpay_checkout"))))?.kind)
    }

    @Test fun loginScreenIsABoundary() {
        val n = screen(nodes = arrayOf(t("Log in or sign up"), input("Enter Phone Number"), t("Continue")))
        assertEquals(BoundaryKind.LOGIN, ScreenGuard.screen(n)?.kind)
    }

    // The cart and the address sheet sit right before payment and MUST stay usable (T2 / T6 happen there).
    @Test fun cartWithPlaceOrderButtonIsNotAScreenBoundary() {
        val n = screen(nodes = arrayOf(t("Domino's Pizza"), t("Margherita"), t("₹249"), t("Deliver to Home"), t("Pay using"), t("Google Pay UPI"), t("Place Order")))
        assertNull(ScreenGuard.screen(n))
    }

    @Test fun addressSheetAndSettingsAreNotBoundaries() {
        assertNull(ScreenGuard.screen(screen(nodes = arrayOf(t("Select an address"), t("Home"), t("Work"), t("Add address")))))
        assertNull(ScreenGuard.screen(screen("com.android.settings", t("Bluetooth"), t("Wi-Fi"), t("Display"))))
        assertNull(ScreenGuard.screen(screen(nodes = arrayOf(t("Search for restaurant, item or more"), input("Search")))))
    }

    @Test fun committingTapsAreBoundariesButOrdinaryTapsAreNot() {
        listOf("Place Order", "Pay ₹349", "Proceed to Pay", "Swipe to place order", "Buy Now", "Confirm order").forEach {
            assertEquals(it, BoundaryKind.PAYMENT, ScreenGuard.actionText(it)?.kind)
        }
        listOf("ADD", "Work", "Margherita", "View cart", "Paytm Karo Pizza", "Payload").forEach { assertNull(it, ScreenGuard.actionText(it)) }
    }

    @Test fun actionLooksInsideATextlessContainer() {
        val n = listOf(
            UiNode(0, 0, "p", "", "", "", "", "", false, false, false, false),
            UiNode(1, 1, "p", "p:id/cta", "", "", "", "", true, false, false, false),
            UiNode(2, 2, "p", "", "Place Order", "", "", "", false, false, false, false),
            UiNode(3, 1, "p", "", "Add more items", "", "", "", true, false, false, false),
        )
        assertEquals(BoundaryKind.PAYMENT, ScreenGuard.action(n, n[1])?.kind)
        assertNull(ScreenGuard.action(n, n[3]))
    }
}
