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

package org.isoron.uhabits.activities.intro

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import com.github.appintro.R as IntroResources
import kotlinx.coroutines.launch
import org.isoron.uhabits.R

/**
 * Activity that introduces the app to the user, shown only after the app is
 * launched for the first time.
 */
class IntroActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            isAppearanceLightNavigationBars = false
        }

        setContentView(ComposeView(this).apply {
            id = IntroResources.id.view_pager
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { IntroScreen() }
        })
    }

    @Composable
    private fun IntroScreen() {
        val pager = rememberPagerState(pageCount = { Slides.size })
        val scope = rememberCoroutineScope()
        DisposableEffect(pager) {
            val callback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val page = pager.currentPage
                    if (page == 0) finish() else scope.launch { pager.animateScrollToPage(page - 1) }
                }
            }
            onBackPressedDispatcher.addCallback(this@IntroActivity, callback)
            onDispose { callback.remove() }
        }

        val page = pager.currentPage
        val isLastPage = page == Slides.lastIndex
        Box(Modifier.fillMaxSize().background(Slides[page].background).navigationBarsPadding()) {
            HorizontalPager(pager, modifier = Modifier.fillMaxSize()) { index ->
                IntroSlideContent(Slides[index])
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(90.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Slides.indices.forEach { index ->
                        Box(
                            Modifier.size(10.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = if (index == page) 1f else 0.45f))
                        )
                    }
                }
                if (!isLastPage) {
                    IntroAction(
                        icon = IntroResources.drawable.ic_appintro_fab_skip,
                        description = IntroResources.string.app_intro_skip_button,
                        onClick = ::finish,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    IntroAction(
                        icon = IntroResources.drawable.ic_appintro_fab_next,
                        description = IntroResources.string.app_intro_next_button,
                        onClick = { scope.launch { pager.animateScrollToPage(page + 1) } },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                } else {
                    IntroAction(
                        icon = IntroResources.drawable.ic_appintro_fab_done,
                        description = IntroResources.string.app_intro_done_button,
                        onClick = ::finish,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }

    private companion object {
        val Slides = listOf(
            IntroSlide(R.string.intro_title_1, R.string.intro_description_1, R.drawable.intro_icon_1, Color(0xFF194673)),
            IntroSlide(R.string.intro_title_2, R.string.intro_description_2, R.drawable.intro_icon_2, Color(0xFFFFA726)),
            IntroSlide(R.string.intro_title_4, R.string.intro_description_4, R.drawable.intro_icon_4, Color(0xFF9575CD))
        )
    }
}

private data class IntroSlide(val title: Int, val description: Int, val image: Int, val background: Color)

@Composable
private fun IntroSlideContent(slide: IntroSlide) {
    Column(
        Modifier.fillMaxSize().background(slide.background).padding(top = 24.dp, bottom = 90.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.weight(2f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            BasicText(
                stringResource(slide.title),
                modifier = Modifier.semantics { heading() },
                style = TextStyle(color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold),
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 28.sp)
            )
        }
        Image(
            painterResource(slide.image),
            stringResource(IntroResources.string.app_intro_image_content_description),
            Modifier.weight(5f).fillMaxWidth().padding(horizontal = 32.dp),
            contentScale = ContentScale.Inside
        )
        Box(Modifier.weight(3f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            BasicText(
                stringResource(slide.description),
                style = TextStyle(color = Color.White, textAlign = TextAlign.Center),
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 16.sp)
            )
        }
    }
}

@Composable
private fun IntroAction(icon: Int, description: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val size = with(LocalDensity.current) { 56.dp.roundToPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val bitmap = remember(context, icon, size, rtl) {
        requireNotNull(context.getDrawable(icon)).mutate().let { drawable ->
            DrawableCompat.setLayoutDirection(drawable, if (rtl) android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR)
            drawable.toBitmap(width = size, height = size).asImageBitmap()
        }
    }
    Box(
        modifier.size(88.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(bitmap, stringResource(description), Modifier.size(56.dp))
    }
}
