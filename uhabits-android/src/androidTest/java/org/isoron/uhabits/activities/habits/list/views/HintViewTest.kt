/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.activities.habits.list.views

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.isoron.uhabits.BaseViewTest
import org.isoron.uhabits.core.ui.screens.habits.list.HintList
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class HintViewTest : BaseViewTest() {
    private lateinit var view: HintView
    private lateinit var list: HintList

    @Before
    override fun setUp() {
        super.setUp()
        list = mock()
        view = HintView(targetContext, list)
        measureView(view, 400f, 200f)
        val text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit."
        every { list.shouldShow() } returns true
        every { list.pop() } returns text
        view.showNext()
        skipAnimation(view)
    }

    @Test
    @Throws(Exception::class)
    fun testRender() {
        assertRenders(view, PATH + "render.png")
    }

    @Test
    @Throws(Exception::class)
    fun testClick() {
        assertThat(view.alpha, equalTo(1f))
        view.performClick()
        skipAnimation(view)
        assertThat(view.alpha, equalTo(0f))
    }

    @Test
    fun missingOrBlankHintStaysGoneAfterReturningToHabits() {
        for (text in arrayOf<String?>(null, "", "  \n ")) {
            every { list.pop() } returns text
            val emptyView = HintView(targetContext, list)
            emptyView.showNext()
            emptyView.setSuppressed(true)
            emptyView.setSuppressed(false)
            assertThat(emptyView.visibility, equalTo(View.GONE))
        }
    }

    @Test
    fun validHintReturnsButDismissedHintDoesNot() {
        view.setSuppressed(true)
        assertThat(view.visibility, equalTo(View.GONE))
        view.setSuppressed(false)
        assertThat(view.visibility, equalTo(View.VISIBLE))
        val capture = java.io.File(targetContext.getExternalFilesDir(null), "hotfix/banner-valid.png")
        capture.parentFile!!.mkdirs()
        java.io.FileOutputStream(capture).use { renderView(view).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        view.performClick()
        skipAnimation(view)
        view.setSuppressed(true)
        view.setSuppressed(false)
        assertThat(view.visibility, equalTo(View.GONE))
    }

    companion object {
        const val PATH = "habits/list/HintView/"
    }
}
