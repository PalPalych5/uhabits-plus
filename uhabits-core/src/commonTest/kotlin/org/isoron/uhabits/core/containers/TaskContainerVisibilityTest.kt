package org.isoron.uhabits.core.containers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskContainerVisibilityTest {
    private val parents = mapOf("root" to null, "child" to "root", "sibling" to "root", "leaf" to "child")

    @Test
    fun `restoring parent preserves individually hidden children`() {
        val hidden = setOf("root", "child")
        val policy = TaskContainerVisibility(parents, hidden)
        assertFalse(policy.isVisible("leaf"))
        assertEquals(listOf("child", "root"), policy.hiddenAncestors("leaf"))
        val restored = TaskContainerVisibility(parents, hidden - "root")
        assertTrue(restored.isVisible("sibling"))
        assertFalse(restored.isVisible("child"))
        assertFalse(restored.isVisible("leaf"))
    }

    @Test
    fun `show path opens only required nodes`() {
        val policy = TaskContainerVisibility(parents, setOf("root", "child", "sibling"))
        assertEquals(setOf("sibling"), policy.showPath("leaf"))
        assertNull(policy.nearestVisibleAncestor("leaf"))
        assertEquals("root", TaskContainerVisibility(parents, setOf("child")).nearestVisibleAncestor("leaf"))
    }

    @Test
    fun `missing and cyclic nodes fail closed without modifying state`() {
        val hidden = setOf("a")
        val policy = TaskContainerVisibility(mapOf("a" to "b", "b" to "a"), hidden)
        assertFalse(policy.isVisible("a"))
        assertFalse(policy.isVisible("missing"))
        assertEquals(hidden, policy.showPath("a"))
        assertNull(policy.nearestVisibleAncestor("a"))
    }
}
