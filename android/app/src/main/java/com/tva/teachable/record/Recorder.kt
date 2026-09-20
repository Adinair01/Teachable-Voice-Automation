package com.tva.teachable.record

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.tva.teachable.core.Expect
import com.tva.teachable.core.FlowStep
import com.tva.teachable.core.Selector
import com.tva.teachable.core.StepType

/**
 * Turns the user's own accessibility events into steps (selectors, never coordinates).
 * Typing is debounced into ONE type step per field. Password fields are never recorded.
 * Pure event->step logic; the service only forwards events.
 */
class Recorder(private val ownPackage: String, private val ignoredPackages: Set<String> = setOf("com.android.systemui")) {
    private val steps = ArrayList<FlowStep>()
    private var pendingType: FlowStep? = null
    private var launched = false

    fun start(foregroundPackage: String?) {
        steps.clear(); pendingType = null; launched = false
        foregroundPackage?.takeIf { it != ownPackage }?.let { steps += FlowStep(StepType.LAUNCH, value = it); launched = true }
    }

    fun stop(): List<FlowStep> { flushType(); return steps.toList() }

    fun onEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString().orEmpty()
        if (pkg == ownPackage || pkg in ignoredPackages) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onText(e)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onClick(e)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindow(pkg)
        }
    }

    private fun onText(e: AccessibilityEvent) {
        if (e.isPassword) return
        val src = e.source ?: return
        if (src.isPassword) return
        val text = e.text?.joinToString("") { it.toString() }.orEmpty()
        pendingType = FlowStep(StepType.TYPE, selectorOf(src), value = text)
    }

    private fun onClick(e: AccessibilityEvent) {
        val src = e.source ?: return
        if (src.isPassword) return
        flushType()
        steps += FlowStep(StepType.TAP, selectorOf(src))
    }

    /** The screen that appears after an action becomes that action's `expect` (what replay must verify). */
    private fun onWindow(pkg: String) {
        flushType()
        val last = steps.lastOrNull() ?: return
        if (last.type != StepType.LAUNCH && last.expect.packageName.isEmpty()) steps[steps.lastIndex] = last.copy(expect = Expect(packageName = pkg))
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
