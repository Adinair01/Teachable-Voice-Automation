package com.tva.teachable.replay

import com.tva.teachable.a11y.LiveTree
import com.tva.teachable.core.Answer
import com.tva.teachable.core.AnswerParser
import com.tva.teachable.core.Boundary
import com.tva.teachable.core.Correction
import com.tva.teachable.core.CorrectionKind
import com.tva.teachable.core.CorrectionOp
import com.tva.teachable.core.CorrectionPolicy
import com.tva.teachable.core.CartState
import com.tva.teachable.core.BoundaryKind
import com.tva.teachable.core.Flow
import com.tva.teachable.core.FlowStep
import com.tva.teachable.core.RowContext
import com.tva.teachable.core.ScreenGuard
import com.tva.teachable.core.ScreenSummary
import com.tva.teachable.core.Selector
import com.tva.teachable.core.SelectorMatcher
import com.tva.teachable.core.StepType
import com.tva.teachable.core.StuckCause
import com.tva.teachable.core.TapTarget
import com.tva.teachable.core.Steppers
import com.tva.teachable.core.UiNode
import com.tva.teachable.core.resolveSlots
import kotlinx.coroutines.delay

/** What the replayer needs from the phone. Implemented by the AccessibilityService; faked in tests. */
interface Device {
    fun tree(): LiveTree
    fun launch(packageName: String): Boolean
    fun tap(tree: LiveTree, node: UiNode): Boolean
    fun type(tree: LiveTree, node: UiNode, value: String): Boolean
    fun scroll(tree: LiveTree): Boolean
    fun back(): Boolean
}

/** Asked before any sensitive step. Default denies: nothing sensitive ever runs without explicit consent. */
fun interface ConfirmationGate { suspend fun confirm(step: FlowStep, slots: Map<String, String>): Boolean }
val DenyAll = ConfirmationGate { _, _ -> false }

data class ReplayConfig(val waitMs: Long = 6000, val retryMs: Long = 3000, val pollMs: Long = 300, val scrollTries: Int = 3, val settleMs: Long = 3000)
data class StepResult(
    val index: Int, val ok: Boolean, val usedLlmFallback: Boolean = false, val error: String? = null,
    val declined: Boolean = false, val handover: Boundary? = null,
    /** Set when the step stopped because it could not proceed (not because of a safety stop): the run may ask. */
    val cause: StuckCause? = null,
)

/** A question put to the user because a step could not proceed. [spoken] is the whole thing to say aloud. */
data class Stuck(val stepIndex: Int, val cause: StuckCause, val spoken: String)

/** Puts a [Stuck] to the user and returns what they said, or null if they stayed silent. Without one, a stuck run just reports and stops. */
fun interface StuckAsker { suspend fun ask(q: Stuck): String? }
/**
 * [declined] = the user said no to a sensitive step. That is the safety feature working, not a failed replay.
 * [handover] = the run hit a payment/OTP/login/password boundary and gave the phone back. Reaching payment is the
 * end of every ordering flow, so a PAYMENT handover with all earlier steps done is a success.
 */
data class RunResult(
    val success: Boolean, val steps: List<StepResult>, val declined: Boolean = false, val handover: Boundary? = null,
    /** What the user's answers imply for the stored flow. Persisting any of it is the backend's job, and needs a yes. */
    val corrections: List<Correction> = emptyList(),
    val questions: Int = 0,
)

class Replayer(
    private val device: Device,
    private val picker: ElementPicker? = null,
    private val gate: ConfirmationGate = DenyAll,
    private val cfg: ReplayConfig = ReplayConfig(),
    private val asker: StuckAsker? = null,
) {
    private var appPackage = ""
    private var llmPicks = 0
    private var asked = 0
    private val corrections = ArrayList<Correction>()

    suspend fun run(flow: Flow, slots: Map<String, String>): RunResult {
        appPackage = flow.appPackage; llmPicks = 0; asked = 0; corrections.clear()
        val results = ArrayList<StepResult>()
        for ((i, step) in flow.steps.withIndex()) {
            val next = flow.steps.getOrNull(i + 1)
            var r = runStep(i, step, next, slots)
            // A step that could not proceed is a question, not a verdict: ask, act on the answer, and carry on.
            if (!r.ok && r.cause != null && !r.declined && r.handover == null && asker != null) {
                r = resolveByAsking(i, step, next, slots, r, results, flow.steps.size)
            }
            results += r
            r.handover?.let { return done(it.kind == BoundaryKind.PAYMENT, results, handover = it) }
            if (!r.ok) return done(false, results, declined = r.declined)
        }
        val end = awaitStable().nodes
        // An ordering flow ends where the user pays; the cart may still be loading after the last tap, so wait for it.
        val ordering = flow.steps.any { it.type == StepType.SET_QUANTITY || it.type == StepType.CHOOSE_ADDRESS }
        // A popup (a coupon offer) can hide the pay bar; the flow still ended where it was taught to stop, before paying.
        var handover = ScreenGuard.screen(end) ?: ScreenGuard.readyToPay(end)
            ?: if (ordering) poll(cfg.waitMs) { ScreenGuard.readyToPay(it.nodes) } else null
        if (ordering && handover == null) {
            // Ran out of steps short of the cart: the demonstration stopped early. Ask how to get there, don't go quiet.
            handover = askToReachCart(flow, slots, results)
            if (handover == null) return notAtCart(flow, results)
        }
        return done(true, results, handover = handover)
    }

    private fun done(success: Boolean, steps: List<StepResult>, declined: Boolean = false, handover: Boundary? = null) =
        RunResult(success, steps, declined, handover, corrections.toList(), asked)

    private fun notAtCart(flow: Flow, results: List<StepResult>): RunResult {
        val err = "I did every step, but I'm not on the cart or checkout screen, so I can't say the order is ready. This screen shows ${ScreenSummary.of(device.tree().nodes)}."
        return done(false, results + StepResult(flow.steps.size, false, error = err))
    }

    private suspend fun runStep(i: Int, step: FlowStep, next: FlowStep?, slots: Map<String, String>): StepResult {
        // Checked on the live screen before every step, whatever was flagged while teaching: nothing but launching
        // the app (which touches no screen) is allowed once a credential or payment screen is up.
        if (step.type != StepType.LAUNCH && step.type != StepType.WAIT) {
            ScreenGuard.screen(device.tree().nodes)?.let { return handover(i, it) }
        }
        if (step.type == StepType.TAP) ScreenGuard.actionText("${step.selector.text} ${step.selector.contentDesc}")?.let { return handover(i, it) }
        if (step.sensitive && !gate.confirm(step, slots)) return StepResult(i, false, error = "sensitive step not confirmed", declined = true)
        val value = resolveSlots(step.value, slots)
        val slotValue = step.slotRef?.let { slots[it] }?.trim().orEmpty()
        return when (step.type) {
            StepType.LAUNCH -> launched(i, step, value, slots)
            StepType.WAIT -> { delay(value.toLongOrNull() ?: 500); StepResult(i, true) }
            StepType.BACK -> finish(i, step, device.back(), slots = slots)
            StepType.SCROLL -> finish(i, step, device.scroll(device.tree()), slots = slots)
            StepType.TAP, StepType.TYPE -> targeted(i, step, next, slots, value)
            // Optional by design: "order a pizza" keeps the app's own quantity and address.
            StepType.SET_QUANTITY -> if (slotValue.isEmpty()) StepResult(i, true) else quantity(i, step, slots, slotValue).askable()
            StepType.CHOOSE_ADDRESS -> if (slotValue.isEmpty()) StepResult(i, true) else address(i, slotValue).askable()
        }
    }

    /** A quantity or address that could not be applied is a property of this request, so it is a question, never a flow defect. */
    private fun StepResult.askable() = if (!ok && handover == null && !declined) copy(cause = StuckCause.SLOT_ABSENT) else this

    /**
     * Opens the app and waits until it is really showing its start screen.
     *
     * "Is this package in front" is not enough: when the app is ALREADY in front on some other screen — a restaurant
     * page from a minute ago — that is true the instant it is asked, so the flow used to race on and look for the home
     * search bar on the wrong screen. The launch clears the task, so the screen genuinely changes; we wait for that,
     * then for it to settle.
     */
    private suspend fun launched(i: Int, step: FlowStep, pkg: String, slots: Map<String, String>): StepResult {
        val before = device.tree()
        val wasInFront = before.nodes.firstOrNull()?.packageName == pkg
        val beforeSig = signature(before)
        if (!device.launch(pkg)) return StepResult(i, false, error = "I couldn't open that app.")
        val arrived = waitFor { t ->
            t.nodes.firstOrNull()?.packageName == pkg && (!wasInFront || signature(t) != beforeSig)
        }
        // Even a cold start paints in stages; the first step must not read a half-built screen.
        if (arrived) awaitStable()
        return finish(i, step, arrived, slots = slots)
    }

    private suspend fun targeted(i: Int, step: FlowStep, next: FlowStep?, slots: Map<String, String>, value: String): StepResult {
        val sel = resolved(step.selector, slots)
        val isTap = step.type == StepType.TAP
        val slotValue = step.slotRef?.let { slots[it] }
        val probe = probeFor(step.type, sel, slotValue)

        var fallback = false
        var found = poll(cfg.waitMs, probe)
        // A value the command parameterised may need entering one character at a time: the demonstration tapped the
        // single key "5", but "12" is two keys and no button carries that label.
        if (found == null && isTap && step.slotRef != null) {
            keyedIn(i, step, sel, slotValue)?.let { return it }
        }
        // A sheet or upsell seen during the demo ("Unlock 20% OFF… Continue") need not appear again: if the next step
        // can already run, this one was an interstitial that was not shown this time.
        //
        // NEVER for a step the command parameterised. An interstitial is app chrome and is never slot-bound, whereas
        // a missing slot target means the flow cannot do what was asked: skipping it once reported "Done." for
        // "what is 12 plus 7" after tapping only "+ 7 =", because the step after it happened to be on screen.
        if (found == null && step.slotRef == null && next != null && isDistinctive(next) &&
            probeFor(next.type, resolved(next.selector, slots), next.slotRef?.let { slots[it] })(device.tree()) != null) {
            return StepResult(i, true, error = "skipped: not shown this time")
        }
        // A popup sitting on the screen hides the step's control. Close what only CLOSES it — before the LLM picker
        // and before any question — but never "Continue"/"Proceed"/"OK", which move the flow on (or confirm a dialog)
        // rather than clear the way, and never anywhere near a cart-replacement prompt.
        if (found == null && !blocksAutoDismissal(device.tree().nodes)) {
            var passes = 0
            while (found == null && passes < MAX_DISMISSALS && passInterstitial(DISMISSAL)) { passes++; found = poll(cfg.retryMs, probe) }
        }
        // A tap the user once said can be left out. Only ever a plain tap: a missing slot target is never skipped quietly.
        if (found == null && step.optional && step.slotRef == null && isTap) {
            return StepResult(i, true, error = "skipped: optional step not shown")
        }
        if (found == null && sel.near.isNotEmpty()) {
            // Row-anchored target (the ADD in the Farmhouse row) may be further down the list. Never handed to the LLM:
            // a wrong guess here adds the wrong dish.
            // Asked before scrolling: once the list has moved on, the item's own row (and its stepper) is out of view.
            alreadyInCart(step, slots)?.let { return StepResult(i, false, error = it, cause = StuckCause.CART_CONFLICT) }
            repeat(cfg.scrollTries) {
                if (found == null && device.scroll(device.tree())) found = poll(cfg.retryMs, probe)
            }
            if (found == null) return stuckResult(i, step, sel, "\"${sel.near}\"")
        }
        // A target can simply be further down a list that the demonstration had already scrolled past ("Apps" below the
        // fold when the demo was given on "Battery"). True of a plain tap just as much as a slot-bound one: a step
        // learned from an answer ("tap Apps") must be able to reach its control the way that answer did, or the flow
        // learns the right control and then asks about it on every run. Scrolling only reveals; the target is still
        // found by the same selector, so nothing is ever tapped on a guess. A brief wait per scroll: the list has
        // settled long before the full retry window.
        if (found == null && sel.near.isEmpty() && (isTap || !slotValue.isNullOrEmpty())) {
            repeat(cfg.scrollTries) {
                if (found == null && device.scroll(device.tree())) found = poll(minOf(cfg.retryMs, SCROLL_SETTLE_MS), probe)
            }
        }
        if (found == null) found = poll(cfg.retryMs, probe) // retry once
        // LLM fallback: also how unexpected popups get dismissed/bypassed. Capped per run so a screen that keeps
        // changing under the assistant ends in a question instead of a string of guesses.
        if (found == null && picker != null && llmPicks < MAX_LLM_PICKS) {
            val tree = device.tree()
            val idx = picker.pick(step, slots, tree.nodes)
            // A guess must never be the one that throws the user's cart or data away ("Replace", "Clear").
            tree.nodes.getOrNull(idx ?: -1)?.takeUnless { CartState.DESTRUCTIVE.containsMatchIn(it.text + " " + it.contentDesc) }
                ?.takeIf { plausible(step, sel, it) }
                ?.let { found = tree to it; fallback = true; llmPicks++ }
        }
        val (tree, node) = found ?: return stuckResult(i, step, sel, targetLabel(step, sel))
        if (isTap) ScreenGuard.action(tree.nodes, node)?.let { return handover(i, it) }
        val before = signature(tree)
        val acted = if (isTap) device.tap(tree, node) else typeAndVerify(tree, node, sel, value)
        // Let the screen react before the next step looks for its target, or it may act on the old screen.
        if (acted && isTap) poll(cfg.settleMs) { t -> t.takeIf { signature(it) != before } }
        return finish(i, step, acted, fallback, slots)
    }

    /**
     * Text set while a screen is still opening can be wiped when the field initialises: if the screen does not react
     * at all, the text is set again.
     */
    private suspend fun typeAndVerify(tree: LiveTree, node: UiNode, sel: Selector, value: String): Boolean {
        if (node.password) return device.type(tree, node, value) // never read back
        // Typing into a screen that is still initialising gets wiped when the field resets: wait for it to settle.
        var t = awaitStable()
        var n = SelectorMatcher.target(t.nodes, sel, null, forTap = false) ?: node.also { t = tree }
        // Some fields keep reporting their hint as their text, so "did it stick" is judged on the whole screen: any
        // reaction (a clear button, a spinner, results) counts. Slow search results are the next step's wait to handle;
        // retyping into a field that did react would only restart the search.
        repeat(TYPE_TRIES) {
            val before = signature(t)
            if (!device.type(t, n, value)) return false
            if (poll(cfg.retryMs) { tt -> tt.takeIf { signature(it) != before } } != null) return true
            android.util.Log.w("TVA", "replay: typed text did not show on screen, retrying")
            t = device.tree()
            n = SelectorMatcher.target(t.nodes, sel, null, forTap = false) ?: return false
        }
        return false
    }


    /**
     * Types a multi-character slot value on a keypad, one key per character, when no single control carries the whole
     * value. Only for all-digit values whose every digit is on screen as its own tappable key, so it cannot wander off
     * and press something unrelated; a screen the guard holds back is handed over as usual.
     */
    private suspend fun keyedIn(i: Int, step: FlowStep, sel: Selector, value: String?): StepResult? {
        val v = value?.trim().orEmpty()
        if (v.length < 2 || !v.all { it.isDigit() }) return null
        val keyFor = { c: Char, t: LiveTree ->
            SelectorMatcher.target(t.nodes, sel.copy(text = c.toString(), near = ""), null, forTap = true)
        }
        // Every key must be there BEFORE anything is pressed: half a number entered is worse than none.
        val tree = device.tree()
        if (v.any { keyFor(it, tree) == null }) return null
        for (c in v) {
            val t = device.tree()
            val key = keyFor(c, t) ?: return StepResult(i, false, error = stuck("the key \"$c\""))
            ScreenGuard.action(t.nodes, key)?.let { return handover(i, it) }
            if (!device.tap(t, key)) return StepResult(i, false, error = "couldn't press \"$c\"")
            poll(cfg.settleMs) { tt -> tt.takeIf { signature(it) != signature(t) } }
        }
        android.util.Log.i("TVA", "replay: entered \"$v\" one key at a time")
        return StepResult(i, true)
    }

    /** Only a tap with its own caption or row anchor proves the screen moved on; a bare text-field id exists everywhere. */
    private fun isDistinctive(step: FlowStep) = step.type == StepType.TAP &&
        (step.selector.text.isNotBlank() || step.selector.contentDesc.isNotBlank() || step.selector.near.isNotBlank())

    /** Returns the screen once it has not changed for [QUIET_MS] (at most [cfg.settleMs] of waiting). */
    private suspend fun awaitStable(): LiveTree {
        var last = device.tree()
        var quiet = 0L
        var waited = 0L
        while (quiet < QUIET_MS && waited < cfg.settleMs) {
            delay(cfg.pollMs); waited += cfg.pollMs
            val now = device.tree()
            quiet = if (signature(now) == signature(last)) quiet + cfg.pollMs else 0
            last = now
        }
        return last
    }

    private fun signature(t: LiveTree) = t.nodes.take(200).joinToString("|") { "${it.resourceId}:${it.text}:${it.contentDesc}" }.hashCode()

    private fun probeFor(type: StepType, sel: Selector, slotValue: String?): (LiveTree) -> Pair<LiveTree, UiNode>? = { t ->
        SelectorMatcher.target(t.nodes, sel, slotValue, type == StepType.TAP)?.let { t to it }
    }

    /**
     * Passes a benign upsell/interstitial ("Continue", "Skip", "Got it", …) that Zomato can drop on top of the cart
     * after an item is added, hiding the quantity stepper and the address header. Only taps a control ScreenGuard
     * clears, so it can never advance a purchase (a real "Continue to pay" is handed over instead). Returns true if it
     * tapped something and the screen changed.
     */
    private suspend fun passInterstitial(allowed: Regex = INTERSTITIAL): Boolean {
        val t = device.tree()
        val node = t.nodes.firstOrNull { n ->
            n.clickable && allowed.matches((n.text.ifBlank { n.contentDesc }).trim()) && ScreenGuard.action(t.nodes, n) == null
        } ?: return false
        val before = signature(t)
        device.tap(t, node)
        return poll(cfg.settleMs) { tt -> tt.takeIf { signature(it) != before } } != null
    }

    /** Taps "+" / "−" beside the item until the count reads [want]; each tap is re-read, never assumed. */
    private suspend fun quantity(i: Int, step: FlowStep, slots: Map<String, String>, want: String): StepResult {
        val n = want.toIntOrNull()?.takeIf { it in 1..MAX_QTY } ?: return StepResult(i, false, error = "quantity \"$want\" is not a number I can set")
        val near = resolveSlots(step.selector.near, slots)
        val item = SLOT_TOKEN.find(step.selector.near)?.groupValues?.get(1)?.let { slots[it] }
        var last = -1
        var upsells = 0
        // Tree and stepper from the SAME read: a node is only tappable through the tree it was found in.
        suspend fun findStepper() = poll(cfg.waitMs) { tt -> Steppers.find(tt.nodes, near, item)?.let { tt to it } }
        repeat(MAX_QTY + 2) {
            val (t, s) = findStepper()
                ?: (if (upsells < 2 && passInterstitial()) { upsells++; findStepper() } else null)
                ?: return StepResult(i, false, error = "couldn't find the quantity control for ${item ?: "the item"}")
            if (s.count == n) return StepResult(i, true)
            if (s.count == last) return StepResult(i, false, error = "the quantity stayed at ${s.count}")
            if (s.count > n && s.count == 1) return StepResult(i, false, error = "the quantity can't go below 1")
            val button = if (s.count < n) s.plus else s.minus
            ScreenGuard.action(t.nodes, button)?.let { return handover(i, it) }
            last = s.count
            device.tap(t, button)
            poll(cfg.waitMs) { tt -> Steppers.find(tt.nodes, near, item)?.takeIf { it.count != last } }
        }
        return StepResult(i, false, error = "couldn't set the quantity to $n")
    }

    /** Opens the delivery-address picker and chooses the saved address labelled [label]; verified on the header. */
    private suspend fun address(i: Int, label: String): StepResult {
        val chosen = Regex("\\bto\\s+${Regex.escape(label)}\\b", RegexOption.IGNORE_CASE)
        val isChosen = { t: LiveTree -> t.nodes.any { !it.editable && chosen.containsMatchIn(it.text + " " + it.contentDesc) } }
        if (isChosen(device.tree())) return StepResult(i, true)

        suspend fun findOpener() = poll(cfg.waitMs) { t -> t.nodes.firstOrNull { ADDRESS_HEADER.containsMatchIn(it.text + " " + it.contentDesc) }?.let { t to it } }
        val opener = findOpener()
            ?: (if (passInterstitial()) findOpener() else null)
            ?: return StepResult(i, false, error = "couldn't find where to change the delivery address")
        device.tap(opener.first, opener.second)

        val entry = { t: LiveTree -> t.nodes.firstOrNull { !it.editable && (it.text.trim().equals(label, true) || it.contentDesc.trim().equals(label, true)) }?.let { t to it } }
        var found = poll(cfg.waitMs, entry)
        repeat(cfg.scrollTries) { if (found == null && device.scroll(device.tree())) found = poll(cfg.retryMs, entry) }
        val (tree, node) = found ?: run {
            device.back() // close the picker again rather than leave the user in it
            return StepResult(i, false, error = "there's no saved address called \"$label\"")
        }
        device.tap(tree, node)
        if (waitFor(isChosen)) return StepResult(i, true)
        if (entry(device.tree()) != null) device.back() // still in the picker: close it rather than leave the user there
        return StepResult(i, false, error = "the app didn't switch to your \"$label\" address, it may be outside the delivery area")
    }

    /** Anything that could mean the screen is asking the user to make a cart decision: dismissing it blindly would make it for them. */
    private fun blocksAutoDismissal(nodes: List<UiNode>) = CartState.replacePrompt(nodes) != null ||
        nodes.any { !it.editable && !it.password && CartState.DESTRUCTIVE.containsMatchIn(it.text + " " + it.contentDesc) && it.clickable }

    /** Why this step is stuck, and the specific question that says so: ambiguity is told apart from absence. */
    private fun stuckResult(i: Int, step: FlowStep, sel: Selector, what: String): StepResult {
        val nodes = device.tree().nodes
        val pkg = nodes.firstOrNull()?.packageName.orEmpty()
        val wrongApp = appPackage.isNotEmpty() && pkg.isNotEmpty() && pkg != appPackage
        val many = if (sel.near.isEmpty()) SelectorMatcher.findAll(nodes, sel).count { !it.editable } else 0
        val cause = when {
            CartState.replacePrompt(nodes) != null -> StuckCause.REPLACE_PROMPT
            wrongApp -> StuckCause.WRONG_APP
            sel.near.isNotEmpty() -> StuckCause.SLOT_ABSENT
            many > 1 -> StuckCause.AMBIGUOUS
            step.slotRef != null -> StuckCause.SLOT_ABSENT
            else -> StuckCause.MISSING_TARGET
        }
        val error = if (cause == StuckCause.AMBIGUOUS)
            "I can see $many different controls that could be $what, and nothing tells me which one you mean. This screen shows ${ScreenSummary.of(nodes)}. Which should I tap?"
        else stuck(what)
        return StepResult(i, false, error = error, cause = cause)
    }

    /**
     * The user is asked, answers, and the answer is acted on in this same run. A tap they name must be on screen,
     * must pass the payment guard, and is refused outright if it would throw their cart away. Four questions per run
     * at most, and silence ends the run: it never waits forever and never keeps guessing.
     */
    private suspend fun resolveByAsking(
        i: Int, step: FlowStep, next: FlowStep?, slots: Map<String, String>,
        first: StepResult, done: List<StepResult>, total: Int,
    ): StepResult {
        var r = first
        var note = ""
        while (asked < MAX_QUESTIONS) {
            val cause = r.cause ?: return r
            asked++
            val reply = asker!!.ask(Stuck(i, cause, spoken(r, cause, done, total, note)))
                ?: return r.copy(error = "I didn't hear back, so I stopped", cause = null)
            note = ""
            when (val a = AnswerParser.parse(reply)) {
                Answer.Stop -> return r.copy(error = "you asked me to stop", cause = null)
                Answer.Unclear -> note = "Sorry, I didn't understand that."
                Answer.Skip -> {
                    if (step.slotRef != null || step.type != StepType.TAP && step.type != StepType.TYPE) {
                        note = "I can't skip that one, it's the part you asked for."
                    } else {
                        record(i, step, cause, a, null, "")
                        return StepResult(i, true, error = "skipped at your request")
                    }
                }
                Answer.Retry -> {
                    r = if (cause == StuckCause.CART_CONFLICT) addAnother(i, step, slots) else runStep(i, step, next, slots)
                    if (r.ok || r.cause == null) return r
                }
                is Answer.Tap -> {
                    val located = locateNamed(a.caption)
                    val tree = located?.first ?: device.tree()
                    val node = located?.second
                    val caption = node?.let { it.text.ifBlank { it.contentDesc } }.orEmpty()
                    when {
                        node == null -> note = "I can't see \"${a.caption}\" on this screen."
                        CartState.DESTRUCTIVE.containsMatchIn("${node.text} ${node.contentDesc}") ->
                            note = "I won't tap \"$caption\" myself, it could throw your cart away."
                        else -> {
                            ScreenGuard.action(tree.nodes, node)?.let { return handover(i, it) }
                            val sel = portable(TapTarget.selector(tree.nodes, node))
                            val before = signature(tree)
                            if (!device.tap(tree, node)) {
                                note = "I couldn't tap \"$caption\"."
                            } else {
                                poll(cfg.settleMs) { t -> t.takeIf { signature(it) != before } }
                                // The tap may simply have cleared the way: if the taught control is there now, run the
                                // step as taught. Only a plain tap may be REPLACED by the user's tap — a typing step
                                // that is still missing its field is still not done.
                                val taughtIsBack = probeFor(step.type, resolved(step.selector, slots), step.slotRef?.let { slots[it] })(device.tree()) != null
                                if (taughtIsBack || step.type != StepType.TAP) {
                                    val again = runStep(i, step, next, slots)
                                    if (again.ok) record(i, step, cause, a, null, caption, clearedTheWay = true)
                                    if (again.ok || again.cause == null) return again
                                    r = again
                                } else {
                                    record(i, step, cause, a, sel, caption)
                                    return StepResult(i, true, error = "done as you told me: tapped \"$caption\"")
                                }
                            }
                        }
                    }
                }
            }
        }
        return r.copy(error = "${r.error} I've asked as much as I'm going to for one run, so I've stopped.", cause = null)
    }

    /**
     * The flow ran out of steps short of the cart: the demonstration stopped early. Ask how to get there and, when the
     * user names a tap that does, remember that THIS flow is missing it. Returns the checkout boundary reached, or null.
     */
    private suspend fun askToReachCart(flow: Flow, slots: Map<String, String>, done: List<StepResult>): Boundary? {
        if (asker == null) return null
        var note = ""
        val insertAt = flow.steps.indexOfFirst { it.type == StepType.SET_QUANTITY || it.type == StepType.CHOOSE_ADDRESS }.let { if (it < 0) flow.steps.size else it }
        while (asked < MAX_QUESTIONS) {
            asked++
            val nodes = device.tree().nodes
            val spoken = listOf(
                note, "I've done all ${flow.steps.size} steps, but I'm not on the cart or checkout screen.",
                "This screen shows ${ScreenSummary.of(nodes)}.", "What should I tap to get to your cart? Or say stop.",
            ).filter { it.isNotBlank() }.joinToString(" ")
            val reply = asker.ask(Stuck(flow.steps.size, StuckCause.MISSING_TARGET, spoken)) ?: return null
            note = ""
            when (val a = AnswerParser.parse(reply)) {
                Answer.Stop, Answer.Skip -> return null
                Answer.Unclear -> note = "Sorry, I didn't understand that."
                Answer.Retry -> reachedCheckout()?.let { return it } // they took me there themselves
                is Answer.Tap -> {
                    val located = locateNamed(a.caption)
                    val tree = located?.first ?: device.tree()
                    val node = located?.second
                    val caption = node?.let { it.text.ifBlank { it.contentDesc } }.orEmpty()
                    when {
                        node == null -> note = "I can't see \"${a.caption}\" on this screen."
                        CartState.DESTRUCTIVE.containsMatchIn("${node.text} ${node.contentDesc}") -> note = "I won't tap \"$caption\" myself, it could throw your cart away."
                        else -> {
                            ScreenGuard.action(tree.nodes, node)?.let { return it }
                            val sel = portable(TapTarget.selector(tree.nodes, node))
                            val before = signature(tree)
                            device.tap(tree, node)
                            poll(cfg.settleMs) { t -> t.takeIf { signature(it) != before } }
                            val at = reachedCheckout()
                            if (at != null) {
                                corrections += Correction(insertAt, CorrectionKind.FLOW_DEFECT, CorrectionOp.INSERT_TAP, "the end of the flow", "tap \"$caption\"", sel)
                                return at
                            }
                            note = "I tapped \"$caption\" but I'm still not on the cart."
                        }
                    }
                }
            }
        }
        return null
    }

    private suspend fun reachedCheckout(): Boundary? = poll(cfg.waitMs) { t -> ScreenGuard.screen(t.nodes) ?: ScreenGuard.readyToPay(t.nodes) }

    /** "Add another": the row shows a stepper where ADD was, so the answer is one tap on its plus. Asked for, so it is allowed. */
    private fun addAnother(i: Int, step: FlowStep, slots: Map<String, String>): StepResult {
        val near = resolveSlots(step.selector.near, slots)
        val item = SLOT_TOKEN.find(step.selector.near)?.groupValues?.get(1)?.let { slots[it] }
        val tree = device.tree()
        val plus = Steppers.find(tree.nodes, near, item)?.plus
            ?: return StepResult(i, false, error = "I can't find the + for ${item ?: "it"} any more. Should I leave it as it is?", cause = StuckCause.CART_CONFLICT)
        ScreenGuard.action(tree.nodes, plus)?.let { return handover(i, it) }
        return if (device.tap(tree, plus)) StepResult(i, true, error = "added another at your request")
        else StepResult(i, false, error = "I couldn't add another.", cause = StuckCause.CART_CONFLICT)
    }

    /**
     * What the user named, wherever it is: on this screen, or a few scrolls down. Scrolling only reveals — the control is
     * still found by its caption and still goes through the same guards before anything is tapped. A tree and node from
     * the SAME read are returned, since a node is only tappable through the tree it came from.
     */
    private suspend fun locateNamed(caption: String): Pair<LiveTree, UiNode>? {
        var tree = device.tree()
        answerTarget(tree.nodes, caption)?.let { return tree to it }
        repeat(cfg.scrollTries) {
            if (!device.scroll(tree)) return null
            poll(cfg.retryMs) { t -> answerTarget(t.nodes, caption)?.let { t to it } }?.let { return it }
            tree = device.tree()
        }
        return null
    }

    /** What the user named, as a control that can really be tapped here: exact caption first, then a unique partial one. */
    private fun answerTarget(nodes: List<UiNode>, caption: String): UiNode? {
        SelectorMatcher.target(nodes, Selector(text = caption, contentDesc = caption), null, forTap = true)?.let { return it }
        val want = RowContext.norm(caption)
        if (want.length < 3) return null
        val hits = SelectorMatcher.tappable(nodes).filter { n ->
            !n.password && listOf(n.text, n.contentDesc).any { c -> RowContext.norm(c).let { it.isNotEmpty() && want in it } }
        }
        return hits.singleOrNull()
    }

    /** A selector learned from an answer prefers its caption to a resource id: ids like button1 are layout accidents. */
    private fun portable(sel: Selector) = if (sel.text.isNotBlank() || sel.contentDesc.isNotBlank()) sel.copy(resourceId = "") else sel

    /**
     * [clearedTheWay]: the user's tap only revealed the control the flow was taught to use, so the taught step still
     * works and there is nothing to correct — whatever the cause, that is situational.
     */
    private fun record(i: Int, step: FlowStep, cause: StuckCause, a: Answer, sel: Selector?, caption: String, clearedTheWay: Boolean = false) {
        val kind = if (clearedTheWay) CorrectionKind.SITUATIONAL else CorrectionPolicy.kind(step, cause, a)
        corrections += Correction(
            i, kind, CorrectionPolicy.op(kind, a), was = labelOf(step),
            now = if (a is Answer.Skip) "skip it" else "tap \"$caption\"", selector = sel,
        )
    }

    private fun labelOf(step: FlowStep) =
        "\"" + step.selector.text.ifBlank { step.selector.contentDesc }.ifBlank { step.selector.resourceId.substringAfterLast('/') }.ifBlank { step.type.name.lowercase() } + "\""

    /** The whole question, said aloud: where the run got to, what is missing, and the ways to answer. */
    private fun spoken(r: StepResult, cause: StuckCause, done: List<StepResult>, total: Int, note: String): String {
        val progress = "I've got through ${done.count { it.ok }} of $total steps."
        val how = when (cause) {
            StuckCause.CART_CONFLICT -> "Say add another, or leave it."
            StuckCause.REPLACE_PROMPT -> "Sort your cart out yourself and say done, or say stop."
            StuckCause.WRONG_APP -> "Take me back to the app and say done, or say stop."
            else -> "Tell me what to tap, say skip to leave this step out, or say stop."
        }
        return listOf(note, progress, r.error.orEmpty(), how).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** The row we were sent to has a quantity stepper instead of the control: the item is already in the cart (T7). */
    private fun alreadyInCart(step: FlowStep, slots: Map<String, String>): String? {
        val near = resolveSlots(step.selector.near, slots)
        val item = SLOT_TOKEN.find(step.selector.near)?.groupValues?.get(1)?.let { slots[it] }
        val stepper = Steppers.find(device.tree().nodes, near, item) ?: return null
        return "\"${item ?: near}\" is already in your cart, ${stepper.count} of it. Should I add another one, or leave it as it is?"
    }

    /**
     * A guess is only acted on when it looks like the step's own control (shares a word with it) or is a plain way out
     * of a popup. Otherwise a wrong pick opens some unrelated screen and the rest of the run "succeeds" against it.
     */
    private fun plausible(step: FlowStep, sel: Selector, node: UiNode): Boolean {
        val caption = listOf(node.text, node.contentDesc).map { it.trim() }
        if (caption.any { DISMISS.matches(it) }) return true
        val want = words("${sel.text} ${sel.contentDesc} ${step.value}")
        return words(caption.joinToString(" ")).any { it in want }
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()

    private fun targetLabel(step: FlowStep, sel: Selector): String =
        (sel.text.ifBlank { sel.contentDesc }.ifBlank { step.selector.resourceId.substringAfterLast('/') }).let { if (it.isBlank()) "the next control" else "\"$it\"" }

    /**
     * Nothing to tap and nothing safe to guess: say what is missing and what the screen shows, as a question. A screen
     * of another app means the run was thrown out of the app (or never got in), which is worth saying differently.
     */
    private fun stuck(what: String): String {
        val nodes = device.tree().nodes
        val pkg = nodes.firstOrNull()?.packageName.orEmpty()
        CartState.replacePrompt(nodes)?.let {
            return "the app is asking \"$it\". Continuing would replace what is already in your cart, so I won't. Should I go ahead, or would you like to sort your cart out first?"
        }
        if (appPackage.isNotEmpty() && pkg.isNotEmpty() && pkg != appPackage) {
            return "I'm not in the app any more, this screen belongs to ${pkg.substringAfterLast('.')}. Can you take me back to it so I can continue?"
        }
        CartState.itemsInCart(nodes)?.let { n ->
            return "I can't find $what, and your cart already has $n item${if (n == 1) "" else "s"}, which may be in the way. Should I add to it anyway, or would you like to clear it first?"
        }
        return "I can't find $what. This screen shows ${ScreenSummary.of(nodes)}. What should I do?"
    }

    private fun resolved(sel: Selector, slots: Map<String, String>) = sel.copy(
        // Selector text may hold slot tokens ("{{query}} near me"); resolved against the CURRENT slot values.
        text = resolveSlots(sel.text, slots),
        contentDesc = resolveSlots(sel.contentDesc, slots),
        near = resolveSlots(sel.near, slots),
    )

    private fun handover(i: Int, b: Boundary) = StepResult(i, false, error = "handed over: ${b.reason}", handover = b)

    /**
     * Post-action check: `expect` (package / text) must appear within the wait window. `textContains` may hold a slot
     * token ("{{item}}"), so it is resolved against the CURRENT slots — the check is "did the screen we landed on name
     * what we actually asked for", which catches a tap that opened the wrong restaurant or dish.
     */
    private suspend fun finish(i: Int, step: FlowStep, acted: Boolean, fallback: Boolean = false, slots: Map<String, String> = emptyMap()): StepResult {
        if (!acted) return StepResult(i, false, fallback, "action failed")
        val e = step.expect
        val want = resolveSlots(e.textContains, slots)
        if (e.packageName.isEmpty() && want.isEmpty()) return StepResult(i, true, fallback)
        val ok = waitFor { t ->
            (e.packageName.isEmpty() || t.nodes.firstOrNull()?.packageName == e.packageName) && names(t.nodes, want)
        }
        return if (ok) StepResult(i, true, fallback) else StepResult(i, false, fallback, "expected screen not reached")
    }

    /** Does any caption carry [want]? Punctuation- and spacing-insensitive, so "Corn & Cheese" matches "corn and cheese". */
    private fun names(nodes: List<UiNode>, want: String): Boolean {
        if (want.isEmpty()) return true
        val w = RowContext.norm(want)
        return nodes.any { n ->
            !n.password && (n.text.contains(want, true) || n.contentDesc.contains(want, true) ||
                (w.isNotEmpty() && (RowContext.norm(n.text).contains(w) || RowContext.norm(n.contentDesc).contains(w))))
        }
    }

    private suspend fun <T : Any> poll(timeoutMs: Long, probe: (LiveTree) -> T?): T? {
        var waited = 0L
        while (true) {
            probe(device.tree())?.let { return it }
            if (waited >= timeoutMs) return null
            delay(cfg.pollMs); waited += cfg.pollMs
        }
    }

    private suspend fun waitFor(cond: (LiveTree) -> Boolean): Boolean =
        poll(cfg.waitMs) { t -> if (cond(t)) true else null } ?: false

    private companion object {
        const val MAX_QTY = 20
        const val MAX_LLM_PICKS = 2
        const val MAX_QUESTIONS = 4
        const val MAX_DISMISSALS = 2
        const val SCROLL_SETTLE_MS = 1200L
        // What a blind pre-step may tap: controls that only CLOSE something. No Continue/Proceed (they advance the
        // flow, which once opened the cart behind a step's back) and no OK (it confirms as often as it informs).
        val DISMISSAL = Regex("^(close|skip|not now|maybe later|no thanks|got it|dismiss|later|[×✕x])$", RegexOption.IGNORE_CASE)
        val DISMISS = Regex("^(close|skip|not now|maybe later|no thanks|got it|ok|okay|allow|cancel|dismiss|later|[×✕x])$", RegexOption.IGNORE_CASE)
        // Benign pass-through controls an upsell/interstitial can sit behind, on top of the cart ("Add items worth ₹X…
        // Continue"). Only ever tapped when ScreenGuard clears it, so a genuine "Continue to pay" is still handed over.
        val INTERSTITIAL = Regex("^(continue|proceed|close|skip|not now|maybe later|no thanks|got it|ok|okay|dismiss|later|[×✕x])$", RegexOption.IGNORE_CASE)
        const val TYPE_TRIES = 3
        const val QUIET_MS = 600L
        val SLOT_TOKEN = Regex("\\{\\{(\\w+)\\}\\}")
        // "25-30 mins to Home", "Deliver to Work", "Delivering to …": the control that opens the address picker.
        val ADDRESS_HEADER = Regex("\\b(deliver(ing|y)?\\s+to|mins?\\s+to)\\s+\\S", RegexOption.IGNORE_CASE)
    }
}
