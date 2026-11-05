package org.isoron.uhabits.core.containers

/** Task-navigation state only; it never changes the organization tree or its contents. */
class TaskContainerVisibility(
    private val parents: Map<String, String?>,
    private val hidden: Set<String>,
) {
    /** Unknown nodes and invalid parent cycles are not navigable. */
    fun isVisible(id: String): Boolean {
        val path = path(id) ?: return false
        return path.none { it in hidden }
    }

    /** Hidden nodes on the path, beginning with the requested node. */
    fun hiddenAncestors(id: String): List<String> =
        path(id)?.filter { it in hidden }.orEmpty()

    /** Returns new explicit state; unrelated branches and descendants are untouched. */
    fun showPath(id: String): Set<String> {
        val path = path(id) ?: return hidden
        return hidden - path.toSet()
    }

    fun nearestVisibleAncestor(id: String): String? =
        path(id)?.drop(1)?.firstOrNull { isVisible(it) }

    private fun path(id: String): List<String>? {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var current: String? = id
        while (current != null) {
            if (!seen.add(current) || !parents.containsKey(current)) return null
            result.add(current)
            current = parents[current]
        }
        return result
    }
}
