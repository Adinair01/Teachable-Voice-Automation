package com.tva.teachable.replay

import com.tva.teachable.a11y.LiveTree
import com.tva.teachable.core.Flow
import com.tva.teachable.core.FlowStep
import com.tva.teachable.core.Selector
import com.tva.teachable.core.StepType
import com.tva.teachable.core.UiNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Does teach-once generalise?" — the regression check for the whole point of the product.
 *
 * ONE demonstration is taught (order a Margherita from a crowded menu), then the SAME learned flow is replayed
 * against menus it never saw: another dish, a shorthand name, a reordered list with a look-alike dish, a longer
 * menu that needs scrolling, and a cart that already holds the item. Each case asserts WHICH dish was added,
 * because adding the wrong one silently is the worst failure this system can have.
 *
 * The two steps under test are exactly what backend `generalise.js` emits for that demonstration (verified against
 * the real service): a row-anchored ADD, and — for the teach-on-a-single-row case — the un-generalised ADD it emits
 * when the demo screen showed only one of them.
 */
class TeachOnceGeneraliseTest {

    /** A dish row: name, price, and either an ADD button or a "− n +" stepper when it is already in the cart. */
    private data class Dish(val name: String, val price: String = "₹109", val inCart: Int = 0)

    /** A menu screen built from rows, the way a real list does it: identical ADD per row, name in a sibling. */
    private class Menu(private val dishes: List<Dish>, private val scrollRevealsFrom: Int = Int.MAX_VALUE) : Device {
        var tapped: UiNode? = null
        var addedTo: String? = null
        var scrolls = 0
        private var revealed = false

        private fun visible() = if (revealed) dishes else dishes.take(minOf(dishes.size, scrollRevealsFrom))

        /** Which dish each node belongs to, recorded as the tree is built so attribution cannot drift. */
        private val owner = HashMap<Int, String>()

        override fun tree(): LiveTree {
            val n = ArrayList<UiNode>()
            owner.clear()
            fun add(depth: Int, dish: String?, text: String = "", desc: String = "", id: String = "", click: Boolean = false) {
                dish?.let { owner[n.size] = it }
                n += UiNode(n.size, depth, PKG, id, text, desc, "", "", click, false, false, false)
            }
            add(0, null) // root
            for (d in visible()) {
                add(1, d.name, click = true)      // row container
                add(2, d.name, text = d.name)
                add(2, d.name, text = d.price)
                if (d.inCart > 0) {               // already in the cart: a stepper replaces ADD
                    add(2, d.name, text = "\u2212", click = true)
                    add(2, d.name, text = "${d.inCart}")
                    add(2, d.name, text = "+", click = true)
                } else {
                    add(2, d.name, desc = "ADD", id = "$PKG:id/add_button", click = true)
                }
            }
            return LiveTree(n, emptyList())
        }

        override fun tap(tree: LiveTree, node: UiNode): Boolean {
            tapped = node
            if (node.contentDesc == "ADD") addedTo = owner[node.index]
            return true
        }

        override fun launch(packageName: String) = true
        override fun type(tree: LiveTree, node: UiNode, value: String) = true
        override fun scroll(tree: LiveTree): Boolean { scrolls++; revealed = true; return true }
        override fun back() = true
    }

    private val fast = ReplayConfig(waitMs = 30, retryMs = 30, pollMs = 10, settleMs = 30, scrollTries = 3)

    /** What generalise.js emits for the taught ADD when the demo screen showed SEVERAL ADDs (row captured). */
    private val learnedAdd = FlowStep(StepType.TAP, Selector(contentDesc = "ADD", near = "{{item}}"), slotRef = "item")

    /** What it emits when the demo screen showed only ONE ADD: no row anchor, no slot — the raw resource id. */
    private val learnedAddNoRow = FlowStep(StepType.TAP, Selector(resourceId = "$PKG:id/add_button", contentDesc = "ADD"))

    private fun replay(step: FlowStep, menu: Menu, item: String) = runBlocking {
        Replayer(menu, cfg = fast).run(Flow("order_food", "order a pizza", PKG, listOf(step)), mapOf("item" to item))
    }

    // ---- taught once on a crowded menu, replayed on menus it never saw ----

    @Test fun `case 1 - exact replay adds the dish it was taught`() {
        val menu = Menu(listOf(Dish("Margherita Pizza"), Dish("Farmhouse Pizza"), Dish("Peppy Paneer Pizza")))
        val r = replay(learnedAdd, menu, "Margherita pizza")
        assertTrue(r.steps.toString(), r.success)
        assertEquals("Margherita Pizza", menu.addedTo)
    }

    @Test fun `case 2 - a dish never demonstrated is added from its own row`() {
        val menu = Menu(listOf(Dish("Margherita Pizza"), Dish("Farmhouse Pizza"), Dish("Peppy Paneer Pizza")))
        val r = replay(learnedAdd, menu, "Farmhouse pizza")
        assertTrue(r.steps.toString(), r.success)
        assertEquals("Farmhouse Pizza", menu.addedTo)
    }

    @Test fun `case 3 - spoken shorthand still finds the full menu caption`() {
        // The judge says "Farmhouse", the menu says "Farmhouse Pizza".
        val menu = Menu(listOf(Dish("Margherita Pizza"), Dish("Farmhouse Pizza")))
        val r = replay(learnedAdd, menu, "Farmhouse")
        assertTrue(r.steps.toString(), r.success)
        assertEquals("Farmhouse Pizza", menu.addedTo)
    }

    @Test fun `case 4 - a reordered menu with a look-alike dish does not fool the full name`() {
        // "Double Cheese Margherita Pizza" is listed FIRST and contains the whole requested name as a substring.
        val menu = Menu(listOf(Dish("Double Cheese Margherita Pizza", "₹209"), Dish("Margherita Pizza")))
        val r = replay(learnedAdd, menu, "Margherita pizza")
        assertTrue(r.steps.toString(), r.success)
        assertEquals("Margherita Pizza", menu.addedTo)
    }

    @Test fun `case 5 - the dish is below the fold and is scrolled to`() {
        val menu = Menu(
            listOf(Dish("Margherita Pizza"), Dish("Farmhouse Pizza"), Dish("Peppy Paneer Pizza"), Dish("Veg Extravaganza")),
            scrollRevealsFrom = 2,
        )
        val r = replay(learnedAdd, menu, "Veg Extravaganza")
        assertTrue(r.steps.toString(), r.success)
        assertEquals("Veg Extravaganza", menu.addedTo)
        assertTrue("should have scrolled to reach it", menu.scrolls > 0)
    }

    @Test fun `case 6 - item already in cart is questioned, and another dish's ADD is never used instead`() {
        // The requested dish is in the cart, so its row shows a stepper and the only ADD on screen is a DIFFERENT
        // dish's. That ADD must not inherit the screen as its row (fix 1), and the run must say what it found.
        val menu = Menu(listOf(Dish("Margherita Pizza", inCart = 1), Dish("Farmhouse Pizza")))
        val r = replay(learnedAdd, menu, "Margherita pizza")
        assertTrue(!r.success)
        assertNull("nothing may be added", menu.addedTo)
        assertTrue(r.steps.last().error.orEmpty(), r.steps.last().error.orEmpty().contains("already in your cart, 1 of it"))
    }

    @Test fun `case 7 - a dish that is not on the menu asks a specific question and taps nothing`() {
        val menu = Menu(listOf(Dish("Margherita Pizza"), Dish("Farmhouse Pizza")))
        val r = replay(learnedAdd, menu, "Chicken Tikka Pizza")
        assertTrue(!r.success)
        assertNull("must not fall back to the first ADD", menu.addedTo)
        assertTrue(r.steps.last().error.orEmpty(), r.steps.last().error.orEmpty().contains("Chicken Tikka Pizza"))
    }

    // ---- the capture gap: the SAME task taught on a screen that showed only one ADD ----

    @Test fun `case 8 - taught on a single-row screen, an un-anchored ADD is refused, not guessed`() {
        // Recorder.rowContext() only records the row when the control already repeats, so a demo done straight after
        // a search (one result, one ADD) learns a bare resource id with no slot. Replayed on a crowded menu that id
        // matches every row's ADD: several different controls, nothing to choose between them, so it asks (fix 4).
        // Capturing the row every time is a separate Recorder fix and needs an APK rebuild.
        val menu = Menu(listOf(Dish("Margherita Pizza"), Dish("Farmhouse Pizza")))
        val r = replay(learnedAddNoRow, menu, "Farmhouse pizza")
        assertTrue(!r.success)
        assertNull("must not add the first dish it happens to find", menu.addedTo)
    }

    @Test fun `case 9 - one ADD on screen is still used when it really is the requested dish`() {
        // The conservative row must not over-refuse: a single search result is the common Zomato shape.
        val menu = Menu(listOf(Dish("Farmhouse Pizza")))
        val r = replay(learnedAdd, menu, "Farmhouse pizza")
        assertTrue(r.steps.toString(), r.success)
        assertEquals("Farmhouse Pizza", menu.addedTo)
    }

    private companion object { const val PKG = "com.application.zomato" }
}
