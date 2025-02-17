/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.clombardo.dnsnet.DnsNetApplication.Companion.applicationContext
import dev.clombardo.dnsnet.Host
import dev.clombardo.dnsnet.HostException
import dev.clombardo.dnsnet.HostFile
import dev.clombardo.dnsnet.HostState
import dev.clombardo.dnsnet.HostState.Companion.toHostState
import dev.clombardo.dnsnet.R
import dev.clombardo.dnsnet.ui.theme.Animation
import dev.clombardo.dnsnet.ui.theme.DnsNetTheme

object Hosts {
    val RefreshHostsButtonContainerAnimationSpec by lazy {
        tween<IntSize>(
            durationMillis = 1,
            easing = Animation.EmphasizedAccelerateEasing,
        )
    }
}

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

@Composable
fun HostsScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    refreshDaily: Boolean,
    onRefreshDailyClick: () -> Unit,
    hosts: List<Host>,
    onHostClick: (Host) -> Unit,
    onHostStateChanged: (Host) -> Unit,
    isRefreshingHosts: Boolean,
    onRefreshHosts: () -> Unit,
) {
    val itemStateStrings = stringArrayResource(R.array.item_states)
    val getStateString = { state: HostState ->
        when (state) {
            HostState.IGNORE -> itemStateStrings[2]
            HostState.DENY -> itemStateStrings[0]
            HostState.ALLOW -> itemStateStrings[1]
        }
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        state = listState,
    ) {
        item {
            ListSettingsContainer(title = stringResource(R.string.hosts_title)) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Spacer(Modifier.padding(top = 2.dp))
                    Text(
                        text = stringResource(id = R.string.legend_host_intro),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.padding(top = 2.dp))
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

                SwitchListItem(
                    title = stringResource(id = R.string.automatic_refresh),
                    details = stringResource(id = R.string.automatic_refresh_description),
                    checked = refreshDaily,
                    onCheckedChange = { onRefreshDailyClick() },
                )
            }

            Spacer(modifier = Modifier.padding(vertical = 4.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.animateContentSize(
                        animationSpec = Hosts.RefreshHostsButtonContainerAnimationSpec
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (!isRefreshingHosts) {
                                onRefreshHosts()
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.action_refresh))
                    }

                    AnimatedVisibility(
                        visible = isRefreshingHosts,
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

            Spacer(modifier = Modifier.padding(vertical = 4.dp))
        }

        items(hosts) {
            val iconResource = when (it.state) {
                HostState.DENY -> R.drawable.ic_state_deny
                HostState.ALLOW -> R.drawable.ic_state_allow
                else -> R.drawable.ic_state_ignore
            }

            SplitContentSetting(
                modifier = Modifier.animateItem(),
                onBodyClick = {
                    onHostClick(it)
                },
                title = it.title,
                details = it.data,
                endContent = {
                    val stateText = getStateString(it.state)
                    TooltipIconButton(
                        painter = painterResource(iconResource),
                        contentDescription = stateText,
                        onClick = { onHostStateChanged(it) },
                    )
                },
            )
        }
    }
}

@Preview
@Composable
private fun HostsScreenPreview() {
    val items = buildList {
        val item1 = HostFile()
        item1.title = "StevenBlack's hosts file"
        item1.data = "https://url.to.hosts.file.com/"
        item1.state = HostState.IGNORE
        add(item1)

        val item2 = HostFile()
        item2.title = "StevenBlack's hosts file"
        item2.data = "https://url.to.hosts.file.com/"
        item2.state = HostState.DENY
        add(item2)

        val item3 = HostFile()
        item3.title = "StevenBlack's hosts file"
        item3.data = "https://url.to.hosts.file.com/"
        item3.state = HostState.ALLOW
        add(item3)
    }

    DnsNetTheme {
        HostsScreen(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            refreshDaily = false,
            onRefreshDailyClick = {},
            hosts = items,
            onHostClick = {},
            onHostStateChanged = {},
            isRefreshingHosts = false,
            onRefreshHosts = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditHost(
    modifier: Modifier = Modifier,
    titleText: String,
    titleTextError: Boolean,
    onTitleTextChanged: (String) -> Unit,
    dataText: String,
    dataTextError: Boolean,
    onDataTextChanged: (String) -> Unit,
    onOpenHostsDirectoryClick: (() -> Unit)?,
    state: HostState,
    singleHost: Boolean,
    onStateChanged: (HostState) -> Unit,
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
                Text(text = stringResource(id = if (singleHost) R.string.hostname else R.string.location))
            },
            value = dataText,
            onValueChange = onDataTextChanged,
            trailingIcon = if (onOpenHostsDirectoryClick != null) {
                {
                    BasicTooltipIconButton(
                        icon = Icons.Default.AttachFile,
                        contentDescription = stringResource(R.string.action_use_file),
                        onClick = onOpenHostsDirectoryClick,
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
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                value = when (state) {
                    HostState.IGNORE -> itemStates[2]
                    HostState.DENY -> itemStates[0]
                    HostState.ALLOW -> itemStates[1]
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
                                    0 -> HostState.DENY
                                    1 -> HostState.ALLOW
                                    else -> HostState.IGNORE
                                }
                            )
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun EditHostPreview() {
    DnsNetTheme {
        var state by remember { mutableStateOf(HostState.IGNORE) }
        var titleText by remember { mutableStateOf("") }
        var locationText by remember { mutableStateOf("") }
        EditHost(
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
            onOpenHostsDirectoryClick = {},
            state = state,
            singleHost = true,
            onStateChanged = { state = it },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditHostScreen(
    modifier: Modifier = Modifier,
    host: Host,
    onNavigateUp: () -> Unit,
    onSave: (Host) -> Unit,
    onDelete: (() -> Unit)? = null,
    onUriPermissionAcquireFailed: (() -> Unit)? = null,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var titleInput by rememberSaveable { mutableStateOf(host.title) }
    var titleInputError by rememberSaveable { mutableStateOf(false) }
    var dataInput by rememberSaveable { mutableStateOf(host.data) }
    var dataInputError by rememberSaveable { mutableStateOf(false) }
    var stateInput by rememberSaveable { mutableStateOf(host.state) }

    if (titleInput.isNotBlank()) {
        titleInputError = false
    }
    if (dataInput.isNotBlank()) {
        dataInputError = false
    }

    val locationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it ?: return@rememberLauncherForActivityResult

            try {
                applicationContext.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                onUriPermissionAcquireFailed?.invoke()
                return@rememberLauncherForActivityResult
            }

            dataInput = it.toString()
        }

    InsetScaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                windowInsets = topAppBarInsets,
                title = {
                    val text = when (host) {
                        is HostFile -> {
                            if (host.data.isEmpty()) {
                                stringResource(R.string.add_hosts_file)
                            } else {
                                stringResource(R.string.edit_hosts_file)
                            }
                        }

                        is HostException -> {
                            if (host.title.isEmpty()) {
                                stringResource(R.string.add_host)
                            } else {
                                stringResource(R.string.edit_host)
                            }
                        }

                        else -> ""
                    }
                    Text(text = text)
                },
                navigationIcon = {
                    BasicTooltipIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.navigate_up),
                        onClick = onNavigateUp,
                    )
                },
                actions = {
                    if (onDelete != null) {
                        BasicTooltipIconButton(
                            icon = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            onClick = onDelete,
                        )
                    }

                    BasicTooltipIconButton(
                        icon = Icons.Default.Save,
                        contentDescription = stringResource(R.string.save),
                        onClick = {
                            titleInputError = titleInput.isBlank()
                            dataInputError = dataInput.isBlank()
                            if (titleInputError || dataInputError) {
                                return@BasicTooltipIconButton
                            }

                            when (host) {
                                is HostFile -> onSave(HostFile(titleInput, dataInput, stateInput))
                                is HostException ->
                                    onSave(HostException(titleInput, dataInput, stateInput))
                            }
                        },
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        EditHost(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            titleText = titleInput,
            titleTextError = titleInputError,
            onTitleTextChanged = { titleInput = it },
            dataText = dataInput,
            dataTextError = dataInputError,
            onDataTextChanged = { dataInput = it },
            onOpenHostsDirectoryClick = if (host is HostFile) {
                { locationLauncher.launch(arrayOf("*/*")) }
            } else {
                null
            },
            state = stateInput,
            singleHost = host is HostException,
            onStateChanged = { stateInput = it },
        )
    }
}

@Preview
@Composable
private fun EditHostScreenPreview() {
    DnsNetTheme {
        EditHostScreen(
            host = HostFile(),
            onNavigateUp = {},
            onSave = {},
            onDelete = {},
        )
    }
}
