/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlin.math.roundToInt

internal fun tasksComposeNodes(root: View, subtreeTag: String? = null): List<Pair<SemanticsNode, View>> = buildList {
    fun walkSemantics(node: SemanticsNode, host: View, insideSubtree: Boolean) {
        val include = insideSubtree || node.config.getOrNull(SemanticsProperties.TestTag) == subtreeTag
        if (include) add(node to host)
        node.children.forEach { walkSemantics(it, host, include) }
    }

    fun walkViews(view: View) {
        if (view is RootForTest) {
            walkSemantics(view.semanticsOwner.unmergedRootSemanticsNode, view, subtreeTag == null)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) walkViews(view.getChildAt(index))
        }
    }

    walkViews(root)
}

internal fun taskWorkspaceBounds(root: View, tag: String): Rect {
    val (node, host) = checkNotNull(tasksComposeNodes(root, subtreeTag = tag).firstOrNull()) {
        "Missing Tasks workspace node: $tag"
    }
    val bounds = node.boundsInRoot
    val origin = IntArray(2)
    host.getLocationOnScreen(origin)
    return Rect(
        (bounds.left + origin[0]).roundToInt(),
        (bounds.top + origin[1]).roundToInt(),
        (bounds.right + origin[0]).roundToInt(),
        (bounds.bottom + origin[1]).roundToInt(),
    )
}
