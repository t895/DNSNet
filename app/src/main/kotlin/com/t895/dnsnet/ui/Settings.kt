/* Copyright (C) 2024 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.t895.dnsnet.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.t895.dnsnet.R
import com.t895.dnsnet.ui.theme.DnsNetTheme
import com.t895.materialswitch.MaterialSwitch

private val innerHorizontalPadding = 8.dp
private val clickablePadding = 8.dp

@Composable
private fun Modifier.roundedClickable(
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
    role: Role,
    onClick: () -> Unit,
) = this
    .clip(CardDefaults.shape)
    .clickable(
        enabled = enabled,
        onClick = onClick,
        interactionSource = interactionSource,
        indication = ripple(),
        role = role,
    )
    .padding(clickablePadding)

@Composable
private fun Modifier.roundedToggleable(
    value: Boolean,
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
    role: Role,
    onValueChange: (Boolean) -> Unit,
) = this
    .clip(CardDefaults.shape)
    .toggleable(
        value = value,
        enabled = enabled,
        onValueChange = onValueChange,
        interactionSource = interactionSource,
        indication = ripple(),
        role = role,
    )
    .padding(clickablePadding)

@Composable
fun SettingInfo(
    modifier: Modifier = Modifier,
    title: String,
    details: String = "",
    maxDetailLines: Int = Int.MAX_VALUE,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (details.isNotEmpty()) {
            Spacer(modifier = Modifier.padding(vertical = 1.dp))
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                maxLines = maxDetailLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StartContentContainer(
    modifier: Modifier = Modifier,
    startContent: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .size(56.dp),
        contentAlignment = Alignment.Center,
        content = startContent,
    )
}

@Composable
private fun ContentSetting(
    modifier: Modifier = Modifier,
    title: String = "",
    details: String = "",
    startContent: @Composable (BoxScope.() -> Unit)? = null,
    endContent: @Composable (BoxScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (startContent != null) {
            StartContentContainer(
                modifier = Modifier.minimumInteractiveComponentSize(),
                startContent = startContent,
            )
            Spacer(modifier = Modifier.padding(horizontal = innerHorizontalPadding))
        }

        SettingInfo(
            modifier = Modifier.weight(1f),
            title = title,
            details = details,
        )

        if (endContent != null) {
            Spacer(modifier = Modifier.padding(horizontal = innerHorizontalPadding))
            Box(
                modifier = Modifier.minimumInteractiveComponentSize(),
                contentAlignment = Alignment.Center,
                content = endContent,
            )
        }
    }
}

@Composable
fun SplitContentSetting(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    title: String = "",
    details: String = "",
    onBodyClick: () -> Unit,
    interactionSource: MutableInteractionSource? = remember { MutableInteractionSource() },
    startContent: @Composable (BoxScope.() -> Unit)? = null,
    endContent: @Composable BoxScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .roundedClickable(
                    enabled = enabled,
                    onClick = onBodyClick,
                    interactionSource = interactionSource,
                    role = Role.Button,
                )
                .weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (startContent != null) {
                StartContentContainer(
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    startContent = startContent,
                )
                Spacer(modifier = Modifier.padding(horizontal = innerHorizontalPadding))
            }

            SettingInfo(
                modifier = Modifier.weight(1f),
                title = title,
                details = details,
                maxDetailLines = 1,
            )
        }
        Spacer(modifier = Modifier.padding(horizontal = innerHorizontalPadding / 2))
        VerticalDivider(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(modifier = Modifier.padding(horizontal = innerHorizontalPadding))
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .padding(end = clickablePadding),
            contentAlignment = Alignment.Center,
            content = endContent,
        )
    }
}

@Composable
private fun ClickableSetting(
    title: String,
    role: Role,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    details: String = "",
    sharedInteractionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
    startContent: @Composable (BoxScope.() -> Unit)? = null,
    endContent: @Composable BoxScope.() -> Unit,
) {
    ContentSetting(
        modifier = modifier
            .roundedClickable(
                enabled = enabled,
                onClick = onClick,
                interactionSource = sharedInteractionSource,
                role = role,
            ),
        title = title,
        details = details,
        startContent = startContent,
        endContent = endContent,
    )
}

@Composable
private fun ToggleableSetting(
    title: String,
    role: Role,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    details: String = "",
    sharedInteractionSource: MutableInteractionSource? = null,
    onCheckedChange: (Boolean) -> Unit,
    startContent: @Composable (BoxScope.() -> Unit)? = null,
    toggleableContent: @Composable BoxScope.() -> Unit,
) {
    ContentSetting(
        modifier = modifier
            .roundedToggleable(
                value = checked,
                enabled = enabled,
                onValueChange = onCheckedChange,
                interactionSource = sharedInteractionSource,
                role = role,
            ),
        title = title,
        details = details,
        startContent = startContent,
        endContent = toggleableContent,
    )
}

@Composable
fun CheckboxListItem(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    details: String = "",
    onCheckedChange: (Boolean) -> Unit,
    startContent: @Composable (BoxScope.() -> Unit)? = null,
) {
    val sharedInteractionSource = remember { MutableInteractionSource() }
    ToggleableSetting(
        title = title,
        role = Role.Checkbox,
        checked = checked,
        modifier = modifier,
        enabled = enabled,
        details = details,
        sharedInteractionSource = sharedInteractionSource,
        onCheckedChange = onCheckedChange,
        startContent = startContent,
    ) {
        Checkbox(
            enabled = enabled,
            checked = checked,
            onCheckedChange = null,
            interactionSource = sharedInteractionSource,
        )
    }
}

@Preview
@Composable
private fun CheckboxListItemPreview() {
    DnsNetTheme {
        var checked by remember { mutableStateOf(false) }
        CheckboxListItem(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            checked = checked,
            title = "Chaos Computer Club",
            details = "213.73.91.35",
            onCheckedChange = { checked = !checked },
        )
    }
}

@Composable
fun SplitCheckboxListItem(
    checked: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    bodyEnabled: Boolean = true,
    checkboxEnabled: Boolean = true,
    details: String = "",
    onBodyClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    startContent: @Composable (BoxScope.() -> Unit)? = null,
) {
    SplitContentSetting(
        modifier = modifier,
        title = title,
        details = details,
        onBodyClick = onBodyClick,
        enabled = bodyEnabled,
        startContent = startContent,
        endContent = {
            Checkbox(
                enabled = checkboxEnabled,
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}

@Preview
@Composable
private fun SplitCheckboxListItemPreview() {
    DnsNetTheme {
        SplitCheckboxListItem(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            checked = true,
            title = "Title",
            details = "Details",
            onBodyClick = {},
            onCheckedChange = {},
        )
    }
}

@Composable
fun SwitchListItem(
    checked: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    details: String = "",
    onCheckedChange: (Boolean) -> Unit,
    startContent: @Composable (BoxScope.() -> Unit)? = null
) {
    val sharedInteractionSource = remember { MutableInteractionSource() }
    ToggleableSetting(
        modifier = modifier,
        checked = checked,
        enabled = enabled,
        title = title,
        role = Role.Switch,
        details = details,
        onCheckedChange = onCheckedChange,
        sharedInteractionSource = sharedInteractionSource,
        startContent = startContent,
        toggleableContent = {
            MaterialSwitch(
                enabled = enabled,
                checked = checked,
                onCheckedChange = null,
                interactionSource = sharedInteractionSource,
            )
        },
    )
}

@Preview
@Composable
private fun SwitchListItemPreview() {
    DnsNetTheme {
        var checked by remember { mutableStateOf(false) }
        SwitchListItem(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            checked = checked,
            title = "Chaos Computer Club",
            details = "213.73.91.35",
            onCheckedChange = { checked = !checked },
        )
    }
}

@Composable
fun SplitSwitchListItem(
    checked: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    bodyEnabled: Boolean = true,
    switchEnabled: Boolean = true,
    details: String = "",
    onBodyClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    startContent: @Composable (BoxScope.() -> Unit)? = null
) {
    SplitContentSetting(
        modifier = modifier,
        title = title,
        details = details,
        onBodyClick = onBodyClick,
        enabled = bodyEnabled,
        startContent = startContent,
        endContent = {
            MaterialSwitch(
                enabled = switchEnabled,
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}

@Preview
@Composable
private fun SplitSwitchListItemPreview() {
    DnsNetTheme {
        SplitSwitchListItem(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            checked = true,
            title = "Title",
            details = "Details",
            onBodyClick = {},
            onCheckedChange = {},
        )
    }
}

@Composable
fun IconListItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    details: String = "",
    interactionSource: MutableInteractionSource? = remember { MutableInteractionSource() },
    iconContent: @Composable BoxScope.() -> Unit,
) {
    ClickableSetting(
        title = title,
        role = Role.Button,
        modifier = modifier,
        enabled = enabled,
        details = details,
        sharedInteractionSource = interactionSource,
        onClick = onClick,
        endContent = iconContent,
    )
}

@Preview
@Composable
private fun IconListItemPreview() {
    DnsNetTheme {
        IconListItem(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            onClick = {},
            title = "Chaos Computer Club",
            details = "213.73.91.35",
            iconContent = {
                IconButton(onClick = {}) {
                    Icon(Icons.Default.MoreVert, null)
                }
            }
        )
    }
}

@Composable
fun ExpandableOptionsItem(
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    enabled: Boolean = true,
    title: String = "",
    details: String = "",
    sharedInteractionSource: MutableInteractionSource? = null,
    onExpandClick: () -> Unit,
    options: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        ClickableSetting(
            title = title,
            role = Role.DropdownList,
            details = details,
            enabled = enabled,
            onClick = onExpandClick,
            sharedInteractionSource = sharedInteractionSource,
        ) {
            IconButton(
                enabled = enabled,
                onClick = onExpandClick,
                interactionSource = sharedInteractionSource,
            ) {
                val iconRotation by animateFloatAsState(
                    targetValue = if (expanded) 0f else 90f,
                    label = "iconRotation",
                )
                Icon(
                    modifier = Modifier.rotate(iconRotation),
                    painter = rememberVectorPainter(Icons.Default.KeyboardArrowDown),
                    contentDescription = if (expanded) {
                        stringResource(R.string.collapse)
                    } else {
                        stringResource(R.string.expand)
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top),
            exit = shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            Column(
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = options,
            )
        }
    }
}

@Preview
@Composable
private fun ExpandableOptionsItemPreview() {
    DnsNetTheme {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
            var expanded by remember { mutableStateOf(false) }
            ExpandableOptionsItem(
                expanded = expanded,
                title = "Title",
                details = "Details",
                onExpandClick = { expanded = !expanded },
            ) {
                SettingInfo(title = "Option1")
                SettingInfo(title = "Option2")
                SettingInfo(title = "Option3")
            }
        }
    }
}

@Composable
fun RadioListItem(
    modifier: Modifier = Modifier,
    checked: Boolean,
    enabled: Boolean = true,
    title: String = "",
    details: String = "",
    sharedInteractionSource: MutableInteractionSource? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    ToggleableSetting(
        modifier = modifier,
        checked = checked,
        role = Role.RadioButton,
        enabled = enabled,
        title = title,
        details = details,
        onCheckedChange = onCheckedChange,
        sharedInteractionSource = sharedInteractionSource,
        toggleableContent = {
            RadioButton(
                selected = checked,
                onClick = null,
                interactionSource = sharedInteractionSource,
            )
        },
    )
}

@Preview
@Composable
private fun RadioListItemPreview() {
    DnsNetTheme {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
            var selected by remember { mutableStateOf(false) }
            RadioListItem(
                title = "Title",
                details = "Details",
                checked = selected,
                onCheckedChange = { selected = !selected },
            )
        }
    }
}

@Composable
fun ListSettingsContainer(
    modifier: Modifier = Modifier,
    title: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        if (title.isNotEmpty()) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp),
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.padding(vertical = 4.dp))
        }

        Card(
            modifier = modifier
                .wrapContentHeight()
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.Start,
                content = content,
            )
        }
    }
}

@Preview
@Composable
private fun ListSettingsContainerPreview() {
    DnsNetTheme {
        ListSettingsContainer(title = "Bypass DNSNet for marked apps") {
            var checked by remember { mutableStateOf(false) }
            SwitchListItem(
                checked = checked,
                title = "Chaos Computer Club",
                details = "213.73.91.35",
                onCheckedChange = { checked = !checked },
            )
            var checked2 by remember { mutableStateOf(false) }
            CheckboxListItem(
                checked = checked2,
                title = "Chaos Computer Club",
                details = "213.73.91.35",
                onCheckedChange = { checked2 = !checked2 },
            )
            IconListItem(
                title = "Chaos Computer Club",
                details = "213.73.91.35",
                onClick = {},
                iconContent = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.MoreVert, null)
                    }
                },
            )

            var expanded by remember { mutableStateOf(false) }
            ExpandableOptionsItem(
                expanded = expanded,
                title = "Expandable",
                details = "Details",
                onExpandClick = { expanded = !expanded },
            ) {
                RadioListItem(
                    checked = false,
                    title = "Option1",
                    onCheckedChange = {},
                )
                RadioListItem(
                    checked = false,
                    title = "Option2",
                    onCheckedChange = {},
                )
                RadioListItem(
                    checked = false,
                    title = "Option3",
                    onCheckedChange = {},
                )
            }
        }
    }
}
