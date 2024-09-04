package org.jak_linux.dns66.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import org.jak_linux.dns66.Configuration
import org.jak_linux.dns66.R
import org.jak_linux.dns66.ui.theme.Dns66Theme

@Composable
private fun IconText(
    modifier: Modifier = Modifier,
    icon: Painter,
    text: String,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(painter = icon, contentDescription = null)
        Spacer(modifier = Modifier.padding(horizontal = 2.dp))
        Text(text = text)
    }
}

// TODO: Viewmodel hookup
@Composable
fun HostsScreen(
    modifier: Modifier = Modifier,
    hosts: List<Configuration.Item>,
    onItemClick: (Configuration.Item) -> Unit,
    onItemStateChanged: () -> Unit,
) {
    LazyColumn(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp),
    ) {
        item {
            ListSettingsContainer {
                SwitchListItem(
                    title = stringResource(id = R.string.enable_hosts),
                    onCheckedChange = {},
                    onClick = {},
                )

                Column(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = stringResource(id = R.string.legend_host_intro))
                    IconText(
                        icon = painterResource(id = R.drawable.ic_state_ignore),
                        text = stringResource(id = R.string.legend_host_ignore),
                    )
                    IconText(
                        icon = painterResource(id = R.drawable.ic_state_allow),
                        text = stringResource(id = R.string.legend_host_allow),
                    )
                    IconText(
                        icon = painterResource(id = R.drawable.ic_state_deny),
                        text = stringResource(id = R.string.legend_host_deny),
                    )
                }

                Spacer(modifier = Modifier.padding(vertical = 2.dp))

                SwitchListItem(
                    title = stringResource(id = R.string.automatic_refresh),
                    details = stringResource(id = R.string.automatic_refresh_description),
                    onCheckedChange = {},
                    onClick = {},
                )
            }
            Spacer(modifier = Modifier.padding(vertical = 4.dp))
        }

        items(hosts) {
            val iconResource = when (it.state) {
                Configuration.Item.STATE_DENY -> R.drawable.ic_state_deny
                Configuration.Item.STATE_ALLOW -> R.drawable.ic_state_allow
                else -> R.drawable.ic_state_ignore
            }

            IconListItem(
                onClick = {
                    onItemClick(it)
                },
                title = it.title,
                details = it.location,
                iconContent = {
                    IconButton(onClick = onItemStateChanged) {
                        Icon(painterResource(iconResource), null)
                    }
                },
            )
        }
    }
}

@Preview
@Composable
private fun HostsScreenPreview() {
    val items = buildList {
        val item1 = Configuration.Item()
        item1.title = "StevenBlack's hosts file"
        item1.location = "https://url.to.hosts.file.com/"
        item1.state = 0
        add(item1)

        val item2 = Configuration.Item()
        item2.title = "StevenBlack's hosts file"
        item2.location = "https://url.to.hosts.file.com/"
        item2.state = 1
        add(item2)

        val item3 = Configuration.Item()
        item3.title = "StevenBlack's hosts file"
        item3.location = "https://url.to.hosts.file.com/"
        item3.state = 2
        add(item3)
    }

    Dns66Theme {
        HostsScreen(
            hosts = items,
            onItemClick = {},
            onItemStateChanged = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditFilter(
    modifier: Modifier = Modifier,
    titleText: String,
    onTitleTextChanged: (String) -> Unit,
    locationText: String,
    onLocationTextChanged: (String) -> Unit,
    onOpenHostsDirectoryClick: () -> Unit,
    action: Int,
    onActionChanged: (Int) -> Unit,
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
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(text = stringResource(id = R.string.location))
            },
            value = locationText,
            onValueChange = onLocationTextChanged,
            trailingIcon = {
                IconButton(onClick = onOpenHostsDirectoryClick) {
                    Icon(imageVector = Icons.Default.AttachFile, contentDescription = null)
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
                value = itemStates[action],
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
                            onActionChanged(index)
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
private fun EditFilterPreview() {
    Dns66Theme {
        var action by remember { mutableIntStateOf(0) }
        var titleText by remember { mutableStateOf("") }
        var locationText by remember { mutableStateOf("") }
        EditFilter(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .fillMaxWidth()
                .padding(20.dp),
            titleText = titleText,
            onTitleTextChanged = { titleText = it },
            locationText = locationText,
            onLocationTextChanged = { locationText = it },
            onOpenHostsDirectoryClick = {},
            action = action,
            onActionChanged = { action = it },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditFilterScreen(
    modifier: Modifier = Modifier,
    title: String,
    location: String,
    action: Int,
    onNavigateUp: () -> Unit,
    onSave: (title: String, location: String, action: Int) -> Unit,
    onDelete: (() -> Unit)? = null,
    onOpenUri: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var titleInput by rememberSaveable { mutableStateOf(title) }
    var locationInput by rememberSaveable { mutableStateOf(location) }
    var actionInput by rememberSaveable { mutableIntStateOf(action) }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.activity_edit_filter))
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                            )
                        }
                    }

                    IconButton(onClick = { onSave(titleInput, locationInput, actionInput) }) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        EditFilter(
            modifier = Modifier.padding(paddingValues).padding(horizontal = 16.dp),
            titleText = titleInput,
            onTitleTextChanged = { titleInput = it },
            locationText = locationInput,
            onLocationTextChanged = { locationInput = it },
            onOpenHostsDirectoryClick = onOpenUri,
            action = actionInput,
            onActionChanged = { actionInput = it },
        )
    }
}

@Preview
@Composable
private fun EditFilterScreenPreview() {
    Dns66Theme {
        EditFilterScreen(
            title = "",
            location = "",
            action = 0,
            onNavigateUp = {},
            onSave = { _, _, _ -> },
            onDelete = {},
            onOpenUri = {},
        )
    }
}
