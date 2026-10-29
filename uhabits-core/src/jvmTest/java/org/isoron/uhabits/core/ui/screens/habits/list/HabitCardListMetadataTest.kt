package org.isoron.uhabits.core.ui.screens.habits.list

import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.resetCalls
import dev.mokkery.verify
import dev.mokkery.verifyNoMoreCalls
import kotlinx.coroutines.runBlocking
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.containers.HabitVisualIdentity
import org.isoron.uhabits.core.containers.IconRef
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacade
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.models.memory.MemoryHabitList
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.core.tasks.TaskRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HabitCardListMetadataTest : BaseUnitTest() {
    private class QueuedRunner : TaskRunner {
        val tasks = mutableListOf<Task>()
        override val activeTaskCount get() = tasks.size
        override fun execute(task: Task) { tasks.add(task); task.onAttached(this) }
        override fun publishProgress(task: Task, progress: Int) {}
        override fun addListener(listener: TaskRunner.Listener) {}
        override fun removeListener(listener: TaskRunner.Listener) {}
        override suspend fun await() {}
        fun finish(task: Task = tasks.last()) { runBlocking { task.doInBackground() }; task.onPostExecute() }
    }

    @Test
    fun metadataReadsRunOnlyInWorkerAndPublishedNullsDoNotQueryAgain() {
        val habit = fixtures.createShortHabit().apply { blockId = 42 }
        habitList.add(habit)
        var iconReads = 0
        var groupReads = 0
        val facade = mock<HabitOrganizationFacade>()
        every { facade.mode } returns OrganizationAuthorityMode.CONTAINER_LOCAL
        every { facade.getHabitVisualIdentity(any()) } calls { iconReads++; null }
        every { facade.getRootGroupKey(any(), any()) } calls { groupReads++; null }
        (habitList as MemoryHabitList).organizationFacade = facade
        val runner = QueuedRunner()
        val cache = HabitCardListCache(habitList, commandRunner, runner, mock())
        cache.setCheckmarkCount(10)
        cache.refreshAllHabits()
        assertEquals(0, iconReads)
        assertEquals(0, groupReads)
        runner.finish()
        repeat(100) {
            assertNull(cache.getMetadata(habit.id!!).iconRef)
            assertNull(cache.getMetadata(habit.id!!).rootGroupKey, "Unassigned Next root must not fall back to legacy block 42")
        }
        assertEquals(1, iconReads)
        assertEquals(1, groupReads)
    }

    @Test
    fun metadataOnlyTargetedChangeNotifiesFullItemChangeAndKeepsOtherRows() {
        val first = fixtures.createShortHabit(); habitList.add(first)
        val second = fixtures.createShortHabit(); habitList.add(second)
        val identities = mutableMapOf<String, HabitVisualIdentity>()
        val roots = mutableMapOf<String, String>()
        val facade = mock<HabitOrganizationFacade>()
        every { facade.mode } returns OrganizationAuthorityMode.CONTAINER_LOCAL
        every { facade.getHabitVisualIdentity(first.uuid!!) } calls { identities[first.uuid!!] }
        every { facade.getHabitVisualIdentity(second.uuid!!) } calls { identities[second.uuid!!] }
        every { facade.getRootGroupKey(first.uuid!!, any()) } calls { roots[first.uuid!!] }
        every { facade.getRootGroupKey(second.uuid!!, any()) } calls { roots[second.uuid!!] }
        (habitList as MemoryHabitList).organizationFacade = facade
        val runner = QueuedRunner()
        val cache = HabitCardListCache(habitList, commandRunner, runner, mock())
        cache.setCheckmarkCount(10)
        cache.refreshAllHabits(); runner.finish()
        val unchanged = cache.getMetadata(second.id!!)
        val listener = mock<HabitCardListCache.Listener>()
        cache.setListener(listener)
        identities[first.uuid!!] = HabitVisualIdentity(first.uuid!!, IconRef.Emoji("x"))
        roots[first.uuid!!] = "new-root"
        cache.refreshHabit(first.id!!); runner.finish()
        assertEquals(IconRef.Emoji("x"), cache.getMetadata(first.id!!).iconRef)
        assertEquals("new-root", cache.getMetadata(first.id!!).rootGroupKey)
        assertEquals(unchanged, cache.getMetadata(second.id!!))
        verify { listener.onItemChanged(0) }
        verify { listener.onRefreshFinished() }
        verifyNoMoreCalls(listener)
        resetCalls(listener)
        identities.clear(); roots.clear()
        cache.refreshAllHabits(); runner.finish()
        assertNull(cache.getMetadata(first.id!!).iconRef)
        assertNull(cache.getMetadata(first.id!!).rootGroupKey)
        verify { listener.onItemChanged(0) }
    }

    @Test
    fun olderMetadataGenerationCannotOverwriteNewIconAndPlacement() {
        val habit = fixtures.createShortHabit(); habitList.add(habit)
        var icon: IconRef = IconRef.Vector("old")
        var root = "old-root"
        val facade = mock<HabitOrganizationFacade>()
        every { facade.mode } returns OrganizationAuthorityMode.CONTAINER_LOCAL
        every { facade.getHabitVisualIdentity(any()) } calls { HabitVisualIdentity(habit.uuid!!, icon) }
        every { facade.getRootGroupKey(any(), any()) } calls { root }
        (habitList as MemoryHabitList).organizationFacade = facade
        val runner = QueuedRunner()
        val cache = HabitCardListCache(habitList, commandRunner, runner, mock())
        cache.setCheckmarkCount(10)
        cache.refreshAllHabits(); runner.finish()
        cache.refreshAllHabits()
        val oldTask = runner.tasks.last()
        runBlocking { oldTask.doInBackground() }
        icon = IconRef.Vector("new"); root = "new-root"
        cache.refreshAllHabits(); runner.finish()
        oldTask.onPostExecute()
        assertEquals(icon, cache.getMetadata(habit.id!!).iconRef)
        assertEquals(root, cache.getMetadata(habit.id!!).rootGroupKey)
    }

    @Test
    fun habitListOrganizationFacadeIsResolvedFromHabitListDirectly() {
        val habit = fixtures.createShortHabit().apply { blockId = 99 }
        val customList = object : MemoryHabitList() {}
        customList.add(habit)
        val facade = mock<HabitOrganizationFacade>()
        every { facade.mode } returns OrganizationAuthorityMode.CONTAINER_LOCAL
        every { facade.getHabitVisualIdentity(any()) } returns null
        every { facade.getRootGroupKey(habit.uuid!!, 99L) } returns "root-container-xyz"
        customList.organizationFacade = facade
        val runner = QueuedRunner()
        val cache = HabitCardListCache(customList, commandRunner, runner, mock())
        cache.setCheckmarkCount(10)
        cache.refreshAllHabits(); runner.finish()
        assertEquals("root-container-xyz", cache.getMetadata(habit.id!!).rootGroupKey)
    }

    @Test
    fun sqliteWrapperSuppliesOrganizationMetadataDuringFullAndTargetedRefresh() = runBlocking {
        val db = buildMemoryDatabase()
        try {
            val factory = SQLModelFactory(db)
            val list = SQLiteHabitList(factory)
            val first = factory.buildHabit().apply { name = "First" }
            val second = factory.buildHabit().apply { name = "Second" }
            list.add(first)
            list.add(second)
            val facade = mock<HabitOrganizationFacade>()
            every { facade.mode } returns OrganizationAuthorityMode.CONTAINER_LOCAL
            every { facade.getHabitVisualIdentity(any()) } returns null
            every { facade.getRootGroupKey(any(), any()) } returns "shared-root"
            list.organizationFacade = facade
            val runner = QueuedRunner()
            val cache = HabitCardListCache(list, commandRunner, runner, mock())
            cache.setCheckmarkCount(10)
            cache.refreshAllHabits(); runner.finish()
            assertEquals("shared-root", cache.getMetadata(first.id!!).rootGroupKey)
            assertEquals("shared-root", cache.getMetadata(second.id!!).rootGroupKey)
            cache.refreshHabit(first.id!!); runner.finish()
            assertEquals("shared-root", cache.getMetadata(first.id!!).rootGroupKey)
            assertEquals("shared-root", cache.getMetadata(second.id!!).rootGroupKey)
        } finally { db.close() }
    }

    @Test
    fun targetedRefreshPreservesMetadataForOtherHabits() {
        val first = fixtures.createShortHabit(); habitList.add(first)
        val second = fixtures.createShortHabit(); habitList.add(second)
        val roots = mutableMapOf(first.uuid!! to "root-1", second.uuid!! to "root-2")
        val facade = mock<HabitOrganizationFacade>()
        every { facade.mode } returns OrganizationAuthorityMode.CONTAINER_LOCAL
        every { facade.getHabitVisualIdentity(any()) } returns null
        every { facade.getRootGroupKey(first.uuid!!, any()) } calls { roots[first.uuid!!] }
        every { facade.getRootGroupKey(second.uuid!!, any()) } calls { roots[second.uuid!!] }
        habitList.organizationFacade = facade
        val runner = QueuedRunner()
        val cache = HabitCardListCache(habitList, commandRunner, runner, mock())
        cache.setCheckmarkCount(10)
        cache.refreshAllHabits(); runner.finish()
        assertEquals("root-1", cache.getMetadata(first.id!!).rootGroupKey)
        assertEquals("root-2", cache.getMetadata(second.id!!).rootGroupKey)

        roots[first.uuid!!] = "root-1-updated"
        cache.refreshHabit(first.id!!); runner.finish()
        assertEquals("root-1-updated", cache.getMetadata(first.id!!).rootGroupKey)
        assertEquals("root-2", cache.getMetadata(second.id!!).rootGroupKey)
    }
}
