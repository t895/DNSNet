/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowWidthSizeClass
import dev.clombardo.dnsnet.R
import dev.clombardo.dnsnet.ui.navigation.NavigationBar
import dev.clombardo.dnsnet.ui.theme.Animation
import dev.clombardo.dnsnet.ui.theme.DnsNetTheme
import dev.clombardo.dnsnet.ui.theme.FabPadding
import dev.clombardo.dnsnet.vpn.VpnStatus

data class StartButton(
    val enabled: Boolean = true,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val endContent: @Composable (() -> Unit)? = null,
)

@Composable
fun StartScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyGridState = rememberLazyGridState(),
    resumeOnStartup: Boolean,
    onResumeOnStartupClick: () -> Unit,
    ipv6Support: Boolean,
    onIpv6SupportClick: () -> Unit,
    blockLog: Boolean,
    onToggleBlockLog: () -> Unit,
    onOpenBlockLog: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    isWritingLogcat: Boolean,
    onShareLogcat: () -> Unit,
    onResetSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    status: VpnStatus,
    onChangeVpnStatusClick: () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        val startButtons = listOf(
            StartButton(
                title = stringResource(R.string.action_import),
                description = stringResource(R.string.import_description),
                icon = Icons.Default.Download,
                onClick = onImport,
            ),
            StartButton(
                title = stringResource(R.string.action_export),
                description = stringResource(R.string.export_description),
                icon = Icons.Default.Upload,
                onClick = onExport,
            ),
            StartButton(
                enabled = !isWritingLogcat,
                title = stringResource(R.string.action_logcat),
                description = stringResource(R.string.logcat_description),
                icon = Icons.Default.BugReport,
                onClick = onShareLogcat,
                endContent = {
                    AnimatedVisibility(
                        modifier = Modifier.height(IntrinsicSize.Max),
                        visible = isWritingLogcat,
                        enter = Animation.ShowSpinnerHorizontal,
                        exit = Animation.HideSpinnerHorizontal,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Spacer(Modifier.padding(horizontal = 8.dp))
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                }
            ),
            StartButton(
                title = stringResource(R.string.load_defaults),
                description = stringResource(R.string.load_defaults_description),
                icon = Icons.Default.History,
                onClick = onResetSettings,
            ),
            StartButton(
                title = stringResource(R.string.action_about),
                description = stringResource(R.string.about_description),
                icon = Icons.Default.Info,
                onClick = onOpenAbout,
            ),
        )

        val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
        val columns = remember {
            when (windowSizeClass.windowWidthSizeClass) {
                WindowWidthSizeClass.COMPACT -> 1
                WindowWidthSizeClass.MEDIUM -> 2
                else -> 3
            }
        }

        LazyVerticalGrid(
            state = listState,
            contentPadding = contentPadding,
            columns = GridCells.Fixed(columns),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(columns) }) {
                ListSettingsContainer(title = stringResource(R.string.start_title)) {
                    SwitchListItem(
                        title = stringResource(id = R.string.switch_onboot),
                        details = stringResource(id = R.string.switch_onboot_description),
                        checked = resumeOnStartup,
                        onCheckedChange = { onResumeOnStartupClick() },
                    )
                    SwitchListItem(
                        title = stringResource(id = R.string.ipv6_support),
                        details = stringResource(id = R.string.ipv6_support_description),
                        checked = ipv6Support,
                        onCheckedChange = { onIpv6SupportClick() },
                    )
                    SplitSwitchListItem(
                        title = stringResource(id = R.string.block_log),
                        details = stringResource(id = R.string.block_log_description),
                        maxDetailLines = Int.MAX_VALUE,
                        outlineColor = MaterialTheme.colorScheme.outline,
                        checked = blockLog,
                        bodyEnabled = blockLog,
                        onCheckedChange = { onToggleBlockLog() },
                        onBodyClick = onOpenBlockLog,
                    )
                }
                Spacer(Modifier.padding(vertical = 4.dp))
            }

            items(startButtons) {
                FilledTonalSettingsButton(
                    enabled = it.enabled,
                    title = it.title,
                    description = it.description,
                    icon = it.icon,
                    onClick = it.onClick,
                    endContent = it.endContent,
                )
            }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = if (windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.COMPACT) {
                Alignment.BottomCenter
            } else {
                Alignment.BottomEnd
            },
        ) {
            VpnFab(
                modifier = Modifier
                    .then(
                        if (windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.COMPACT) {
                            Modifier.padding(bottom = NavigationBar.height)
                        } else {
                            Modifier.displayCutoutPadding()
                        }
                    )
                    .padding(FabPadding),
                status = status,
                onClick = onChangeVpnStatusClick,
            )
        }
    }
}

@Preview
@Composable
private fun StartScreenPreview() {
    DnsNetTheme {
        StartScreen(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            resumeOnStartup = false,
            onResumeOnStartupClick = {},
            ipv6Support = false,
            onIpv6SupportClick = {},
            status = VpnStatus.STOPPED,
            onChangeVpnStatusClick = {},
            blockLog = true,
            onToggleBlockLog = {},
            onOpenBlockLog = {},
            onImport = {},
            onExport = {},
            isWritingLogcat = false,
            onShareLogcat = {},
            onResetSettings = {},
            onOpenAbout = {},
        )
    }
}
