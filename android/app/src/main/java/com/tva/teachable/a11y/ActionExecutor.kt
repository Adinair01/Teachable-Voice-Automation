package com.tva.teachable.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.tva.teachable.core.Selector

/** Performs click/type/scroll/back against live nodes located by Selector. Runs off the main thread's UI work. */
class ActionExecutor(private val service: AccessibilityService) {

    private fun findLive(sel: Selector): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        if (sel.resourceId.isNotEmpty()) root.findAccessibilityNodeInfosByViewId(sel.resourceId).firstOrNull()?.let { return it }
        if (sel.text.isNotEmpty()) root.findAccessibilityNodeInfosByText(sel.text)
            .firstOrNull { !it.isPassword && it.text?.toString().equals(sel.text, true) }?.let { return it }
        if (sel.contentDesc.isNotEmpty()) return search(root) { it.contentDescription?.toString().equals(sel.contentDesc, true) }
        if (sel.className.isNotEmpty()) return search(root) { it.className?.toString() == sel.className }
        return null
    }

    private fun search(n: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (pred(n)) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> search(c, pred)?.let { return it } }
        return null
    }

    fun tap(sel: Selector): Boolean {
        var n = findLive(sel) ?: return false
        while (!n.isClickable) n = n.parent ?: return false // tap the nearest clickable ancestor
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun type(sel: Selector, value: String): Boolean {
        val n = findLive(sel) ?: return false
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(forward: Boolean = true): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val target = search(root) { it.isScrollable } ?: return false
        return target.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    fun back(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
}
