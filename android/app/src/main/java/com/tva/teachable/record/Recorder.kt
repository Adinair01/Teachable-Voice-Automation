package com.tva.teachable.record

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.tva.teachable.BuildConfig
import android.view.accessibility.AccessibilityNodeInfo
import com.tva.teachable.core.Expect
import com.tva.teachable.a11y.UiTreeReader
import com.tva.teachable.core.FlowStep
import com.tva.teachable.core.RowContext
import com.tva.teachable.core.ScreenGuard
import com.tva.teachable.core.TapTarget
import com.tva.teachable.core.UiNode
import com.tva.teachable.core.Selector
import com.tva.teachable.core.StepType

/**
 * Turns the user's own accessibility events into steps (selectors, never coordinates).
 * Typing is debounced into ONE type step per field. Password fields are never recorded, and nothing at all is recorded
 * on a payment / OTP / login screen (ScreenGuard): an OTP is not a password field but must never be stored either.
 * Pure event->step logic; the service only forwards events.
 */
private val IME = Regex("inputmethod|\\.ime$|keyboard", RegexOption.IGNORE_CASE)
private const val MAX_CAPTIONS = 15

class Recorder(private val ownPackage: String, private val ignoredPackages: Set<String> = setOf("com.android.systemui")) {
    private val steps = ArrayList<FlowStep>()
    private var pendingType: FlowStep? = null
    private var launched = false
    /** Taps come from the TouchCatcher (onUserTap) instead of TYPE_VIEW_CLICKED, which would double-record them. */
    @Volatile var touchMode = false

    fun start() { steps.clear(); pendingType = null; launched = false }

    @Synchronized fun stop(): List<FlowStep> { flushType(); return steps.toList() }

    @Synchronized fun onEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString().orEmpty()
        if (BuildConfig.DEBUG) Log.d("TVA", "rec event ${AccessibilityEvent.eventTypeToString(e.eventType)} pkg=$pkg src=${e.source != null} text=${e.text}")
        if (pkg.isEmpty() || pkg == ownPackage || pkg in ignoredPackages || IME.containsMatchIn(pkg)) return
        // The demo's launch step is the first real app the user touches (launcher taps are ignored by design).
        if (!launched) { steps += FlowStep(StepType.LAUNCH, value = pkg); launched = true }
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val screen = UiTreeReader.readLive(e.source?.window?.root).nodes
                if (onBoundary(screen)) { pendingType = null; return }
                if (e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) onText(e) else if (!touchMode) onClick(e, screen)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindow(pkg, UiTreeReader.readLive(e.source?.window?.root).nodes)
        }
    }

    private fun onText(e: AccessibilityEvent) {
        if (e.isPassword) return
        val src = e.source ?: return
        if (src.isPassword) return
        val text = e.text?.joinToString("") { it.toString() }.orEmpty()
        pendingType = FlowStep(StepType.TYPE, selectorOf(src), value = text)
    }

    private fun onClick(e: AccessibilityEvent, screen: List<UiNode>) {
        val src = e.source
        if (src?.isPassword == true) return
        flushType()
        // A click that navigates away detaches the view before delivery (source == null): use the event's own data.
        val sel = src?.let { selectorOf(it) } ?: Selector(
            text = e.text?.joinToString(" ") { it.toString() }.orEmpty(),
            contentDesc = e.contentDescription?.toString().orEmpty(),
            className = e.className?.toString().orEmpty(),
        )
        val me = src?.let { UiTreeReader.toUiNode(it) }
        val node = me?.let { m -> screen.firstOrNull { it.bounds == m.bounds && it.resourceId == m.resourceId && it.className == m.className } }
        if (!sel.isEmpty) steps += FlowStep(
            StepType.TAP, sel,
            context = node?.let { rowContext(screen, it, sel) }.orEmpty(),
            candidates = node?.let { sameKindCount(screen, sel) } ?: 0,
        )
    }

    /** A finger tap seen by the TouchCatcher at (x, y); [screen] was read before the tap reached the app. */
    @Synchronized fun onUserTap(screen: List<UiNode>, x: Int, y: Int) {
        val pkg = screen.firstOrNull()?.packageName.orEmpty()
        if (pkg.isEmpty() || pkg == ownPackage || pkg in ignoredPackages || IME.containsMatchIn(pkg)) return
        if (onBoundary(screen)) { pendingType = null; return }
        if (!launched) { steps += FlowStep(StepType.LAUNCH, value = pkg); launched = true }
        val node = TapTarget.at(screen, x, y) ?: return
        flushType()
        if (node.editable) return // focusing a field: the typed text becomes the step
        val sel = TapTarget.selector(screen, node)
        if (!sel.isEmpty) steps += FlowStep(StepType.TAP, sel, context = rowContext(screen, node, sel), candidates = sameKindCount(screen, sel))
    }

    /** An upward swipe while teaching: replay scrolls the page forward at this point too. */
    @Synchronized fun onSwipeUp() {
        if (!launched) return
        flushType()
        if (steps.lastOrNull()?.type != StepType.SCROLL) steps += FlowStep(StepType.SCROLL)
    }

    private fun sameKind(sel: Selector): (UiNode) -> Boolean = { n ->
        if (sel.resourceId.isNotEmpty()) n.resourceId == sel.resourceId else sel.text.isNotEmpty() && n.text == sel.text
    }

    private fun sameKindCount(screen: List<UiNode>, sel: Selector) = screen.count(sameKind(sel))

    /**
     * Captions of the tapped element's row: the backend turns the caption that names a slot value into
     * selector.near, so replay taps the right row's ADD.
     *
     * Captured for EVERY tap, not only when the control already repeats on screen. A demonstration done straight
     * after a search shows one result and one ADD, and recording no row there produced a flow with no slot at all,
     * which then matched every row's ADD on a fuller menu. [FlowStep.candidates] carries how many there were, so
     * the backend can still tell "this control repeats" from "this control was alone".
     */
    private fun rowContext(screen: List<UiNode>, node: UiNode, sel: Selector): List<String> =
        RowContext.of(screen, node, sameKind(sel))

    /**
     * The screen that appears after an action becomes that action's `expect` (what replay must verify). Its captions
     * are recorded too: the backend keeps the one that names a slot value, so replay can check it really opened the
     * restaurant or dish that was asked for instead of only "still in the same app".
     */
    private fun onWindow(pkg: String, screen: List<UiNode>) {
        flushType()
        val last = steps.lastOrNull() ?: return
        if (last.type == StepType.TAP && last.expect.packageName.isEmpty()) {
            steps[steps.lastIndex] = last.copy(expect = Expect(packageName = pkg), screen = captions(screen))
        }
    }

    /** Short, wordy captions of a screen, deduplicated — enough for the backend to find a slot value among them. */
    private fun captions(screen: List<UiNode>): List<String> = screen.asSequence()
        .filter { !it.password && !it.editable }
        .flatMap { sequenceOf(it.text, it.contentDesc) }
        .map { it.trim() }
        .filter { it.length in 2..60 && it.any(Char::isLetter) }
        .distinct()
        .take(MAX_CAPTIONS)
        .toList()

    private fun onBoundary(screen: List<UiNode>): Boolean {
        val b = ScreenGuard.screen(screen) ?: return false
        Log.i("TVA", "recorder: not recording on a ${b.kind} screen (${b.reason})")
        return true
    }

    private fun flushType() { pendingType?.let { steps += it }; pendingType = null }

    /** Stable selector: id + text (own or first labelled descendant) + desc + class. */
    private fun selectorOf(n: AccessibilityNodeInfo): Selector = Selector(
        resourceId = n.viewIdResourceName.orEmpty(),
        text = if (n.isEditable) "" else (n.text?.toString() ?: firstText(n)).orEmpty(),
        contentDesc = n.contentDescription?.toString().orEmpty(),
        className = n.className?.toString().orEmpty(),
    )

    private fun firstText(n: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 4) return null
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (c.isPassword) continue
            c.text?.toString()?.takeIf { it.isNotEmpty() }?.let { return it }
            firstText(c, depth + 1)?.let { return it }
        }
        return null
    }
}
