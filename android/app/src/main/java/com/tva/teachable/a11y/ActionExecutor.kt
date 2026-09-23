package com.tva.teachable.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.tva.teachable.core.UiNode
import com.tva.teachable.core.area

/** Performs actions on a node found in a [LiveTree]. Matching lives in SelectorMatcher — not duplicated here. */
class ActionExecutor(private val service: AccessibilityService) {

    fun tap(tree: LiveTree, node: UiNode): Boolean {
        var n: AccessibilityNodeInfo = tree.handle(node)
        while (!n.isClickable) n = n.parent ?: return false // nearest clickable ancestor
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun type(tree: LiveTree, node: UiNode, value: String): Boolean {
        val n = tree.handle(node)
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(tree: LiveTree, forward: Boolean = true): Boolean {
        // The biggest scrollable is the page itself; the first one is often a horizontal strip of filter chips.
        val target = tree.nodes.filter { it.scrollable }.maxByOrNull { it.area } ?: return false
        return tree.handle(target).performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    fun back(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
}
