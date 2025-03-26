/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

data class TabLayoutContent(
    val tabContent: @Composable () -> Unit,
    val pageContent: @Composable ColumnScope.() -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialHorizontalTabLayout(
    modifier: Modifier = Modifier,
    initialPage: Int = 0,
    onPageChange: (index: Int) -> Unit = {},
    pages: List<TabLayoutContent>,
) {
    assert(pages.indices.contains(initialPage))
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        var selectedTabIndex by remember { mutableIntStateOf(initialPage) }
        PrimaryTabRow(
            modifier = Modifier.fillMaxWidth(),
            selectedTabIndex = selectedTabIndex,
            containerColor = Color.Transparent,
        ) {
            pages.forEachIndexed { index, tabLayoutContent ->
                Tab(
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    selected = index == selectedTabIndex,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    onClick = { selectedTabIndex = index },
                ) {
                    tabLayoutContent.tabContent()
                }
            }
        }

        val pagerState = rememberPagerState(
            initialPage = initialPage,
        ) { pages.size }
        HorizontalPager(
            modifier = Modifier.fillMaxWidth(),
            state = pagerState,
        ) { pageIndex ->
            Column(
                modifier = Modifier
                    .defaultMinSize(minHeight = 192.dp)
                    .padding(bottom = 16.dp),
                content = pages[pageIndex].pageContent,
            )
        }

        LaunchedEffect(selectedTabIndex) {
            pagerState.animateScrollToPage(selectedTabIndex)
            onPageChange(selectedTabIndex)
        }
        LaunchedEffect(pagerState.currentPage) {
            selectedTabIndex = pagerState.currentPage
            onPageChange(selectedTabIndex)
        }
    }
}

@Preview
@Composable
fun MaterialHorizontalTabLayoutPreview() {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
    ) {
        MaterialHorizontalTabLayout(
            pages = listOf(
                TabLayoutContent(
                    tabContent = {
                        Text("Tab 1")
                    },
                    pageContent = {
                        Text("Page 1")
                    },
                ),
                TabLayoutContent(
                    tabContent = {
                        Text("Tab 2")
                    },
                    pageContent = {
                        Text("Page 2")
                    },
                ),
            )
        )
    }
}
