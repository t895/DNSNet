/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.app

import android.os.Parcelable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastJoinToString
import dev.clombardo.dnsnet.settings.DnsServer
import dev.clombardo.dnsnet.ui.common.BasicTooltipButton
import dev.clombardo.dnsnet.ui.common.FloatingTopActions
import dev.clombardo.dnsnet.ui.common.InsetScaffold
import dev.clombardo.dnsnet.ui.common.ListSettingsContainer
import dev.clombardo.dnsnet.ui.common.ScreenTitle
import dev.clombardo.dnsnet.ui.common.SplitCheckboxListItem
import dev.clombardo.dnsnet.ui.common.SwitchListItem
import dev.clombardo.dnsnet.ui.common.TooltipIconButton
import dev.clombardo.dnsnet.ui.common.rememberAtTop
import dev.clombardo.dnsnet.ui.common.theme.DnsNetTheme
import dev.clombardo.dnsnet.ui.common.theme.ListPadding
import kotlinx.parcelize.Parcelize

@Composable
fun DnsScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    servers: List<DnsServer> = emptyList(),
    customDnsServers: Boolean,
    onCustomDnsServersClick: () -> Unit,
    useNetworkDnsServers: Boolean,
    onUseNetworkDnsServersClick: () -> Unit,
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
                item {
                    SwitchListItem(
                        title = stringResource(R.string.custom_dns),
                        details = stringResource(R.string.dns_description),
                        checked = customDnsServers,
                        onCheckedChange = { onCustomDnsServersClick() },
                    )
                }

                item {
                    val allServersDisabled by remember {
                        derivedStateOf { servers.all { !it.enabled } }
                    }
                    SwitchListItem(
                        enabled = customDnsServers && !allServersDisabled,
                        title = stringResource(R.string.use_dns_servers_from_active_network),
                        details = stringResource(R.string.use_dns_servers_from_active_network_description),
                        checked = useNetworkDnsServers || !customDnsServers || allServersDisabled,
                        onCheckedChange = { onUseNetworkDnsServersClick() },
                    )
                }
            }
            Spacer(modifier = Modifier.padding(vertical = 4.dp))
        }

        items(servers) {
            SplitCheckboxListItem(
                modifier = Modifier.animateItem(),
                title = it.title,
                details = it.addresses.replace(",", ", "),
                checked = it.enabled,
                clip = true,
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
            useNetworkDnsServers = false,
            onUseNetworkDnsServersClick = {},
            onItemCheckClicked = {},
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
                                var locationInputError = false
                                addressesState.forEachIndexed { i, state ->
                                    if (state.address.isBlank()) {
                                        locationInputError = true
                                        addressesState[i] = state.copy(error = true)
                                    }
                                }
                                if (titleInputError || locationInputError) {
                                    return@BasicTooltipButton
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
                    }
                },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ListPadding),
            state = state,
            contentPadding = contentPadding,
        ) {
            item(key = "title") {
                ScreenTitle(
                    modifier = Modifier.animateItem(),
                    text = stringResource(
                        if (server.title.isBlank() && server.addresses.isBlank()) {
                            R.string.add_dns_server
                        } else {
                            R.string.activity_edit_dns_server
                        }
                    )
                )
            }

            item(key = "title-input") {
                OutlinedTextField(
                    modifier = Modifier
                        .animateItem()
                        .fillMaxWidth(),
                    label = {
                        Text(text = stringResource(id = R.string.title))
                    },
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    isError = titleInputError,
                    supportingText = {
                        if (titleInputError) {
                            Text(text = stringResource(R.string.input_blank_error))
                        }
                    },
                )
            }

            itemsIndexed(
                items = addressesState,
                key = { i, _ -> i }
            ) { i, state ->
                OutlinedTextField(
                    modifier = Modifier
                        .animateItem()
                        .fillMaxWidth(),
                    label = {
                        Text(text = stringResource(id = R.string.location_dns))
                    },
                    value = state.address,
                    onValueChange = {
                        addressesState[i] = AddressInputState(it)
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
                                onClick = { addressesState.removeAt(i) },
                            )
                        }
                    }
                )
            }

            item(key = "item") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem(),
                    contentAlignment = Alignment.Center,
                ) {
                    FilledTonalButton(
                        onClick = { addressesState.add(AddressInputState()) },
                    ) {
                        Text(text = stringResource(R.string.add_address))
                    }
                }
                SwitchListItem(
                    modifier = Modifier.animateItem(),
                    title = stringResource(id = R.string.state_dns_enabled),
                    checked = enabledInput,
                    clip = true,
                    onCheckedChange = { enabledInput = !enabledInput },
                )
            }
        }
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
