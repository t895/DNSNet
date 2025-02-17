/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui

import android.os.Parcelable
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aallam.similarity.Cosine
import dev.clombardo.dnsnet.NumberFormatterCompat
import dev.clombardo.dnsnet.R
import dev.clombardo.dnsnet.ui.theme.Animation
import dev.clombardo.dnsnet.ui.theme.ListPadding
import dev.clombardo.dnsnet.ui.theme.ScrollUpIndicatorPadding
import dev.clombardo.dnsnet.ui.theme.ScrollUpIndicatorSize
import dev.clombardo.dnsnet.vpn.LoggedConnection
import kotlinx.parcelize.Parcelize

@Parcelize
data class LoggedConnectionState(
    val hostname: String,
    val allowed: Boolean,
    var attempts: Long,
    var lastAttemptTime: Long,
) : Parcelable

object BlockLog {
    val BlockedRatioAnimationSpec by lazy {
        tween<Float>(
            durationMillis = 500,
            easing = Animation.EmphasizedDecelerateEasing,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockLog(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    contentPadding: PaddingValues,
    loggedConnections: Map<String, LoggedConnection>,
    onCreateException: (LoggedConnectionState) -> Unit,
) {
    val allowedString = stringResource(R.string.allowed)
    val blockedString = stringResource(R.string.blocked)
    val allowedColor = MaterialTheme.colorScheme.onSurface
    val blockedColor = MaterialTheme.colorScheme.error

    var showModifyListSheet by rememberSaveable { mutableStateOf(false) }

    var sortState by rememberSaveable { mutableStateOf(BlockLogSortState()) }
    var filterState by rememberSaveable { mutableStateOf(BlockLogFilterState()) }
    var searchValue by rememberSaveable { mutableStateOf("") }
    val cosine = remember { Cosine() }

    val adjustedList by remember {
        derivedStateOf {
            val list = loggedConnections.map {
                LoggedConnectionState(
                    it.key,
                    it.value.allowed,
                    it.value.attempts,
                    it.value.lastAttemptTime,
                )
            }

            val sortedList = when (sortState.selectedType) {
                BlockLogSortType.Alphabetical -> if (sortState.ascending) {
                    list.sortedByDescending { it.hostname }
                } else {
                    list.sortedBy { it.hostname }
                }

                BlockLogSortType.LastConnected -> if (sortState.ascending) {
                    list.sortedByDescending { it.lastAttemptTime }
                } else {
                    list.sortedBy { it.lastAttemptTime }
                }

                BlockLogSortType.Attempts -> if (sortState.ascending) {
                    list.sortedByDescending { it.attempts }
                } else {
                    list.sortedBy { it.attempts }
                }
            }

            val filteredList = sortedList.filter {
                var result = true
                filterState.filters.forEach { (type, mode) ->
                    when (type) {
                        BlockLogFilterType.Blocked -> {
                            result = when (mode) {
                                FilterMode.Include -> !it.allowed
                                FilterMode.Exclude -> it.allowed
                            }
                        }
                    }
                }
                result
            }

            if (searchValue.isEmpty()) {
                filteredList
            } else {
                val adjustedSearchValue = searchValue.trim().lowercase()
                filteredList.mapNotNull {
                    val similarity = cosine.similarity(it.hostname, adjustedSearchValue)
                    if (similarity > 0) {
                        similarity to it
                    } else {
                        null
                    }
                }.sortedByDescending {
                    it.first
                }.map { it.second }
            }
        }
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = contentPadding + PaddingValues(ListPadding) +
                PaddingValues(bottom = ScrollUpIndicatorPadding + ScrollUpIndicatorSize),
    ) {
        item {
            val blockedConnections = loggedConnections.count { !it.value.allowed }
            val blockedConnectionsPercent =
                blockedConnections.toFloat() / loggedConnections.size.toFloat()
            val blockedRatioAnimated by animateFloatAsState(
                targetValue = blockedConnectionsPercent.takeIf { !it.isNaN() } ?: 0f,
                animationSpec = BlockLog.BlockedRatioAnimationSpec,
                label = "blockedRatioAnimated",
            )
            val size = 256.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(size),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(size),
                    progress = { blockedRatioAnimated },
                    strokeWidth = 14.dp,
                )

                val blockedConnectionsString = stringResource(
                    id = R.string.blocked_connections_percent,
                    formatArgs = arrayOf((blockedConnectionsPercent * 100).toInt()),
                )
                Text(
                    modifier = Modifier
                        .width(size * 0.65f)
                        .heightIn(min = 0.dp, max = size * 0.65f),
                    text = blockedConnectionsString,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
            }

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
                var expanded by rememberSaveable { mutableStateOf(false) }
                SearchWidget(
                    modifier = Modifier.weight(
                        weight = 1f,
                        fill = false
                    ),
                    expanded = expanded,
                    searchValue = searchValue,
                    onSearchButtonClick = { expanded = true },
                    onSearchValueChange = { searchValue = it },
                    onClearButtonClick = {
                        expanded = false
                        searchValue = ""
                    },
                    keyboardOptions = keyboardOptions,
                )
                Spacer(Modifier.padding(horizontal = 2.dp))
                BasicTooltipIconButton(
                    icon = Icons.Default.FilterList,
                    contentDescription = stringResource(R.string.modify_list),
                    onClick = { showModifyListSheet = true },
                )
            }
        }

        items(
            items = adjustedList,
            key = { it.hostname },
        ) {
            ContentSetting(
                modifier = Modifier.animateItem(),
                title = it.hostname,
                details = if (it.allowed) allowedString else blockedString,
                endContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val abbreviatedAttempts = NumberFormatterCompat.formatCompact(it.attempts)
                        Text(
                            text = abbreviatedAttempts,
                            color = if (it.allowed) allowedColor else blockedColor,
                        )

                        Spacer(Modifier.padding(horizontal = 4.dp))

                        Box(contentAlignment = Alignment.Center) {
                            var showMenu by rememberSaveable { mutableStateOf(false) }
                            BasicTooltipIconButton(
                                icon = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_options),
                                onClick = { showMenu = true },
                            )

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                MenuItem(
                                    text = stringResource(R.string.create_exception),
                                    painter = rememberVectorPainter(Icons.Default.Add),
                                    onClick = {
                                        showMenu = false
                                        onCreateException(it)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        }
    }

    var currentModifyListPage by rememberSaveable { mutableIntStateOf(0) }
    if (showModifyListSheet) {
        ModalBottomSheet(
            onDismissRequest = { showModifyListSheet = false }
        ) {
            MaterialHorizontalTabLayout(
                initialPage = currentModifyListPage,
                onPageChange = { currentModifyListPage = it },
                pages = listOf(
                    TabLayoutContent(
                        tabContent = {
                            Text("Sort")
                        },
                        pageContent = {
                            BlockLogSortType.entries.forEach {
                                SortItem(
                                    selected = sortState.selectedType == it,
                                    ascending = sortState.ascending,
                                    label = stringResource(it.labelRes),
                                    onClick = {
                                        sortState = if (sortState.selectedType == it) {
                                            BlockLogSortState(
                                                selectedType = it,
                                                ascending = !sortState.ascending,
                                            )
                                        } else {
                                            BlockLogSortState(
                                                selectedType = it,
                                                ascending = true,
                                            )
                                        }
                                    }
                                )
                            }
                        },
                    ),
                    TabLayoutContent(
                        tabContent = {
                            Text("Filter")
                        },
                        pageContent = {
                            BlockLogFilterType.entries.forEach {
                                FilterItem(
                                    label = stringResource(it.labelRes),
                                    mode = filterState.filters[it],
                                    onClick = {
                                        val newFilters = filterState.filters.toMutableMap()
                                        val currentState = filterState.filters[it]
                                        when (currentState) {
                                            FilterMode.Include ->
                                                newFilters[it] = FilterMode.Exclude

                                            FilterMode.Exclude -> newFilters.remove(it)
                                            null -> newFilters[it] = FilterMode.Include
                                        }
                                        filterState = BlockLogFilterState(newFilters)
                                    }
                                )
                            }
                        },
                    ),
                )
            )
        }
    }
}

enum class BlockLogSortType(@StringRes val labelRes: Int) {
    Attempts(R.string.attempts),
    LastConnected(R.string.last_connected),
    Alphabetical(R.string.alphabetical),
}

@Parcelize
data class BlockLogSortState(
    val selectedType: BlockLogSortType = BlockLogSortType.Attempts,
    val ascending: Boolean = true,
) : Parcelable

enum class BlockLogFilterType(@StringRes val labelRes: Int) {
    Blocked(R.string.blocked),
}

@Parcelize
data class BlockLogFilterState(
    val filters: Map<BlockLogFilterType, FilterMode> = emptyMap()
) : Parcelable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockLogScreen(
    modifier: Modifier = Modifier,
    onNavigateUp: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    loggedConnections: Map<String, LoggedConnection>,
    onCreateException: (LoggedConnectionState) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Box(modifier = Modifier.fillMaxSize()) {
        InsetScaffold(
            modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = {
                        Text(text = stringResource(id = R.string.block_log))
                    },
                    navigationIcon = {
                        BasicTooltipIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up),
                            onClick = onNavigateUp,
                        )
                    },
                    windowInsets = topAppBarInsets,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            BlockLog(
                contentPadding = contentPadding,
                listState = listState,
                loggedConnections = loggedConnections,
                onCreateException = onCreateException,
            )
        }

        val isAtTop by remember {
            derivedStateOf {
                listState.firstVisibleItemIndex != 0
            }
        }
        ScrollUpIndicator(
            visible = isAtTop,
            onClick = { listState.animateScrollToItem(0) },
        )
    }
}

@Preview
@Composable
fun BlockLogScreenPreview() {
    BlockLogScreen(
        onNavigateUp = {},
        loggedConnections = mapOf(
            "some.blocked.server" to LoggedConnection(false, 1, 0),
            "some.allowed.server" to LoggedConnection(false, 1, 0),
        ),
        onCreateException = {},
    )
}
