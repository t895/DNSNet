/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.app

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Filter1
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.clombardo.dnsnet.settings.Filter
import dev.clombardo.dnsnet.settings.SingleFilter
import dev.clombardo.dnsnet.settings.FilterFile
import dev.clombardo.dnsnet.settings.FilterState
import dev.clombardo.dnsnet.ui.common.BasicTooltipButton
import dev.clombardo.dnsnet.ui.common.BasicTooltipIconButton
import dev.clombardo.dnsnet.ui.common.FloatingTopActions
import dev.clombardo.dnsnet.ui.common.InsetScaffold
import dev.clombardo.dnsnet.ui.common.ListSettingsContainer
import dev.clombardo.dnsnet.ui.common.ScreenTitle
import dev.clombardo.dnsnet.ui.common.SplitContentSetting
import dev.clombardo.dnsnet.ui.common.SwitchListItem
import dev.clombardo.dnsnet.ui.common.TooltipIconButton
import dev.clombardo.dnsnet.ui.common.plus
import dev.clombardo.dnsnet.ui.common.rememberAtTop
import dev.clombardo.dnsnet.ui.common.theme.Animation
import dev.clombardo.dnsnet.ui.common.theme.DefaultFabSize
import dev.clombardo.dnsnet.ui.common.theme.DnsNetTheme
import dev.clombardo.dnsnet.ui.common.theme.FabPadding
import dev.clombardo.dnsnet.ui.common.theme.ListPadding

@Composable
private fun IconText(
    modifier: Modifier = Modifier,
    icon: Painter,
    text: String,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = icon,
            contentDescription = text,
        )
        Spacer(modifier = Modifier.padding(vertical = 2.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FiltersScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    refreshDaily: Boolean,
    onRefreshDailyClick: () -> Unit,
    filterFiles: List<FilterFile>,
    singleFilters: List<SingleFilter>,
    onFilterClick: (Filter) -> Unit,
    onFilterStateChanged: (Filter) -> Unit,
    isRefreshingFilters: Boolean,
    onRefreshFilters: () -> Unit,
    onOpenPresets: () -> Unit,
) {
    val itemStateStrings = stringArrayResource(R.array.item_states)
    val getStateString = { state: FilterState ->
        when (state) {
            FilterState.IGNORE -> itemStateStrings[2]
            FilterState.DENY -> itemStateStrings[0]
            FilterState.ALLOW -> itemStateStrings[1]
        }
    }

    val filters by remember(filterFiles, singleFilters) {
        derivedStateOf {
            filterFiles + singleFilters
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding + PaddingValues(ListPadding) +
                PaddingValues(bottom = DefaultFabSize + FabPadding),
        state = listState,
    ) {
        item {
            ListSettingsContainer {
                item {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Spacer(Modifier.padding(top = 2.dp))
                        Text(
                            text = stringResource(id = R.string.legend_host_intro),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.padding(top = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconText(
                                icon = painterResource(id = R.drawable.ic_state_ignore),
                                text = stringResource(id = R.string.ignore),
                            )
                            Spacer(Modifier.padding(horizontal = 8.dp))
                            IconText(
                                icon = painterResource(id = R.drawable.ic_state_allow),
                                text = stringResource(id = R.string.allow),
                            )
                            Spacer(Modifier.padding(horizontal = 8.dp))
                            IconText(
                                icon = painterResource(id = R.drawable.ic_state_deny),
                                text = stringResource(id = R.string.deny),
                            )
                        }
                    }
                }

                item {
                    SwitchListItem(
                        title = stringResource(id = R.string.automatic_refresh),
                        details = stringResource(id = R.string.automatic_refresh_description),
                        checked = refreshDaily,
                        onCheckedChange = { onRefreshDailyClick() },
                    )
                }
            }

            Spacer(modifier = Modifier.padding(vertical = 4.dp))

            if (filters.isNotEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier.animateContentSize(
                            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            onClick = {
                                if (!isRefreshingFilters) {
                                    onRefreshFilters()
                                }
                            },
                        ) {
                            Text(text = stringResource(R.string.action_refresh))
                        }

                        AnimatedVisibility(
                            visible = isRefreshingFilters,
                            enter = Animation.ShowSpinnerHorizontal,
                            exit = Animation.HideSpinnerHorizontal,
                        ) {
                            Row(
                                modifier = Modifier.wrapContentSize(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.padding(vertical = 4.dp))
        }

        items(filters) {
            val iconResource = when (it.state) {
                FilterState.DENY -> R.drawable.ic_state_deny
                FilterState.ALLOW -> R.drawable.ic_state_allow
                else -> R.drawable.ic_state_ignore
            }

            SplitContentSetting(
                modifier = Modifier.animateItem(),
                onBodyClick = {
                    onFilterClick(it)
                },
                title = it.title,
                details = it.data,
                clip = true,
                startContent = {
                    val icon = when (it) {
                        is FilterFile -> Icons.Default.Filter1
                        is SingleFilter -> Icons.AutoMirrored.Default.InsertDriveFile
                    }
                    Icon(
                        painter = rememberVectorPainter(icon),
                        contentDescription = null,
                    )
                },
                endContent = {
                    val stateText = getStateString(it.state)
                    TooltipIconButton(
                        painter = painterResource(iconResource),
                        contentDescription = stateText,
                        onClick = { onFilterStateChanged(it) },
                    )
                },
            )
        }

        if (filters.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.no_block_items),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Button(
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        onClick = onOpenPresets,
                    ) {
                        Text(text = stringResource(R.string.presets))
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun FiltersScreenPreview() {
    val filterFiles = buildList {
        val item1 = FilterFile()
        item1.title = "StevenBlack's hosts file"
        item1.data = "https://url.to.hosts.file.com/"
        item1.state = FilterState.IGNORE
        add(item1)

        val item2 = FilterFile()
        item2.title = "StevenBlack's hosts file"
        item2.data = "https://url.to.hosts.file.com/"
        item2.state = FilterState.DENY
        add(item2)

        val item3 = FilterFile()
        item3.title = "StevenBlack's hosts file"
        item3.data = "https://url.to.hosts.file.com/"
        item3.state = FilterState.ALLOW
        add(item3)
    }

    val singleFilters = buildList {
        val item1 = SingleFilter()
        item1.title = "StevenBlack's hosts file"
        item1.data = "https://url.to.hosts.file.com/"
        item1.state = FilterState.IGNORE
        add(item1)

        val item2 = SingleFilter()
        item2.title = "StevenBlack's hosts file"
        item2.data = "https://url.to.hosts.file.com/"
        item2.state = FilterState.DENY
        add(item2)

        val item3 = SingleFilter()
        item3.title = "StevenBlack's hosts file"
        item3.data = "https://url.to.hosts.file.com/"
        item3.state = FilterState.ALLOW
        add(item3)
    }

    var isRefreshingFilters by remember { mutableStateOf(false) }
    DnsNetTheme {
        FiltersScreen(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            refreshDaily = false,
            onRefreshDailyClick = {},
            filterFiles = filterFiles,
            singleFilters = singleFilters,
            onFilterClick = {},
            onFilterStateChanged = {},
            isRefreshingFilters = isRefreshingFilters,
            onRefreshFilters = { isRefreshingFilters = !isRefreshingFilters },
            onOpenPresets = {},
        )
    }
}

@Preview
@Composable
private fun FiltersScreenNoBlockItemsPreview() {
    var isRefreshingFilterFiles by remember { mutableStateOf(false) }
    DnsNetTheme {
        FiltersScreen(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            refreshDaily = false,
            onRefreshDailyClick = {},
            filterFiles = listOf(),
            singleFilters = listOf(),
            onFilterClick = {},
            onFilterStateChanged = {},
            isRefreshingFilters = isRefreshingFilterFiles,
            onRefreshFilters = { isRefreshingFilterFiles = !isRefreshingFilterFiles },
            onOpenPresets = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditFilter(
    modifier: Modifier = Modifier,
    titleText: String,
    titleTextError: Boolean,
    onTitleTextChanged: (String) -> Unit,
    dataText: String,
    dataTextError: Boolean,
    onDataTextChanged: (String) -> Unit,
    onOpenFilterFileClick: (() -> Unit)?,
    state: FilterState,
    singleFilter: Boolean,
    onStateChanged: (FilterState) -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(text = stringResource(id = R.string.title))
            },
            value = titleText,
            onValueChange = onTitleTextChanged,
            isError = titleTextError,
            supportingText = {
                if (titleTextError) {
                    Text(text = stringResource(R.string.input_blank_error))
                }
            },
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(text = stringResource(id = if (singleFilter) R.string.filter_field_hint else R.string.location))
            },
            value = dataText,
            onValueChange = onDataTextChanged,
            trailingIcon = if (onOpenFilterFileClick != null) {
                {
                    BasicTooltipIconButton(
                        icon = Icons.Default.AttachFile,
                        contentDescription = stringResource(R.string.action_use_file),
                        onClick = onOpenFilterFileClick,
                    )
                }
            } else {
                null
            },
            isError = dataTextError,
            supportingText = {
                if (dataTextError) {
                    Text(text = stringResource(R.string.input_blank_error))
                }
            },
        )

        val itemStates = stringArrayResource(id = R.array.item_states)
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                // The `menuAnchor` modifier must be passed to the text field to handle
                // expanding/collapsing the menu on click. A read-only text field has
                // the anchor type `PrimaryNotEditable`.
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                value = when (state) {
                    FilterState.IGNORE -> itemStates[2]
                    FilterState.DENY -> itemStates[0]
                    FilterState.ALLOW -> itemStates[1]
                },
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(text = stringResource(id = R.string.action)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                itemStates.forEachIndexed { index, s ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = s,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        onClick = {
                            expanded = false
                            onStateChanged(
                                when (index) {
                                    0 -> FilterState.DENY
                                    1 -> FilterState.ALLOW
                                    else -> FilterState.IGNORE
                                }
                            )
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.padding(vertical = 8.dp))
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Icon(
                painter = rememberVectorPainter(Icons.Default.Info),
                contentDescription = null,
            )
            Text(stringResource(R.string.supported_formats))
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.labelMedium
            ) {
                Text(stringResource(R.string.supported_formats_host_name))
                Text(stringResource(R.string.supported_formats_wildcard))
                Text(stringResource(R.string.supported_formats_adblock_plus))
            }
        }
    }
}

@Preview
@Composable
private fun EditFilterPreview() {
    DnsNetTheme {
        var state by remember { mutableStateOf(FilterState.IGNORE) }
        var titleText by remember { mutableStateOf("") }
        var locationText by remember { mutableStateOf("") }
        EditFilter(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .fillMaxWidth()
                .padding(20.dp),
            titleText = titleText,
            titleTextError = false,
            onTitleTextChanged = { titleText = it },
            dataText = locationText,
            dataTextError = false,
            onDataTextChanged = { locationText = it },
            onOpenFilterFileClick = {},
            state = state,
            singleFilter = true,
            onStateChanged = { state = it },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditFilterScreen(
    modifier: Modifier = Modifier,
    filter: Filter,
    onNavigateUp: () -> Unit,
    onSave: (Filter) -> Unit,
    onDelete: (() -> Unit)? = null,
    onUriPermissionAcquireFailed: (() -> Unit)? = null,
) {
    var titleInput by rememberSaveable { mutableStateOf(filter.title) }
    var titleInputError by rememberSaveable { mutableStateOf(false) }
    var dataInput by rememberSaveable { mutableStateOf(filter.data) }
    var dataInputError by rememberSaveable { mutableStateOf(false) }
    var stateInput by rememberSaveable { mutableStateOf(filter.state) }

    if (titleInput.isNotBlank()) {
        titleInputError = false
    }
    if (dataInput.isNotBlank()) {
        dataInputError = false
    }

    val context = LocalContext.current
    val locationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it ?: return@rememberLauncherForActivityResult

            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                onUriPermissionAcquireFailed?.invoke()
                return@rememberLauncherForActivityResult
            }

            dataInput = it.toString()
        }

    val state = rememberLazyListState()
    InsetScaffold(
        modifier = modifier,
        topBar = {
            val isAtTop by rememberAtTop(state)
            FloatingTopActions(
                elevated = !isAtTop,
                navigationIcon = {
                    BasicTooltipButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.navigate_up),
                        onClick = onNavigateUp,
                    )
                },
                actions = {
                    if (onDelete != null) {
                        item {
                            BasicTooltipButton(
                                icon = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                onClick = onDelete,
                            )
                        }
                    }

                    item {
                        BasicTooltipButton(
                            icon = Icons.Default.Save,
                            contentDescription = stringResource(R.string.save),
                            onClick = {
                                titleInputError = titleInput.isBlank()
                                dataInputError = dataInput.isBlank()
                                if (titleInputError || dataInputError) {
                                    return@BasicTooltipButton
                                }

                                when (filter) {
                                    is FilterFile -> onSave(
                                        FilterFile(
                                            titleInput,
                                            dataInput,
                                            stateInput
                                        )
                                    )

                                    is SingleFilter ->
                                        onSave(SingleFilter(titleInput, dataInput, stateInput))
                                }
                            },
                        )
                    }
                }
            )
        },
    ) { contentPadding ->
        LazyColumn(
            state = state,
            contentPadding = contentPadding,
        ) {
            item {
                val text = when (filter) {
                    is FilterFile -> {
                        if (filter.data.isEmpty()) {
                            stringResource(R.string.add_filter_file)
                        } else {
                            stringResource(R.string.edit_filter_file)
                        }
                    }

                    is SingleFilter -> {
                        if (filter.title.isEmpty()) {
                            stringResource(R.string.add_filter)
                        } else {
                            stringResource(R.string.edit_filter)
                        }
                    }
                }
                ScreenTitle(text = text)
            }

            item {
                EditFilter(
                    modifier = Modifier.padding(horizontal = ListPadding),
                    titleText = titleInput,
                    titleTextError = titleInputError,
                    onTitleTextChanged = { titleInput = it },
                    dataText = dataInput,
                    dataTextError = dataInputError,
                    onDataTextChanged = { dataInput = it },
                    onOpenFilterFileClick = if (filter is FilterFile) {
                        { locationLauncher.launch(arrayOf("*/*")) }
                    } else {
                        null
                    },
                    state = stateInput,
                    singleFilter = filter is SingleFilter,
                    onStateChanged = { stateInput = it },
                )
            }
        }
    }
}

@Preview
@Composable
private fun EditFilterScreenPreview() {
    DnsNetTheme {
        EditFilterScreen(
            filter = FilterFile(),
            onNavigateUp = {},
            onSave = {},
            onDelete = {},
        )
    }
}
