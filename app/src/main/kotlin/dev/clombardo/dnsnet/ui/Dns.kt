/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui

import android.os.Parcelable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.compose.ui.util.fastJoinToString
import dev.clombardo.dnsnet.DnsServer
import dev.clombardo.dnsnet.R
import dev.clombardo.dnsnet.ui.theme.DnsNetTheme
import dev.clombardo.dnsnet.ui.theme.ListPadding
import kotlinx.parcelize.Parcelize

@Composable
fun DnsScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    servers: List<DnsServer> = emptyList(),
    customDnsServers: Boolean,
    onCustomDnsServersClick: () -> Unit,
    onItemClick: (DnsServer) -> Unit,
    onItemCheckClicked: (DnsServer) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        state = listState,
    ) {
        item {
            ListSettingsContainer {
                SwitchListItem(
                    title = stringResource(R.string.custom_dns),
                    details = stringResource(R.string.dns_description),
                    checked = customDnsServers,
                    onCheckedChange = { onCustomDnsServersClick() },
                )
            }
            Spacer(modifier = Modifier.padding(vertical = 4.dp))
        }

        items(servers) {
            SplitCheckboxListItem(
                modifier = Modifier.animateItem(),
                title = it.title,
                details = it.addresses.replace(",", ", "),
                checked = it.enabled,
                onBodyClick = { onItemClick(it) },
                onCheckedChange = { _ -> onItemCheckClicked(it) },
            )
        }
    }
}

@Preview
@Composable
private fun DnsScreenPreview() {
    DnsNetTheme {
        val item = DnsServer()
        item.title = "Title"
        item.addresses = "213.73.91.35"
        DnsScreen(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            servers = listOf(item, item, item),
            onItemClick = {},
            customDnsServers = false,
            onCustomDnsServersClick = {},
            onItemCheckClicked = {},
        )
    }
}

@Composable
fun EditDns(
    modifier: Modifier = Modifier,
    titleText: String,
    titleTextError: Boolean,
    onTitleTextChanged: (String) -> Unit,
    addressState: List<AddressInputState>,
    onAddressTextChanged: (index: Int, location: String) -> Unit,
    onAddAddress: () -> Unit,
    onRemoveAddress: (Int) -> Unit,
    enabled: Boolean,
    onEnable: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    LazyColumn(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = contentPadding
    ) {
        item {
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
        }
        itemsIndexed(
            items = addressState,
            key = { i, _ -> i }
        ) { i, state ->
            OutlinedTextField(
                modifier = Modifier.animateItem().fillMaxWidth(),
                label = {
                    Text(text = stringResource(id = R.string.location_dns))
                },
                value = state.address,
                onValueChange = {
                    onAddressTextChanged(i, it)
                },
                isError = state.error,
                supportingText = {
                    if (state.error) {
                        Text(text = stringResource(R.string.input_blank_error))
                    }
                },
                trailingIcon = {
                    if (i > 0) {
                        TooltipIconButton(
                            painter = rememberVectorPainter(Icons.Default.Delete),
                            contentDescription = stringResource(R.string.action_delete),
                            onClick = { onRemoveAddress(i) },
                        )
                    }
                }
            )
        }
        item(key = "item") {
            FilledTonalButton(
                modifier = Modifier.animateItem(),
                onClick = onAddAddress,
            ) {
                Text(text = stringResource(R.string.add_address))
            }
            SwitchListItem(
                modifier = Modifier.animateItem(),
                title = stringResource(id = R.string.state_dns_enabled),
                checked = enabled,
                onCheckedChange = { onEnable() },
            )
        }
    }
}

@Preview
@Composable
private fun EditDnsPreview() {
    DnsNetTheme {
        EditDns(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            titleText = "Title",
            titleTextError = false,
            onTitleTextChanged = {},
            addressState = listOf(AddressInputState()),
            onAddressTextChanged = { _, _ -> },
            onAddAddress = {},
            onRemoveAddress = {},
            enabled = true,
            onEnable = {},
        )
    }
}

@Parcelize
data class AddressInputState(
    val address: String = "",
    val error: Boolean = false,
) : Parcelable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDnsScreen(
    modifier: Modifier = Modifier,
    server: DnsServer,
    onNavigateUp: () -> Unit,
    onSave: (DnsServer) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var titleInput by rememberSaveable { mutableStateOf(server.title) }
    var titleInputError by rememberSaveable { mutableStateOf(false) }
    var enabledInput by rememberSaveable { mutableStateOf(server.enabled) }
    val addressesState = rememberMutableStateListOf {
        val locations = server.getAddresses()
        if (locations.isEmpty()) {
            add(AddressInputState())
        } else {
            server.getAddresses().forEach {
                add(AddressInputState(address = it, error = false))
            }
        }
    }

    if (titleInput.isNotBlank()) {
        titleInputError = false
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    InsetScaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                windowInsets = topAppBarInsets,
                title = {
                    Text(
                        text = stringResource(
                            if (server.title.isBlank() && server.addresses.isBlank()) {
                                R.string.add_dns_server
                            } else {
                                R.string.activity_edit_dns_server
                            }
                        )
                    )
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
                            var locationInputError = false
                            addressesState.forEachIndexed { i, state ->
                                if (state.address.isBlank()) {
                                    locationInputError = true
                                    addressesState[i] = state.copy(error = true)
                                }
                            }
                            if (titleInputError || locationInputError) {
                                return@BasicTooltipIconButton
                            }

                            onSave(
                                DnsServer(
                                    titleInput,
                                    addressesState.fastJoinToString(separator = ",") { it.address },
                                    enabledInput
                                )
                            )
                        },
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        EditDns(
            titleText = titleInput,
            titleTextError = titleInputError,
            onTitleTextChanged = { titleInput = it },
            addressState = addressesState,
            onAddressTextChanged = { i, location ->
                addressesState[i] = AddressInputState(location)
            },
            onAddAddress = { addressesState.add(AddressInputState()) },
            onRemoveAddress = { addressesState.removeAt(it) },
            enabled = enabledInput,
            onEnable = { enabledInput = !enabledInput },
            contentPadding = contentPadding + PaddingValues(horizontal = ListPadding),
        )
    }
}

@Preview
@Composable
private fun EditDnsScreenPreview() {
    DnsNetTheme {
        EditDnsScreen(
            server = DnsServer("Title", "Location", true),
            onNavigateUp = {},
            onSave = {},
            onDelete = {},
        )
    }
}
