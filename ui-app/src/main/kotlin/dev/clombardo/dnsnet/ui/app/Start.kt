/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.app

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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowWidthSizeClass
import dev.clombardo.dnsnet.ui.common.FabState
import dev.clombardo.dnsnet.ui.common.IconSettingButton
import dev.clombardo.dnsnet.ui.common.ListSettingsContainer
import dev.clombardo.dnsnet.ui.common.SplitSwitchListItem
import dev.clombardo.dnsnet.ui.common.SwitchListItem
import dev.clombardo.dnsnet.ui.common.TriStateFab
import dev.clombardo.dnsnet.ui.common.navigation.NavigationBar
import dev.clombardo.dnsnet.ui.common.theme.Animation
import dev.clombardo.dnsnet.ui.common.theme.DnsNetTheme
import dev.clombardo.dnsnet.ui.common.theme.FabPadding

object Start {
    const val TEST_TAG_START_BUTTON = "start_button"
}

@Composable
fun StartScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    resumeOnStartup: Boolean,
    onResumeOnStartupClick: () -> Unit,
    blockLog: Boolean,
    onToggleBlockLog: () -> Unit,
    onOpenBlockLog: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    isWritingLogcat: Boolean,
    onShareLogcat: () -> Unit,
    onResetSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    state: FabState,
    onChangeVpnStatusClick: () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ListSettingsContainer(title = stringResource(R.string.start_title)) {
                    item {
                        SwitchListItem(
                            title = stringResource(id = R.string.switch_onboot),
                            details = stringResource(id = R.string.switch_onboot_description),
                            checked = resumeOnStartup,
                            onCheckedChange = { onResumeOnStartupClick() },
                        )
                    }

                    item {
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
                }
                Spacer(Modifier.padding(vertical = 4.dp))
            }

            item {
                ListSettingsContainer {
                    item {
                        IconSettingButton(
                            title = stringResource(R.string.action_import),
                            description = stringResource(R.string.import_description),
                            icon = Icons.Default.Download,
                            onClick = onImport,
                        )
                    }

                    item {
                        IconSettingButton(
                            title = stringResource(R.string.action_export),
                            description = stringResource(R.string.export_description),
                            icon = Icons.Default.Upload,
                            onClick = onExport,
                        )
                    }

                    item {
                        IconSettingButton(
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
                        )
                    }

                    item {
                        IconSettingButton(
                            title = stringResource(R.string.load_defaults),
                            description = stringResource(R.string.load_defaults_description),
                            icon = Icons.Default.History,
                            onClick = onResetSettings,
                        )
                    }

                    item {
                        IconSettingButton(
                            title = stringResource(R.string.action_about),
                            description = stringResource(R.string.about_description),
                            icon = Icons.Default.Info,
                            onClick = onOpenAbout,
                        )
                    }
                }
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
            val iconSize = 42.dp
            TriStateFab(
                modifier = Modifier
                    .testTag(Start.TEST_TAG_START_BUTTON)
                    .then(
                        if (windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.COMPACT) {
                            Modifier
                                .padding(bottom = NavigationBar.height)
                                .systemBarsPadding()
                        } else {
                            Modifier.displayCutoutPadding()
                        }
                    )
                    .padding(FabPadding),
                state = state,
                onClick = onChangeVpnStatusClick,
                inactiveContent = {
                    Icon(
                        modifier = Modifier.size(iconSize),
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.action_start),
                    )
                },
                loadingContent = {
                    CircularProgressIndicator(color = LocalContentColor.current)
                },
                activeContent = {
                    Icon(
                        modifier = Modifier.size(iconSize),
                        imageVector = Icons.Default.Stop,
                        contentDescription = stringResource(R.string.action_stop),
                    )
                },
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
            state = FabState.Inactive,
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
