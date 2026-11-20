/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertTrue

internal fun UiDevice.statisticsControl(tag: String): UiObject2 {
    val selector = By.res(tag)
    for (attempt in 1..5) {
        val obj = wait(Until.findObject(selector), 5_000)
            ?: error("Missing statistics control $tag")
        try {
            if (!obj.visibleBounds.isEmpty) return obj
        } catch (_: StaleObjectException) {
            // Recompose occurred, query fresh object
        }
    }
    return requireNotNull(wait(Until.findObject(selector), 5_000)) { "Missing statistics control $tag" }
}

internal fun UiDevice.revealStatisticsReportControl(tag: String, direction: Direction = Direction.DOWN): UiObject2 =
    revealStatisticsControl(By.res(tag), "unified_content", direction)

internal fun UiDevice.scrollStatisticsReportToEdge(direction: Direction) {
    for (attempt in 1..16) {
        if (!statisticsControl("unified_content").scroll(direction, 0.8f)) return
    }
    error("The statistics report did not reach its $direction edge")
}

internal fun UiObject2.statisticsTexts(): List<String> = buildList {
    text?.takeIf { it.isNotBlank() }?.let { add(it) }
    children.forEach { addAll(it.statisticsTexts()) }
}

internal fun UiDevice.assertStatisticsReportTextBounds() {
    val viewport = statisticsControl("unified_content")
    val bounds = viewport.visibleBounds
    val textNodes = viewport.findObjects(By.text(Pattern.compile("(?s).*\\S.*"))).filterNot { it.visibleBounds.isEmpty }
    assertTrue("Statistics report must expose visible text", textNodes.isNotEmpty())
    for (node in textNodes) {
        assertTrue("Statistics text must remain inside the report viewport: ${node.text}", bounds.contains(node.visibleBounds))
    }
}

internal fun UiDevice.findStatisticsSessionDetailText(id: String, selector: BySelector): Boolean {
    val root = { statisticsControl("session-detail:$id") }
    for (direction in listOf(Direction.DOWN, Direction.UP)) {
        for (attempt in 1..12) {
            val detail = root()
            if (detail.hasObject(selector)) return true
            val scroll = if (detail.isScrollable) detail else detail.findObject(By.scrollable(true))
            if (scroll == null || !scroll.scroll(direction, 0.65f)) break
        }
    }
    return root().hasObject(selector)
}

internal fun UiDevice.revealStatisticsControl(
    selector: BySelector,
    scrollTag: String,
    direction: Direction,
): UiObject2 {
    val reverse = when (direction) {
        Direction.DOWN -> Direction.UP
        Direction.UP -> Direction.DOWN
        Direction.RIGHT -> Direction.LEFT
        Direction.LEFT -> Direction.RIGHT
    }
    for (scrollDirection in listOf(direction, reverse)) {
        for (attempt in 1..12) {
            findObject(selector)?.takeUnless { it.visibleBounds.isEmpty }?.let { return it }
            if (!statisticsControl(scrollTag).scroll(scrollDirection, 0.65f)) break
        }
    }
    return requireNotNull(findObject(selector)?.takeUnless { it.visibleBounds.isEmpty }) {
        "The scroll area $scrollTag did not reveal $selector"
    }
}

internal fun UiDevice.selectStatisticsTab(index: Int) {
    val tag = "unified_tab_${UnifiedAnalyticsTab.entries[index].name}"
    for (attempt in 1..5) {
        try {
            val target = revealStatisticsControl(By.res(tag), "unified_tabs", Direction.RIGHT)
            val bounds = target.visibleBounds
            if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
                click(bounds.centerX(), bounds.centerY())
            } else {
                target.click()
            }
            if (wait(Until.hasObject(By.res(tag).selected(true)), 3_000)) return
        } catch (_: StaleObjectException) {
            // Recompose occurred during layout/event dispatch
        }
    }
    assertTrue(wait(Until.hasObject(By.res(tag).selected(true)), 2_000))
}

internal fun UiDevice.selectStatisticsPeriod(period: UnifiedPeriod) {
    statisticsControl("unified_period").click()
    statisticsControl("unified_period_${period.name}").click()
}

internal fun UiDevice.assertStatisticsControlSizes(density: Float, isAll: Boolean) {
    val navControls = listOf("unified_prev", "unified_next")
    val tags = listOf("unified_period", "unified_scope") + if (isAll) emptyList() else navControls
    for (tag in tags) {
        var bounds: android.graphics.Rect? = null
        for (attempt in 1..5) {
            try {
                bounds = statisticsControl(tag).visibleBounds
                break
            } catch (_: StaleObjectException) {
            }
        }
        val b = requireNotNull(bounds) { "Could not obtain bounds for $tag" }
        assertTrue("$tag width must be at least 48dp", b.width() / density >= 47.5f)
        assertTrue("$tag height must be at least 48dp", b.height() / density >= 47.5f)
    }
}


internal fun UiDevice.revealStatisticsChartColumn(chart: String, index: Int): UiObject2 {
    revealStatisticsReportControl(chart)
    return revealStatisticsControl(By.res("${chart}_column_$index"), "${chart}_scroll", Direction.LEFT)
}

internal fun UiDevice.revealStatisticsDrilldownControl(tag: String, direction: Direction = Direction.DOWN): UiObject2 {
    val alternate = if (direction == Direction.DOWN) Direction.UP else Direction.DOWN
    val selector = By.res(tag)
    fun visible() = statisticsControl("statistics_drilldown_list")
        .findObject(selector)?.takeUnless { it.visibleBounds.isEmpty }
    for (scrollDirection in listOf(direction, alternate)) {
        for (attempt in 1..28) {
            visible()?.let { return it }
            val list = statisticsControl("statistics_drilldown_list")
            if (!list.isScrollable || !list.scroll(scrollDirection, 0.72f)) break
        }
    }
    return requireNotNull(visible()) { "Drilldown did not expose $tag" }
}

internal fun UiDevice.assertStatisticsChart(chart: String, density: Float) {
    revealStatisticsReportControl(chart)
    val axis = statisticsControl("${chart}_axis")
    assertTrue("Chart must expose its numeric axis and unit", !axis.contentDescription.isNullOrBlank())
    assertTrue("Chart height must be at least 48dp", axis.visibleBounds.height() / density >= 47.5f)
    val column = revealStatisticsControl(
        By.res(Pattern.compile(Pattern.quote(chart) + "_column_\\d+")), "${chart}_scroll", Direction.LEFT,
    )
    assertTrue("Chart column must be clickable", column.isClickable)
    assertTrue("Chart column must have an accessibility label", !column.contentDescription.isNullOrBlank())
}
