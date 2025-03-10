/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowWidthSizeClass
import coil3.compose.rememberAsyncImagePainter
import dev.clombardo.dnsnet.AllowListMode
import dev.clombardo.dnsnet.AllowListMode.Companion.toAllowListMode
import dev.clombardo.dnsnet.R
import dev.clombardo.dnsnet.ui.navigation.NavigationBar
import dev.clombardo.dnsnet.ui.state.AppListState
import dev.clombardo.dnsnet.ui.theme.DnsNetTheme
import dev.clombardo.dnsnet.ui.theme.ScrollUpIndicatorPadding
import dev.clombardo.dnsnet.ui.theme.ScrollUpIndicatorSize
import dev.clombardo.dnsnet.viewmodel.AppListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    listViewModel: AppListViewModel,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    bypassSelection: AllowListMode,
    onBypassSelection: (AllowListMode) -> Unit,
    apps: List<App> = emptyList(),
    onAppClick: (App, Boolean) -> Unit,
) {
    val pullToRefreshState = rememberPullToRefreshState()

    val adjustedList by remember {
        derivedStateOf { listViewModel.getList(apps) }
    }

    PullToRefreshBox(
        modifier = modifier,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = pullToRefreshState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullToRefreshState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                threshold = PullToRefreshDefaults.PositionalThreshold + contentPadding.calculateTopPadding(),
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier.testTag("apps:list"),
            contentPadding = contentPadding +
                    PaddingValues(bottom = ScrollUpIndicatorPadding + ScrollUpIndicatorSize),
            state = listState,
        ) {
            item {
                ListSettingsContainer(
                    title = stringResource(R.string.allowlist_description),
                ) {
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    val bypassOptions = stringArrayResource(R.array.allowlist_defaults)
                    ExpandableOptionsItem(
                        expanded = expanded,
                        title = stringResource(R.string.allowlist_defaults_title),
                        details = bypassOptions[bypassSelection.ordinal],
                        onExpandClick = { expanded = !expanded },
                    ) {
                        bypassOptions.forEachIndexed { i, option ->
                            val thisMode = i.toAllowListMode()
                            RadioListItem(
                                checked = thisMode == bypassSelection,
                                title = option,
                                onCheckedChange = { onBypassSelection(thisMode) },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.padding(vertical = 4.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val keyboardOptions = remember {
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                        )
                    }
                    SearchWidget(
                        modifier = Modifier.weight(
                            weight = 1f,
                            fill = false
                        ),
                        expanded = listViewModel.searchWidgetExpanded,
                        searchValue = listViewModel.searchValue,
                        onSearchButtonClick = { listViewModel.searchWidgetExpanded = true },
                        onSearchValueChange = { listViewModel.searchValue = it },
                        onClearButtonClick = {
                            listViewModel.searchWidgetExpanded = false
                            listViewModel.searchValue = ""
                        },
                        keyboardOptions = keyboardOptions,
                    )
                    Spacer(Modifier.padding(horizontal = 2.dp))
                    BasicTooltipIconButton(
                        icon = Icons.Default.FilterList,
                        contentDescription = stringResource(R.string.modify_list),
                        onClick = { listViewModel.showModifyListSheet = true },
                    )
                }
            }

            items(
                items = adjustedList,
                key = { it.info.packageName },
            ) {
                var checked by remember { mutableStateOf(it.enabled) }
                checked = it.enabled
                SwitchListItem(
                    modifier = Modifier
                        .testTag("apps:listItem")
                        .animateItem(),
                    title = it.label,
                    details = it.info.packageName,
                    checked = checked,
                    onCheckedChange = { _ ->
                        checked = !checked
                        onAppClick(it, checked)
                    },
                    startContent = {
                        Image(
                            modifier = Modifier.fillMaxSize(),
                            painter = rememberAsyncImagePainter(it),
                            contentDescription = it.label,
                        )
                    }
                )
            }
        }

        val isAtTop by remember {
            derivedStateOf {
                listState.firstVisibleItemIndex != 0
            }
        }
        val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
        ScrollUpIndicator(
            visible = isAtTop,
            windowInsets = ScrollUpIndicatorDefaults.windowInsets
                .add(
                    if (windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.COMPACT) {
                        WindowInsets(bottom = NavigationBar.height)
                    } else {
                        WindowInsets(bottom = 0.dp)
                    }
                ),
            onClick = { listState.animateScrollToItem(0) },
        )
    }

    var currentModifyListPage by rememberSaveable { mutableIntStateOf(0) }
    if (listViewModel.showModifyListSheet) {
        ModalBottomSheet(
            onDismissRequest = { listViewModel.showModifyListSheet = false }
        ) {
            MaterialHorizontalTabLayout(
                initialPage = currentModifyListPage,
                onPageChange = { currentModifyListPage = it },
                pages = listOf(
                    TabLayoutContent(
                        tabContent = {
                            Text(stringResource(R.string.sort))
                        },
                        pageContent = {
                            AppListState.SortType.entries.forEach {
                                SortItem(
                                    selected = listViewModel.sort.selectedType == it,
                                    ascending = listViewModel.sort.ascending,
                                    label = stringResource(it.labelRes),
                                    onClick = { listViewModel.onSortClick(it) }
                                )
                            }
                        },
                    ),
                    TabLayoutContent(
                        tabContent = {
                            Text(stringResource(R.string.filter))
                        },
                        pageContent = {
                            AppListState.FilterType.entries.forEach {
                                FilterItem(
                                    label = stringResource(it.labelRes),
                                    mode = listViewModel.filter.filters[it],
                                    onClick = { listViewModel.onFilterClick(it) }
                                )
                            }
                        },
                    ),
                )
            )
        }
    }
}

@Preview
@Composable
private fun AppsScreenPreview() {
    DnsNetTheme {
        AppsScreen(
            isRefreshing = false,
            onRefresh = {},
            apps = listOf(App(ApplicationInfo(), "Label", true, false)),
            listViewModel = viewModel(),
            onAppClick = { _, _ -> },
            bypassSelection = AllowListMode.ON_VPN,
            onBypassSelection = {},
        )
    }
}
