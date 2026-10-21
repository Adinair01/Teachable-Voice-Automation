package com.tva.teachable.replay

import com.tva.teachable.a11y.LiveTree
import com.tva.teachable.core.Flow
import com.tva.teachable.core.FlowStep
import com.tva.teachable.core.RowContext
import com.tva.teachable.core.Selector
import com.tva.teachable.core.SelectorMatcher
import com.tva.teachable.core.StepType
import com.tva.teachable.core.StuckCause
import com.tva.teachable.core.UiNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One test per defect of the 2026-10-21 accuracy audit (rows B, E, F, M, A and the "first match wins" list). Each
 * reproduces the audit's probe on the old code's terms and fails there; the comment says what used to happen.
 */
class AccuracyRegressionTest {
    private val z = "com.application.zomato"

    /** A flat tree from rows of (depth, id, text, desc, clickable). */
    private fun tree(vararg rows: List<Any>): List<UiNode> = rows.mapIndexed { i, r ->
        UiNode(i, r[0] as Int, z, r[1] as String, r[2] as String, r[3] as String, "", "0 0 10 10", r[4] as Boolean, false, false, false)
    }
    private fun row(depth: Int, id: String = "", text: String = "", desc: String = "", click: Boolean = false) = listOf(depth, id, text, desc, click)
    private val fast = ReplayConfig(waitMs = 40, retryMs = 20, pollMs = 10, settleMs = 20, scrollTries = 1)

    /** A fixed screen that records every tap and every question. */
    private class Screen(var nodes: List<UiNode>) : Device {
        val tapped = ArrayList<String>()
        override fun tree() = LiveTree(nodes, emptyList())
        override fun launch(packageName: String) = true
        override fun tap(tree: LiveTree, node: UiNode): Boolean { tapped += node.text.ifBlank { node.contentDesc }; return true }
        override fun type(tree: LiveTree, node: UiNode, value: String) = true
        override fun scroll(tree: LiveTree) = false
        override fun back() = true
    }

    private fun replay(screen: Screen, vararg steps: FlowStep, slots: Map<String, String> = emptyMap(), questions: MutableList<String> = ArrayList()) =
        runBlocking { Replayer(screen, cfg = fast, asker = { q -> questions += q.spoken; null }).run(Flow("k", "d", z, steps.toList()), slots) }

    // ---- B: an id match must agree with the recorded caption; nothing destructive is tapped on the flow's behalf ----

    @Test fun `B - the replace-cart dialog never gets Replace tapped for a step taught on "Woohoo! Thanks"`() {
        // Before: the id positive_button matched, the caption was ignored, and "Replace" was tapped.
        val screen = Screen(tree(row(0), row(1, text = "Replace cart item?"), row(1, "$z:id/negative_button", "No", click = true),
            row(1, "$z:id/positive_button", "Replace", click = true)))
        val questions = ArrayList<String>()
        val r = replay(screen, FlowStep(StepType.TAP, Selector(resourceId = "$z:id/positive_button", text = "Woohoo! Thanks")), questions = questions)
        assertTrue("nothing was tapped: ${screen.tapped}", screen.tapped.isEmpty())
        assertTrue(!r.success)
        assertEquals("the user is asked, specifically", 1, questions.size)
        assertTrue(questions[0], questions[0].contains("replace", ignoreCase = true))
        // The matcher alone already refuses it (the destructive guard is a second, independent line).
        assertNull(SelectorMatcher.target(screen.nodes, Selector(resourceId = "$z:id/positive_button", text = "Woohoo! Thanks"), null, true))
    }

    @Test fun `B - a deterministic match on a destructive control is refused unless the step was taught on that word`() {
        // An id with no caption that happens to sit on "Clear cart": refused and asked about.
        val cart = Screen(tree(row(0), row(1, "$z:id/cta", click = true), row(2, text = "Clear cart")))
        replay(cart, FlowStep(StepType.TAP, Selector(resourceId = "$z:id/cta")))
        assertTrue(cart.tapped.toString(), cart.tapped.isEmpty())
        // A calculator's taught "Clear" is the task itself, and is tapped.
        val calc = Screen(tree(row(0), row(1, "c:id/clr", desc = "Clear", click = true)))
        assertTrue(replay(calc, FlowStep(StepType.TAP, Selector(resourceId = "c:id/clr", contentDesc = "Clear"))).success)
        assertEquals(listOf("Clear"), calc.tapped)
    }

    @Test fun `B - a count or price in the caption no longer sends an id match to the first control with that id`() {
        // Before: "1 item added" vs a live "2 items added" -> the first "container" ("Offers for you") was tapped.
        val bar = tree(row(0), row(1, "$z:id/container", desc = "Offers for you", click = true), row(1, "$z:id/container", desc = "2 items added", click = true))
        assertEquals("2 items added", SelectorMatcher.target(bar, Selector(resourceId = "$z:id/container", contentDesc = "1 item added"), null, true)?.contentDesc)
        // Before: "Add item ₹279" vs a live "Add item ₹149" -> the first "button" ("Customise").
        val sheet = tree(row(0), row(1, "$z:id/button", "Customise", click = true), row(1, "$z:id/button", "Add item ₹149", click = true))
        assertEquals("Add item ₹149", SelectorMatcher.target(sheet, Selector(resourceId = "$z:id/button", text = "Add item ₹279"), null, true)?.text)
        // A rotating placeholder: 'Search "binge night"' taught, 'Search "comfort food"' live.
        val home = tree(row(0), row(1, "$z:id/search_bar", "Search \"comfort food\"", click = true))
        assertNotNull(SelectorMatcher.target(home, Selector(resourceId = "$z:id/search_bar", text = "Search \"binge night\""), null, true))
    }

    // ---- F: shorthand picks the CLOSEST name, in either list order; a real tie asks ----

    private fun menu(vararg names: String) = tree(row(0), row(1, click = false), *names.flatMap { n ->
        listOf(row(2, click = true), row(3, text = n), row(3, "$z:id/add", desc = "ADD", click = true))
    }.toTypedArray())

    private fun addFor(nodes: List<UiNode>, item: String): String? =
        SelectorMatcher.target(nodes, Selector(contentDesc = "ADD", near = item), item, true)?.let { add -> nodes[add.index - 1].text }

    @Test fun `F - "margherita" picks Margherita Pizza over Double Cheese Margherita Pizza in either order`() {
        // Before: the first row containing the word won, so the look-alike listed first was added.
        assertEquals("Margherita Pizza", addFor(menu("Double Cheese Margherita Pizza", "Margherita Pizza"), "margherita"))
        assertEquals("Margherita Pizza", addFor(menu("Margherita Pizza", "Double Cheese Margherita Pizza"), "margherita"))
    }

    @Test fun `F - two different names equally close is asked about, the same dish listed twice is not`() {
        assertNull(addFor(menu("Margherita Pizza", "Margherita Pasta"), "margherita"))
        assertEquals("Margherita Pizza", addFor(menu("Margherita Pizza", "Farmhouse Pizza", "Margherita Pizza"), "margherita"))
    }

    // ---- M: a learned "{{restaurant}}" finds the closest caption, and the landing is checked ----

    @Test fun `M - restaurant Pizza Hut opens "Pizza Hut", not "Pizza Hut Express" listed first`() {
        val list = tree(row(0), row(1, text = "Pizza Hut Express", click = true), row(1, text = "Pizza Hut", click = true))
        assertEquals("Pizza Hut", SelectorMatcher.target(list, Selector(text = "Pizza Hut"), "Pizza Hut", true)?.text)
        // No caption is exactly the value: the closest one wins, not the first one containing it.
        val outlets = tree(row(0), row(1, text = "Domino's Pizza Express", click = true), row(1, text = "Domino's Pizza", click = true))
        assertEquals("Domino's Pizza", SelectorMatcher.target(outlets, Selector(text = "Domino's"), "Domino's", true)?.text)
        // ...and the learned "Search in {{restaurant}}" finds the menu search, not the restaurant's own title.
        val page = tree(row(0), row(1, text = "Domino's Pizza", click = true), row(1, desc = "Search in Domino's Pizza", click = true))
        assertEquals("Search in Domino's Pizza", SelectorMatcher.target(page, Selector(contentDesc = "Search in Domino's"), "Domino's", true)?.contentDesc)
        assertEquals("Domino's Pizza", SelectorMatcher.target(page, Selector(text = "Domino's"), "Domino's", true)?.text)
    }

    @Test fun `M - a tap that opened a page not naming the restaurant is questioned, not carried on`() {
        val screen = Screen(tree(row(0), row(1, text = "Pizza Hut", click = true)))
        val step = FlowStep(StepType.TAP, Selector(text = "{{restaurant}}"), slotRef = "restaurant", expect = com.tva.teachable.core.Expect(textContains = "{{restaurant}}"))
        // The tap "works" but the page never changes to anything naming Pizza Hut Express.
        val r = replay(screen, step, slots = mapOf("restaurant" to "Pizza Hut Express"))
        assertTrue(!r.success)
    }

    // ---- E: plurals pass the landing check; a failed landing check is a question ----

    @Test fun `E - "Farmhouse pizzas" asked, "Farmhouse Pizza" shown, is the same dish`() {
        // Before: success=false, "expected screen not reached", and no question (probe P6).
        val screen = Screen(tree(row(0), row(1, text = "Farmhouse Pizza"), row(1, click = true), row(2, text = "Farmhouse Pizza"), row(2, "$z:id/add", desc = "ADD", click = true)))
        val step = FlowStep(StepType.TAP, Selector(contentDesc = "ADD", near = "{{item}}"), slotRef = "item", expect = com.tva.teachable.core.Expect(textContains = "{{item}}"))
        val r = replay(screen, step, slots = mapOf("item" to "Farmhouse pizzas"))
        assertTrue(r.steps.toString(), r.success)
    }

    @Test fun `E - an expected screen that does not show up is asked about with what IS there`() {
        val screen = Screen(tree(row(0), row(1, text = "Details", click = true), row(1, text = "Double Cheese Margherita")))
        val step = FlowStep(StepType.TAP, Selector(text = "Details"), slotRef = "item", expect = com.tva.teachable.core.Expect(textContains = "{{item}}"))
        val questions = ArrayList<String>()
        replay(screen, step, slots = mapOf("item" to "Farmhouse pizza"), questions = questions)
        assertEquals(1, questions.size)
        assertTrue(questions[0], questions[0].contains("doesn't mention \"Farmhouse pizza\""))
    }

    // ---- every remaining non-safety stop is a question, never a bare "failed" ----

    @Test fun `a back that does nothing and a missing key are questions too`() {
        val dead = object : Device by Screen(tree(row(0), row(1, text = "x"))) { override fun back() = false }
        val q = ArrayList<String>()
        runBlocking { Replayer(dead, cfg = fast, asker = { s -> q += s.spoken; null }).run(Flow("k", "d", z, listOf(FlowStep(StepType.BACK))), emptyMap()) }
        assertEquals(1, q.size)
    }

    // ---- A: a stuck step is asked within the budget, and a run has a hard cap ----

    @Test fun `A - the 37_9 s probe now reaches its question inside the 16 s step budget`() = runBlocking {
        var typed = ""
        val base = tree(row(0), row(1, "$z:id/edittext", click = true), row(1, text = "Got it", click = true))
        val dev = object : Device {
            override fun tree() = LiveTree(base.map { if (it.resourceId.endsWith("edittext")) it.copy(text = typed) else it }, emptyList())
            override fun launch(packageName: String) = true
            override fun tap(tree: LiveTree, node: UiNode) = true          // "Got it" never goes away
            override fun type(tree: LiveTree, node: UiNode, value: String): Boolean { typed = value; return true }
            override fun scroll(tree: LiveTree) = true                    // always scrolls, never reveals
            override fun back() = true
        }
        val slowPicker = ElementPicker { _, _, _ -> delay(15_000); null } // the phone's old 15 s read timeout
        var askedAfter = 0L
        val start = System.currentTimeMillis()
        Replayer(dev, slowPicker, asker = { askedAfter = System.currentTimeMillis() - start; null }).run(
            Flow("k", "d", z, listOf(FlowStep(StepType.TYPE, Selector(resourceId = "$z:id/edittext"), value = "x"), FlowStep(StepType.TAP, Selector(text = "Continue")))),
            emptyMap(),
        )
        println("A: question handed over after $askedAfter ms (was 37 866 ms)")
        assertTrue("asked after $askedAfter ms", askedAfter in 1..18_000)
    }

    @Test fun `A - a run stops at the hard cap, at a step boundary, with a reason`() = runBlocking {
        var clock = 0L
        val dev = object : Device by Screen(tree(row(0), row(1, text = "Next", click = true))) {
            override fun tap(tree: LiveTree, node: UiNode): Boolean { clock += 60_000; return true } // each tap "takes" a minute
        }
        val step = FlowStep(StepType.TAP, Selector(text = "Next"))
        val r = Replayer(dev, cfg = fast.copy(runCapMs = 150_000, clock = { clock })).run(Flow("k", "d", z, List(5) { step }), emptyMap())
        assertTrue(!r.success)
        assertEquals(4, r.steps.size) // 3 taps (3 minutes) then the stop at step 4
        assertTrue(r.steps.last().error!!, r.steps.last().error!!.startsWith("This has taken over 2 minutes"))
    }

    // ---- the remaining "first match wins" places ----

    @Test fun `Q13 - the upsell pass never taps OK on a replace-cart dialog (it used to, from the quantity step)`() {
        val screen = Screen(tree(row(0), row(1, text = "Replace cart item?"), row(1, text = "OK", click = true)))
        replay(screen, FlowStep(StepType.SET_QUANTITY, Selector(near = "{{item}}"), slotRef = "quantity"), slots = mapOf("quantity" to "2", "item" to "Farmhouse pizza"))
        assertTrue(screen.tapped.toString(), screen.tapped.isEmpty())
    }

    @Test fun `Q13 - two saved addresses with the same label are asked about, not chosen by list order`() {
        val screen = Screen(tree(row(0), row(1, text = "Deliver to Home", click = true),
            row(1, click = true), row(2, text = "Work"), row(2, text = "Tidel Park, Chennai"),
            row(1, click = true), row(2, text = "Work"), row(2, text = "Manyata Tech Park, Bengaluru")))
        val q = ArrayList<String>()
        replay(screen, FlowStep(StepType.CHOOSE_ADDRESS, slotRef = "address"), slots = mapOf("address" to "Work"), questions = q)
        assertTrue(screen.tapped.toString(), "Work" !in screen.tapped)
        assertTrue(q.toString(), q.single().contains("2 saved addresses called \"Work\""))
    }

    @Test fun `Q13 - RowContext pick and Steppers find no longer depend on tree order`() {
        val parentOrder = listOf("Double Cheese Margherita Pizza", "Margherita Pizza")
        for (names in listOf(parentOrder, parentOrder.reversed())) {
            val nodes = menu(*names.toTypedArray())
            val adds = nodes.filter { it.contentDesc == "ADD" }
            val picked = RowContext.pick(nodes, adds, "", "margherita") { it.contentDesc == "ADD" }
            assertEquals("Margherita Pizza", nodes[picked!!.index - 1].text)
        }
    }

    @Test fun `Q13 - typing into the first of two same-id fields is safe - a wrong field ends in a question, nothing pressed`() {
        // Zomato's home keeps one search field per cached page; typing takes the first. Typing presses nothing, and if
        // the text went to a field that does nothing, the next step cannot find its target: it retypes once, then asks.
        val screen = Screen(tree(row(0), row(1, "$z:id/edittext", click = true), row(1, "$z:id/edittext", click = true)))
        val q = ArrayList<String>()
        replay(screen, FlowStep(StepType.TYPE, Selector(resourceId = "$z:id/edittext"), slotRef = "item", value = "{{item}}"),
            FlowStep(StepType.TAP, Selector(contentDesc = "ADD", near = "{{item}}"), slotRef = "item"), slots = mapOf("item" to "Farmhouse pizza"), questions = q)
        assertTrue(screen.tapped.toString(), screen.tapped.isEmpty())
        assertEquals(1, q.size)
    }

    @Test fun `progress names each step with THIS run's values, as it starts`() {
        val screen = Screen(tree(row(0), row(1, text = "Margherita Pizza", click = true), row(1, desc = "Next", click = true)))
        val seen = ArrayList<String>()
        runBlocking {
            Replayer(screen, cfg = fast, progress = { i, n, what -> seen += "${i + 1}/$n $what" }).run(Flow("k", "d", z, listOf(
                FlowStep(StepType.TAP, Selector(text = "{{item}}"), slotRef = "item"),
                FlowStep(StepType.TAP, Selector(contentDesc = "Next")),
            )), mapOf("item" to "Margherita pizza"))
        }
        assertEquals(listOf("1/2 tapping Margherita pizza", "2/2 tapping Next"), seen)
    }

    // ---- answers to a question, as given in the live session of 2026-10-21 ----

    /**
     * The cart as Zomato really builds it (live dump of 2026-10-21): ONE window holding the menu page (drawn at 4), the
     * menu's bottom bar "2 items added | Continue" (9), a full-screen tappable backdrop (13) and the cart page (15) —
     * the cart is not at either end of the tree.
     */
    private inner class Cart : Device {
        val tapped = ArrayList<String>()
        var picker = false
        override fun tree(): LiveTree {
            val full = "0 0 1080 2412"
            fun n(depth: Int, text: String = "", desc: String = "", click: Boolean = false, b: String = full, z: Int = 1) =
                UiNode(0, depth, z.toString(), "", text, desc, "", b, click, false, false, false, z)
            val menu = listOf(n(1, z = 4), n(2, "Unlock Flat ₹125 OFF", b = "0 300 1080 400"), n(2, "Cheese Volcano Farmhouse", b = "0 400 1080 500"), n(2, desc = "ADD", click = true, b = "800 400 1000 500"))
            val bar = listOf(n(1, z = 9), n(2, "2 items added", b = "0 2255 700 2317"), n(2, "Continue", click = true, b = "700 2255 1000 2317"))
            val backdrop = listOf(n(1, click = true, z = 13))
            val page = listOf(n(1, z = 15)) + if (!picker) listOf(n(2, "Bill details", b = "0 300 1080 400"), n(2, "Select address at next step", click = true, b = "0 2258 1080 2334"))
                else listOf(n(2, "Select an address", b = "0 300 1080 400"), n(2, "Home", click = true, b = "0 400 1080 500"), n(2, "Work", click = true, b = "0 500 1080 600"))
            val all = listOf(n(0)) + menu + bar + backdrop + page
            return LiveTree(all.mapIndexed { i, x -> x.copy(index = i, packageName = z) }, emptyList())
        }
        override fun tap(tree: LiveTree, node: UiNode): Boolean {
            val c = node.text.ifBlank { node.contentDesc }; tapped += c
            if (c == "Select address at next step") picker = true
            return true
        }
        override fun launch(packageName: String) = true
        override fun type(tree: LiveTree, node: UiNode, value: String) = true
        override fun scroll(tree: LiveTree) = false
        override fun back() = true
    }

    private fun answered(reply: String, device: Cart = Cart()): Pair<Cart, List<String>> {
        val questions = ArrayList<String>()
        var replies = listOf(reply)
        runBlocking {
            Replayer(device, cfg = fast, asker = { q -> questions += q.spoken; replies.firstOrNull().also { replies = replies.drop(1) } })
                .run(Flow("k", "d", z, listOf(FlowStep(StepType.TAP, Selector(text = "APPLY")))), emptyMap())
        }
        return device to questions
    }

    @Test fun `answer - "tap select address at next step button and just select home" taps both, in order`() {
        // Before: "I can't see "select address at next step button and just sel…" on this screen."
        val (cart, _) = answered("tap select address at next step button and just select home")
        assertEquals(listOf("Select address at next step", "Home"), cart.tapped)
    }

    @Test fun `answer - "click bottom button" taps the lowest control of the page on top`() {
        val (cart, _) = answered("Click bottom button.")
        assertEquals(listOf("Select address at next step"), cart.tapped)
    }

    @Test fun `the question describes the page on top, not the menu under the cart`() {
        val (_, questions) = answered("stop")
        // Before: "This screen shows "Unlock Flat ₹125 OFF", "ADD", "₹355", "Cheese Volcano Farmhouse"" on the cart.
        assertTrue(questions.first(), questions.first().contains("\"Select address at next step\""))
        assertTrue("the menu under the cart is not described: ${questions.first()}", !questions.first().contains("Unlock Flat") && !questions.first().contains("Continue"))
    }

    @Test fun `at the cart, a leftover demo step (the old flow's coupon APPLY) is skipped, not asked about`() {
        // Seen live: every old-flow run reached the cart and then asked "I can't find APPLY… What should I do?".
        val questions = ArrayList<String>()
        val r = runBlocking {
            Replayer(Cart(), cfg = fast, asker = { q -> questions += q.spoken; null }).run(
                Flow("k", "d", z, listOf(FlowStep(StepType.TAP, Selector(text = "APPLY")), FlowStep(StepType.SET_QUANTITY, Selector(near = "{{item}}"), slotRef = "quantity"))),
                mapOf("item" to "Margherita pizza"))
        }
        assertTrue(questions.toString(), questions.isEmpty())
        assertTrue(r.steps.toString(), r.success)
    }

    @Test fun `"Done, thank you" in answer to a question ends the run as the user's success`() {
        val questions = ArrayList<String>()
        val r = runBlocking {
            Replayer(Cart(), cfg = fast, asker = { q -> questions += q.spoken; "Done. Thank you." })
                .run(Flow("k", "d", z, listOf(FlowStep(StepType.TAP, Selector(text = "APPLY")), FlowStep(StepType.TAP, Selector(text = "Woohoo! Thanks")))), emptyMap())
        }
        assertEquals(1, questions.size)
        assertTrue(r.success)
        assertTrue(r.finishedByUser)
    }

    @Test fun `the real Zomato cart screen - only the cart is seen, and the description names its own bottom action`() {
        // Live dump (2026-10-21) of the cart over the menu, with Android's drawing order.
        val arr = org.json.JSONArray(javaClass.getResource("/screens/zomato_cart_over_menu.json")!!.readText())
        val nodes = (0 until arr.length()).map { k -> arr.getJSONObject(k).let { o ->
            UiNode(o.getInt("i"), o.getInt("d"), o.getString("pkg"), o.getString("id"), o.getString("text"), o.getString("desc"), o.getString("cls"),
                o.getString("b"), o.getBoolean("click"), o.getBoolean("edit"), o.getBoolean("scroll"), false, o.getInt("z"), o.getBoolean("vis"))
        } }
        val seen = com.tva.teachable.core.Visibility.visible(nodes).map { it.text.ifBlank { it.contentDesc }.trim() }.filter { it.isNotEmpty() }
        assertTrue(seen.toString(), "Farmhouse Pizza" in seen && "Margherita Pizza" in seen && "Select address at next step" in seen)
        assertTrue("the menu's bar under the cart: $seen", "2 items added" !in seen && "Continue" !in seen)
        assertTrue("the menu list under the cart: $seen", seen.none { it.startsWith("Flat ₹250 OFF") || it == "Most ordered together" })
        val summary = com.tva.teachable.core.ScreenSummary.of(nodes)
        println("cart summary: $summary")
        assertTrue(summary, summary.contains("\"Select address at next step\""))
        assertTrue(summary, !summary.contains("Continue") && !summary.contains("Unlock Flat"))
    }

    @Test fun `an empty full-screen container or a floating button drawn on top hides nothing`() {
        fun n(i: Int, d: Int, text: String = "", click: Boolean = false, b: String = "0 0 1080 2400", z: Int = 1) =
            UiNode(i, d, z.toString(), "", text, "", "", b, click, false, false, false, z)
        val nodes = listOf(n(0, 0), n(1, 1, z = 1), n(2, 2, "Margherita Pizza", click = true, b = "0 300 1080 400"),
            n(3, 1, z = 9),                                                  // an empty full-screen overlay container
            n(4, 1, z = 12), n(5, 2, "Menu", click = true, b = "800 2000 1040 2120")) // a floating button's container
        assertTrue(com.tva.teachable.core.Visibility.visible(nodes).any { it.text == "Margherita Pizza" })
    }

    private fun realCart(): List<UiNode> {
        val arr = org.json.JSONArray(javaClass.getResource("/screens/zomato_cart_over_menu.json")!!.readText())
        return (0 until arr.length()).map { k -> arr.getJSONObject(k).let { o ->
            UiNode(o.getInt("i"), o.getInt("d"), o.getString("pkg"), o.getString("id"), o.getString("text"), o.getString("desc"), o.getString("cls"),
                o.getString("b"), o.getBoolean("click"), o.getBoolean("edit"), o.getBoolean("scroll"), false, o.getInt("z"), o.getBoolean("vis"))
        } }
    }

    @Test fun `step matching on the real cart - the menu's hidden Continue is never a target, the cart's controls are`() {
        val nodes = realCart()
        // Before: a taught "Continue" step matched the menu's bar UNDER the open cart and tapped it.
        assertNull(SelectorMatcher.target(nodes, Selector(text = "Continue"), null, true))
        // The menu's own tappable controls are all still in the tree under the cart: none of them is a target now.
        for (hiddenControl in listOf("Veg", "Filters", "See items", "4 offers"))
            assertNull(hiddenControl, SelectorMatcher.target(nodes, Selector(text = hiddenControl), null, true))
        assertEquals("Select address at next step", SelectorMatcher.target(nodes, Selector(text = "Select address at next step"), null, true)?.text)
        // The quantity stepper of a dish IN THE CART, not of the same dish on the menu underneath.
        val stepper = com.tva.teachable.core.Steppers.find(nodes, "Margherita Pizza", "Margherita")
        assertEquals("the cart's Margherita stepper (node 121)", 121, stepper?.number?.index)
        assertNotNull(com.tva.teachable.core.ScreenGuard.readyToPay(nodes))
    }

    @Test fun `relaunching an app already in front on an identical screen is not "never came to the front"`() {
        // Seen live: Calculator in front showing "1+1 = 2" restarted onto the very same screen, and the run asked.
        val screen = Screen(tree(row(0), row(1, text = "2"), row(1, text = "+", click = true)))
        val questions = ArrayList<String>()
        val r = replay(screen, FlowStep(StepType.LAUNCH, value = z), FlowStep(StepType.TAP, Selector(text = "+")), questions = questions)
        assertTrue(r.steps.toString(), r.success)
        assertTrue(questions.toString(), questions.isEmpty())
    }

    @Test fun `a new task said in answer ends the stuck run and is handed on as a command`() {
        val r = runBlocking {
            Replayer(Cart(), cfg = fast, asker = { "order a burger from McDonald's" })
                .run(Flow("k", "d", z, listOf(FlowStep(StepType.TAP, Selector(text = "APPLY")))), emptyMap())
        }
        assertEquals("order a burger from McDonald's", r.newCommand)
        assertTrue(r.stoppedByUser)
        assertTrue(!r.success)
    }

    @Test fun `the Stop button ends the run as the next step starts, touching nothing more`() {
        var stop = false
        val screen = Screen(tree(row(0), row(1, text = "Next", click = true)))
        val dev = object : Device by screen { override fun tap(tree: LiveTree, node: UiNode): Boolean { stop = true; return screen.tap(tree, node) } }
        val r = runBlocking {
            Replayer(dev, cfg = fast, stopRequested = { stop }).run(Flow("k", "d", z, List(4) { FlowStep(StepType.TAP, Selector(text = "Next")) }), emptyMap())
        }
        assertEquals("one tap, then the Stop pressed during it ends the run", 1, screen.tapped.size)
        assertTrue(r.stoppedByUser)
        assertEquals("you asked me to stop", r.steps.last().error)
    }

    @Test fun `Amazon - Add to cart in the row sharing most of the spoken product words in any order - a tie asks`() {
        val ultra = "Samsung Galaxy S25 Ultra 5G AI Smartphone (Titanium Silverblue, 12GB RAM, 256GB Storage)"
        val plain = "Samsung Galaxy S25 5G AI Smartphone (Navy, 8GB RAM, 128GB Storage)"
        val s24 = "Samsung Galaxy S24 Ultra 5G AI Smartphone (Titanium Gray, 12GB RAM, 256GB Storage)"
        fun shop(vararg titles: String) = tree(row(0), row(1), *titles.flatMap { t ->
            listOf(row(2, click = true), row(3, text = t), row(3, text = "Add to cart", click = true))
        }.toTypedArray())
        fun added(nodes: List<UiNode>, item: String) =
            SelectorMatcher.target(nodes, Selector(text = "Add to cart", near = item), item, true)?.let { nodes[it.index - 1].text }
        for (order in listOf(listOf(plain, s24, ultra), listOf(ultra, plain, s24), listOf(s24, ultra, plain)))
            assertEquals(order.toString(), ultra, added(shop(*order.toTypedArray()), "S25 ultra 12GB256GB phone"))
        // Two different listings sharing the same number of words: no guess.
        assertNull(added(shop(ultra, "Samsung Galaxy S25 Edge 5G AI Smartphone (Silver, 12GB RAM, 256GB Storage)"), "S25 256GB"))
    }
}
