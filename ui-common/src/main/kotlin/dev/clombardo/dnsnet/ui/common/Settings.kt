/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.t895.materialswitch.MaterialSwitch
import dev.clombardo.dnsnet.ui.common.theme.DnsNetTheme

private val innerStartHorizontalPadding = 8.dp
private val innerEndHorizontalPadding = 8.dp
private val clickablePadding = 8.dp

@Composable
fun Modifier.clickable(
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
    role: Role,
    clip: Boolean = false,
    onClick: () -> Unit,
) = this
    .then(
        if (clip) {
            Modifier.clip(CardDefaults.shape)
        } else {
            Modifier
        }
    )
    .clickable(
        enabled = enabled,
        onClick = onClick,
        interactionSource = interactionSource,
        indication = ripple(),
        role = role,
    )
    .padding(clickablePadding)

@Composable
private fun Modifier.toggleable(
    value: Boolean,
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
    role: Role,
    clip: Boolean = false,
    onValueChange: (Boolean) -> Unit,
) = this
    .then(
        if (clip) {
            Modifier.clip(CardDefaults.shape)
        } else {
            Modifier
        }
    )
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
    maxTitleLines: Int = Int.MAX_VALUE,
    maxDetailLines: Int = Int.MAX_VALUE,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            modifier = Modifier
                .then(
                    if (maxTitleLines == 1) {
                        Modifier.basicMarquee()
                    } else {
                        Modifier
                    }
                ),
            text = title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = maxTitleLines,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (details.isNotEmpty()) {
            Spacer(modifier = Modifier.padding(vertical = 1.dp))
            Text(
                modifier = Modifier
                    .then(
                        if (maxDetailLines == 1) {
                            Modifier.basicMarquee()
                        } else {
                            Modifier
                        }
                    ),
                text = details,
                style = MaterialTheme.typography.bodySmall,
                maxLines = maxDetailLines,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
fun SettingContentTheme(
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
        LocalTextStyle provides LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContentSetting(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    title: String = "",
    details: String = "",
    maxTitleLines: Int = Int.MAX_VALUE,
    maxDetailLines: Int = Int.MAX_VALUE,
    startContent: @Composable (BoxScope.() -> Unit)? = null,
    endContent: @Composable (BoxScope.() -> Unit)? = null,
) {
    val animatedAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.6f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    Row(
        modifier = modifier
            .graphicsLayer {
                alpha = animatedAlpha
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (startContent != null) {
            SettingContentTheme {
                Box(
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    contentAlignment = Alignment.Center,
                    content = startContent,
                )
            }
            Spacer(modifier = Modifier.padding(horizontal = innerStartHorizontalPadding))
        }

        SettingInfo(
            modifier = Modifier.weight(1f),
            title = title,
            details = details,
            maxTitleLines = maxTitleLines,
            maxDetailLines = maxDetailLines,
        )

        if (endContent != null) {
            Spacer(modifier = Modifier.padding(horizontal = innerEndHorizontalPadding))
            SettingContentTheme {
                Box(
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    contentAlignment = Alignment.Center,
                    content = endContent,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SplitContentSetting(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    title: String = "",
    details: String = "",
    maxDetailLines: Int = 1,
    outlineColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onBodyClick: () -> Unit,
    clip: Boolean = false,
    interactionSource: MutableInteractionSource? = remember { MutableInteractionSource() },
    startContent: @Composable (BoxScope.() -> Unit)? = null,
    endContent: @Composable BoxScope.() -> Unit,
) {
    val animatedAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.6f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .clickable(
                enabled = enabled,
                onClick = onBodyClick,
                interactionSource = interactionSource,
                role = Role.Button,
                clip = clip,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .weight(1f)
                .graphicsLayer {
                    alpha = animatedAlpha
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (startContent != null) {
                SettingContentTheme {
                    Box(
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        contentAlignment = Alignment.Center,
                        content = startContent,
                    )
                }
                Spacer(modifier = Modifier.padding(horizontal = innerStartHorizontalPadding))
            }

            SettingInfo(
                modifier = Modifier.weight(1f),
                title = title,
                details = details,
                maxDetailLines = maxDetailLines,
            )
        }
        Row(
            modifier = Modifier
                .graphicsLayer {
                    alpha = animatedAlpha
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.padding(horizontal = innerEndHorizontalPadding / 2))
            Icon(
                painter = rememberVectorPainter(Icons.AutoMirrored.Default.KeyboardArrowRight),
                contentDescription = null,
                tint = outlineColor,
            )
            Spacer(modifier = Modifier.padding(horizontal = innerEndHorizontalPadding / 2))
            VerticalDivider(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 8.dp),
                color = outlineColor,
            )
            Spacer(modifier = Modifier.padding(horizontal = innerEndHorizontalPadding))
        }
        SettingContentTheme {
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .padding(end = 8.dp),
                contentAlignment = Alignment.Center,
                content = endContent,
            )
        }
    }
}

@Composable
private fun ClickableSetting(
    title: String,
    role: Role,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    details: String = "",
    clip: Boolean = false,
    sharedInteractionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
    startContent: @Composable (BoxScope.() -> Unit)? = null,
    endContent: @Composable BoxScope.() -> Unit,
) {
    ContentSetting(
        modifier = modifier
            .clickable(
                enabled = enabled,
                onClick = onClick,
                clip = clip,
                interactionSource = sharedInteractionSource,
                role = role,
            ),
        enabled = enabled,
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
    clip: Boolean = false,
    sharedInteractionSource: MutableInteractionSource? = null,
    onCheckedChange: (Boolean) -> Unit,
    startContent: @Composable (BoxScope.() -> Unit)? = null,
    toggleableContent: @Composable BoxScope.() -> Unit,
) {
    ContentSetting(
        modifier = modifier
            .toggleable(
                value = checked,
                enabled = enabled,
                clip = clip,
                onValueChange = onCheckedChange,
                interactionSource = sharedInteractionSource,
                role = role,
            ),
        enabled = enabled,
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
    clip: Boolean = false,
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
        clip = clip,
        sharedInteractionSource = sharedInteractionSource,
        onCheckedChange = onCheckedChange,
        startContent = startContent,
    ) {
        Checkbox(
            modifier = Modifier.semantics { contentDescription = title },
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
    outlineColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
    bodyEnabled: Boolean = true,
    checkboxEnabled: Boolean = true,
    details: String = "",
    clip: Boolean = false,
    maxDetailLines: Int = 1,
    onBodyClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    startContent: @Composable (BoxScope.() -> Unit)? = null,
) {
    SplitContentSetting(
        modifier = modifier,
        title = title,
        details = details,
        maxDetailLines = maxDetailLines,
        outlineColor = outlineColor,
        onBodyClick = onBodyClick,
        enabled = bodyEnabled,
        clip = clip,
        startContent = startContent,
        endContent = {
            Checkbox(
                modifier = Modifier.semantics { contentDescription = title },
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
    clip: Boolean = false,
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
        clip = clip,
        onCheckedChange = onCheckedChange,
        sharedInteractionSource = sharedInteractionSource,
        startContent = startContent,
        toggleableContent = {
            MaterialSwitch(
                modifier = Modifier.semantics { contentDescription = title },
                enabled = enabled,
                checked = checked,
                onCheckedChange = onCheckedChange,
                interactionSource = sharedInteractionSource,
                thumbContent = { mostlyEnabled ->
                    if (mostlyEnabled) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                        )
                    }
                },
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
    outlineColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
    bodyEnabled: Boolean = true,
    switchEnabled: Boolean = true,
    details: String = "",
    maxDetailLines: Int = 1,
    onBodyClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    startContent: @Composable (BoxScope.() -> Unit)? = null
) {
    SplitContentSetting(
        modifier = modifier,
        title = title,
        details = details,
        maxDetailLines = maxDetailLines,
        outlineColor = outlineColor,
        onBodyClick = onBodyClick,
        enabled = bodyEnabled,
        startContent = startContent,
        endContent = {
            MaterialSwitch(
                modifier = Modifier.semantics { contentDescription = title },
                enabled = switchEnabled,
                checked = checked,
                onCheckedChange = onCheckedChange,
                thumbContent = { mostlyEnabled ->
                    if (mostlyEnabled) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    )
}

@Preview
@Composable
private fun SplitSwitchListItemPreview() {
    DnsNetTheme {
        var checked by remember { mutableStateOf(false) }
        val onCheckedChange = { checked = !checked }
        SplitSwitchListItem(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            checked = checked,
            title = "Title",
            details = "Details",
            onBodyClick = onCheckedChange,
            onCheckedChange = { onCheckedChange() },
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpandableOptionsItem(
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    enabled: Boolean = true,
    clip: Boolean = false,
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
            clip = clip,
            onClick = onExpandClick,
            sharedInteractionSource = sharedInteractionSource,
        ) {
            IconButton(
                modifier = Modifier.semantics { contentDescription = title },
                enabled = enabled,
                onClick = onExpandClick,
                interactionSource = sharedInteractionSource,
            ) {
                val iconRotation by animateFloatAsState(
                    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    targetValue = if (expanded) 180f else 0f,
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
            enter = expandVertically(
                animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
                expandFrom = Alignment.Top
            ),
            exit = shrinkVertically(
                animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
                shrinkTowards = Alignment.Top,
            ),
        ) {
            Column(
                modifier = Modifier
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
    clip: Boolean = false,
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
        clip = clip,
        onCheckedChange = onCheckedChange,
        sharedInteractionSource = sharedInteractionSource,
        toggleableContent = {
            RadioButton(
                modifier = Modifier.semantics { contentDescription = title },
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
    circleClip: Boolean = true,
    content: SplitContentContainerScope.() -> Unit,
) {
    Column(modifier = modifier) {
        if (title.isNotEmpty()) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp),
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.padding(vertical = 4.dp))
        }

        SplitContentColumnContainer(
            color = MaterialTheme.colorScheme.surfaceContainer,
            circleClip = circleClip,
            content = content,
        )
    }
}

@Preview
@Composable
private fun ListSettingsContainerPreview() {
    DnsNetTheme {
        ListSettingsContainer(title = "Bypass DNSNet for marked apps") {
            item {
                var checked by remember { mutableStateOf(false) }
                SwitchListItem(
                    checked = checked,
                    title = "Chaos Computer Club",
                    details = "213.73.91.35",
                    onCheckedChange = { checked = !checked },
                )
            }

            item {
                var checked2 by remember { mutableStateOf(false) }
                CheckboxListItem(
                    checked = checked2,
                    title = "Chaos Computer Club",
                    details = "213.73.91.35",
                    onCheckedChange = { checked2 = !checked2 },
                )
            }

            item {
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
            }

            item {
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
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun IconSettingButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    title: String,
    details: String,
    icon: ImageVector,
    onClick: () -> Unit,
    endContent: (@Composable BoxScope.() -> Unit)? = null,
) {
    ContentSetting(
        modifier = modifier
            .clickable(
                enabled = enabled,
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Button,
                indication = ripple(),
            )
            .fillMaxWidth(),
        enabled = enabled,
        title = title,
        details = details,
        maxTitleLines = 1,
        maxDetailLines = 1,
        startContent = {
            Image(
                modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 16.dp),
                painter = rememberVectorPainter(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        },
        endContent = endContent,
    )
}

@Preview
@Composable
private fun IconSettingButtonPreview() {
    DnsNetTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            IconSettingButton(
                title = "Some submenu",
                details = "Submenu description",
                icon = Icons.Default.Person,
                enabled = true,
                onClick = {},
            )
        }
    }
}
