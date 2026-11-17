/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.about

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.compose.UHabitElevationAndBorders

@Composable
internal fun AboutComposeScreen(
    colors: UHabitComposeColors,
    versionName: String,
    versionCode: Int,
    isDeveloper: Boolean,
    destination: AboutDestination,
    gplLicenseText: String,
    fullNoticesText: String,
    snackbarHostState: SnackbarHostState,
    onNavigate: (AboutDestination) -> Unit,
    onBack: () -> Unit,
    onVersionTap: () -> Unit,
    onOpenSourceCode: () -> Unit,
    onReportIssue: () -> Unit,
    onOpenUpstreamRepository: () -> Unit,
    onOpenUpstreamContributors: () -> Unit,
) {
    AboutBackHandler(enabled = destination != AboutDestination.MAIN, onBack = onBack)

    val topTitle = when (destination) {
        AboutDestination.MAIN -> stringResource(R.string.about)
        AboutDestination.CONTRIBUTORS -> stringResource(R.string.about_original_contributors_title)
        AboutDestination.LICENSES -> stringResource(R.string.about_open_source_notices_title)
        AboutDestination.GPL_LICENSE -> stringResource(R.string.about_gpl_license_title)
        AboutDestination.FULL_NOTICE -> stringResource(R.string.about_full_notices_title)
    }

    UHabitComposeTheme(colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(colors.background)),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                AboutTopBar(
                    title = topTitle,
                    colors = colors,
                    onBack = onBack,
                )

                androidx.compose.runtime.key(destination) {
                    val scrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .navigationBarsPadding()
                            .padding(bottom = 24.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 640.dp)
                                .fillMaxWidth(),
                        ) {
                            when (destination) {
                                AboutDestination.MAIN -> AboutMainContent(
                                    colors = colors,
                                    versionName = versionName,
                                    versionCode = versionCode,
                                    isDeveloper = isDeveloper,
                                    onVersionTap = onVersionTap,
                                    onOpenSourceCode = onOpenSourceCode,
                                    onReportIssue = onReportIssue,
                                    onOpenUpstreamRepository = onOpenUpstreamRepository,
                                    onOpenContributors = { onNavigate(AboutDestination.CONTRIBUTORS) },
                                    onOpenGplLicense = { onNavigate(AboutDestination.GPL_LICENSE) },
                                    onOpenLicenses = { onNavigate(AboutDestination.LICENSES) },
                                )

                                AboutDestination.CONTRIBUTORS -> AboutContributorsContent(
                                    colors = colors,
                                    onOpenUpstreamContributors = onOpenUpstreamContributors,
                                )

                                AboutDestination.LICENSES -> AboutLicensesContent(
                                    colors = colors,
                                    onOpenFullNotices = { onNavigate(AboutDestination.FULL_NOTICE) },
                                )

                                AboutDestination.GPL_LICENSE -> AboutLegalDocumentContent(
                                    colors = colors,
                                    documentText = gplLicenseText,
                                )

                                AboutDestination.FULL_NOTICE -> AboutLegalDocumentContent(
                                    colors = colors,
                                    documentText = fullNoticesText,
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Snackbar(
                        containerColor = if (colors.isPureBlack || colors.isDark) {
                            Color(0xFF232830)
                        } else {
                            Color(0xFF1F242B)
                        },
                        contentColor = Color.White,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    ) {
                        BasicText(
                            text = data.visuals.message,
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = true),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutMainContent(
    colors: UHabitComposeColors,
    versionName: String,
    versionCode: Int,
    isDeveloper: Boolean,
    onVersionTap: () -> Unit,
    onOpenSourceCode: () -> Unit,
    onReportIssue: () -> Unit,
    onOpenUpstreamRepository: () -> Unit,
    onOpenContributors: () -> Unit,
    onOpenGplLicense: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val openLinkAction = stringResource(R.string.about_open_link_action)
    val viewLicenseAction = stringResource(R.string.about_view_license_action)
    Spacer(Modifier.height(if (colors.isClassicSectionLayout) 4.dp else 8.dp))

    AboutSectionSurface(colors = colors) {
        AboutIdentityHeader(colors = colors)
        AboutRowDivider(colors = colors, hasLeadingIcon = false)
        val versionSummary = buildString {
            append(stringResource(R.string.about_build_code, versionCode))
            if (isDeveloper) {
                append(" · ")
                append(stringResource(R.string.about_developer_enabled))
            }
        }
        AboutRow(
            title = stringResource(R.string.version_n, versionName),
            summary = versionSummary,
            iconRes = R.drawable.ic_settings_info,
            trailingIconRes = null,
            colors = colors,
            onClick = onVersionTap,
        )
    }

    AboutSectionHeader(title = stringResource(R.string.about_section_project), colors = colors)
    AboutSectionSurface(colors = colors) {
        AboutRow(
            title = stringResource(R.string.about_source_code),
            summary = stringResource(R.string.about_source_code_summary),
            iconRes = R.drawable.ic_l_code,
            trailingIconRes = R.drawable.ic_settings_link,
            colors = colors,
            clickLabel = openLinkAction,
            onClick = onOpenSourceCode,
        )
        AboutRowDivider(colors = colors, hasLeadingIcon = true)
        AboutRow(
            title = stringResource(R.string.about_report_issue),
            summary = stringResource(R.string.about_report_issue_summary),
            iconRes = R.drawable.ic_settings_bug,
            trailingIconRes = R.drawable.ic_settings_link,
            colors = colors,
            clickLabel = openLinkAction,
            onClick = onReportIssue,
        )
    }

    AboutSectionHeader(title = stringResource(R.string.about_section_authors), colors = colors)
    AboutSectionSurface(colors = colors) {
        AboutRow(
            title = stringResource(R.string.about_fork_maintainer_title),
            summary = stringResource(R.string.about_fork_maintainer_summary),
            iconRes = R.drawable.ic_settings_account,
            trailingIconRes = null,
            colors = colors,
            onClick = null,
        )
        AboutRowDivider(colors = colors, hasLeadingIcon = true)
        AboutRow(
            title = stringResource(R.string.about_upstream_project_title),
            summary = stringResource(R.string.about_upstream_project_summary),
            iconRes = R.drawable.ic_l_git_fork,
            trailingIconRes = R.drawable.ic_settings_link,
            colors = colors,
            clickLabel = openLinkAction,
            onClick = onOpenUpstreamRepository,
        )
        AboutRowDivider(colors = colors, hasLeadingIcon = true)
        AboutRow(
            title = stringResource(R.string.about_original_contributors_title),
            summary = stringResource(R.string.about_original_contributors_summary),
            iconRes = R.drawable.ic_cat_users,
            trailingIconRes = R.drawable.ic_chevron_right,
            colors = colors,
            onClick = onOpenContributors,
        )
    }

    AboutSectionHeader(title = stringResource(R.string.about_section_licenses), colors = colors)
    AboutSectionSurface(colors = colors) {
        AboutRow(
            title = stringResource(R.string.about_app_license_title),
            summary = stringResource(R.string.about_app_license_summary),
            iconRes = R.drawable.ic_cat_scale,
            trailingIconRes = R.drawable.ic_chevron_right,
            colors = colors,
            clickLabel = viewLicenseAction,
            onClick = onOpenGplLicense,
        )
        AboutRowDivider(colors = colors, hasLeadingIcon = true)
        AboutRow(
            title = stringResource(R.string.about_open_source_notices_title),
            summary = stringResource(R.string.about_open_source_notices_summary),
            iconRes = R.drawable.ic_l_book_open_text,
            trailingIconRes = R.drawable.ic_chevron_right,
            colors = colors,
            onClick = onOpenLicenses,
        )
    }
}

@Composable
private fun AboutContributorsContent(
    colors: UHabitComposeColors,
    onOpenUpstreamContributors: () -> Unit,
) {
    val openLinkAction = stringResource(R.string.about_open_link_action)
    AboutSectionHeader(title = stringResource(R.string.about_upstream_developers_section), colors = colors)
    AboutSectionSurface(colors = colors) {
        AboutRow(
            title = stringResource(R.string.about_view_upstream_contributors),
            summary = stringResource(R.string.about_view_upstream_contributors_summary),
            iconRes = R.drawable.ic_settings_link,
            trailingIconRes = R.drawable.ic_settings_link,
            colors = colors,
            clickLabel = openLinkAction,
            onClick = onOpenUpstreamContributors,
        )
        AboutRowDivider(colors = colors, hasLeadingIcon = false)
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AboutCreditsData.upstreamDevelopers.forEach { developer ->
                    BasicText(
                        text = developer,
                        style = TextStyle(
                            color = Color(colors.foreground),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = true),
                        ),
                    )
                }
            }
        }
    }

    AboutSectionHeader(title = stringResource(R.string.about_upstream_translators_section), colors = colors)
    AboutSectionSurface(colors = colors) {
        SelectionContainer {
            Column(modifier = Modifier.fillMaxWidth()) {
                AboutCreditsData.upstreamTranslators.forEachIndexed { index, group ->
                    if (index > 0) {
                        AboutRowDivider(colors = colors, hasLeadingIcon = false)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        BasicText(
                            text = group.language,
                            style = TextStyle(
                                color = Color(colors.foreground),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                platformStyle = PlatformTextStyle(includeFontPadding = true),
                            ),
                        )
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            text = group.translators.joinToString(", "),
                            style = TextStyle(
                                color = Color(colors.secondary),
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = true),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutLicensesContent(
    colors: UHabitComposeColors,
    onOpenFullNotices: () -> Unit,
) {
    val viewLicenseAction = stringResource(R.string.about_view_license_action)
    BasicText(
        text = stringResource(R.string.about_open_source_intro),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (colors.isClassicSectionLayout) 18.dp else 16.dp,
                vertical = 10.dp,
            ),
        style = TextStyle(
            color = Color(colors.secondary),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = true),
        ),
    )

    AboutSectionSurface(colors = colors) {
        AboutRow(
            title = stringResource(R.string.about_full_notices_title),
            summary = stringResource(R.string.about_full_notices_summary),
            iconRes = R.drawable.ic_l_book_open_text,
            trailingIconRes = R.drawable.ic_chevron_right,
            colors = colors,
            clickLabel = viewLicenseAction,
            onClick = onOpenFullNotices,
        )
    }

    Spacer(Modifier.height(if (colors.isClassicSectionLayout) 8.dp else 12.dp))

    AboutSectionSurface(colors = colors) {
        SelectionContainer {
            Column(modifier = Modifier.fillMaxWidth()) {
                AboutCreditsData.openSourceNotices.forEachIndexed { index, notice ->
                    if (index > 0) {
                        AboutRowDivider(colors = colors, hasLeadingIcon = false)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        BasicText(
                            text = notice.name,
                            style = TextStyle(
                                color = Color(colors.foreground),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                platformStyle = PlatformTextStyle(includeFontPadding = true),
                            ),
                        )
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            text = notice.license,
                            style = TextStyle(
                                color = Color(colors.accent),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                platformStyle = PlatformTextStyle(includeFontPadding = true),
                            ),
                        )
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            text = notice.scope,
                            style = TextStyle(
                                color = Color(colors.secondary),
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = true),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutLegalDocumentContent(
    colors: UHabitComposeColors,
    documentText: String,
) {
    Spacer(Modifier.height(if (colors.isClassicSectionLayout) 4.dp else 8.dp))
    val resolvedText = documentText.ifBlank {
        stringResource(R.string.about_legal_document_unavailable)
    }
    AboutSectionSurface(colors = colors) {
        SelectionContainer {
            BasicText(
                text = resolvedText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                style = TextStyle(
                    color = Color(colors.foreground),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontFamily = FontFamily.Default,
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
            )
        }
    }
}

@Composable
private fun AboutTopBar(
    title: String,
    colors: UHabitComposeColors,
    onBack: () -> Unit,
) {
    val backDescription = stringResource(R.string.settings_back)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(colors.background))
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = backDescription },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_settings_back),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(colors.foreground)),
                modifier = Modifier.size(24.dp),
            )
        }
        BasicText(
            text = title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 16.dp)
                .semantics { heading() },
            style = TextStyle(
                color = Color(colors.foreground),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                platformStyle = PlatformTextStyle(includeFontPadding = true),
            ),
        )
    }
}

@Composable
private fun AboutIdentityHeader(colors: UHabitComposeColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppLauncherIcon(modifier = Modifier.size(48.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = stringResource(R.string.app_name),
                style = TextStyle(
                    color = Color(colors.foreground),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = stringResource(R.string.about_build_status),
                style = TextStyle(
                    color = Color(colors.secondary),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
            )
        }
    }
}

@Composable
private fun AppLauncherIcon(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 48.dp.roundToPx().coerceAtLeast(48) }
    val bitmap = remember(context, sizePx) {
        AppCompatResources.getDrawable(context, R.mipmap.ic_launcher)
            ?.toBitmap(width = sizePx, height = sizePx)
            ?.asImageBitmap()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.app_name),
            modifier = modifier,
        )
    }
}

@Composable
private fun AboutSectionHeader(
    title: String,
    colors: UHabitComposeColors,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (colors.isClassicSectionLayout) 18.dp else 20.dp,
                end = 16.dp,
                top = 18.dp,
                bottom = 8.dp,
            )
            .semantics { heading() },
    ) {
        BasicText(
            text = title,
            style = TextStyle(
                color = Color(colors.accent),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                platformStyle = PlatformTextStyle(includeFontPadding = true),
            ),
        )
    }
}

@Composable
private fun AboutSectionSurface(
    colors: UHabitComposeColors,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (colors.isClassicSectionLayout) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(colors.background)),
            content = content,
        )
        return
    }

    val shape = colors.resolveCardShape(colors.habitsCardCornerRadiusDp)
    val surfaceColor = colors.resolveSectionSurfaceColor()
    val borderModifier = if (colors.showCardBorders) {
        Modifier.border(
            width = UHabitElevationAndBorders.hairlineBorderWidth,
            color = colors.resolveSectionBorderColor(),
            shape = shape,
        )
    } else {
        Modifier
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(shape)
            .background(surfaceColor, shape)
            .then(borderModifier),
        content = content,
    )
}

@Composable
private fun AboutRow(
    title: String,
    summary: String?,
    iconRes: Int,
    trailingIconRes: Int?,
    colors: UHabitComposeColors,
    clickLabel: String? = null,
    onClick: (() -> Unit)?,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 52.dp)
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    onClickLabel = clickLabel,
                    role = Role.Button,
                    onClick = onClick,
                )
            } else {
                Modifier
            }
        )
        .padding(horizontal = 16.dp, vertical = 12.dp)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color(colors.secondary)),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = TextStyle(
                    color = Color(colors.foreground),
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
            )
            if (!summary.isNullOrBlank()) {
                BasicText(
                    text = summary,
                    modifier = Modifier.padding(top = 2.dp),
                    style = TextStyle(
                        color = Color(colors.secondary),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = true),
                    ),
                )
            }
        }
        if (trailingIconRes != null) {
            Spacer(Modifier.width(8.dp))
            Image(
                painter = painterResource(trailingIconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(colors.secondary)),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun AboutRowDivider(
    colors: UHabitComposeColors,
    hasLeadingIcon: Boolean,
) {
    val startInset = if (hasLeadingIcon) 50.dp else 16.dp
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startInset, end = 16.dp)
            .height(1.dp)
            .background(Color(colors.divider)),
    )
}

@Composable
private fun AboutBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val owner = LocalContext.current as? OnBackPressedDispatcherOwner ?: return
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(owner, enabled) {
        val callback = object : OnBackPressedCallback(enabled) {
            override fun handleOnBackPressed() = currentOnBack()
        }
        owner.onBackPressedDispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }
}
