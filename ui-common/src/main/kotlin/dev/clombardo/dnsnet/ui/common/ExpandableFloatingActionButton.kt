/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collection.MutableVector
import androidx.compose.runtime.collection.mutableVectorOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.clombardo.dnsnet.ui.common.theme.DnsNetTheme
import kotlinx.coroutines.delay

data class FloatingActionItem(
    val modifier: Modifier,
    val icon: ImageVector,
    val text: String,
    val onClick: () -> Unit,
)

sealed interface ExpandableFloatingActionScope {
    val itemList: MutableVector<FloatingActionItem>

    fun item(
        modifier: Modifier = Modifier,
        icon: ImageVector,
        text: String,
        onClick: () -> Unit,
    )
}

class ExpandableFloatingActionScopeImpl : ExpandableFloatingActionScope {
    override val itemList: MutableVector<FloatingActionItem> = mutableVectorOf()

    override fun item(
        modifier: Modifier,
        icon: ImageVector,
        text: String,
        onClick: () -> Unit
    ) {
        itemList.add(
            FloatingActionItem(
                modifier = modifier,
                icon = icon,
                text = text,
                onClick = onClick,
            )
        )
    }
}

@Composable
fun ExpandableFloatingActionButtonItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    Box(
        modifier = modifier
            .sizeIn(minWidth = 56.dp, minHeight = 56.dp)
            .clip(shape = MaterialTheme.shapes.large)
            .clickable(
                enabled = true,
                onClick = onClick,
            )
            .background(color = containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val mergedStyle = LocalTextStyle.current.merge(MaterialTheme.typography.labelLarge)
            CompositionLocalProvider(
                LocalContentColor provides contentColorFor(containerColor),
                LocalTextStyle provides mergedStyle,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                )
                Text(text = text)
            }
        }
    }
}

@Preview
@Composable
private fun ExpandableFloatingActionButtonItemPreview() {
    ExpandableFloatingActionButtonItem(
        icon = Icons.Default.Remove,
        text = "Add exclusion",
        onClick = {}
    )
}

object ExpandableFloatingActionButtonDefaults {
    const val expandAnimationDurationMillis = 100
}

@Composable
fun ExpandableFloatingActionButton(
    expanded: Boolean,
    onClick: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    expandAnimationDurationMillis: Int = ExpandableFloatingActionButtonDefaults.expandAnimationDurationMillis,
    buttonContent: @Composable () -> Unit,
    expandedContent: ExpandableFloatingActionScope.() -> Unit,
) {
    Box {
        var actionButtonHeight by remember { mutableStateOf(IntOffset(0, 0)) }
        FloatingActionButton(
            onClick = onClick,
            modifier = modifier.onPlaced {
                actionButtonHeight = IntOffset(0, -it.size.height)
            },
            interactionSource = interactionSource,
            content = buttonContent
        )

        var popupVisible by remember { mutableStateOf(false) }
        LaunchedEffect(expanded) {
            if (expanded) {
                popupVisible = true
            } else {
                delay(expandAnimationDurationMillis.toLong())
                popupVisible = false
            }
        }
        if (popupVisible) {
            Popup(
                alignment = Alignment.BottomEnd,
                offset = actionButtonHeight,
                onDismissRequest = onDismissRequest,
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = false,
                ),
            ) {
                val latestContent = rememberUpdatedState(expandedContent)
                val scope by remember {
                    derivedStateOf {
                        ExpandableFloatingActionScopeImpl().apply(latestContent.value)
                    }
                }

                // Need to slightly delay updating this so our animators can see the change
                var readyToExpand by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier.padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    scope.itemList.forEachReversedIndexed { i, item ->
                        val animationSpec = tween<Float>(
                            durationMillis = expandAnimationDurationMillis,
                            delayMillis = i * 50,
                        )
                        val animatedAlpha by animateFloatAsState(
                            targetValue = if (expanded && readyToExpand) 1f else 0f,
                            animationSpec = animationSpec,
                        )
                        val animatedScale by animateFloatAsState(
                            targetValue = if (expanded && readyToExpand) 1f else 0.9f,
                            animationSpec = animationSpec,
                        )
                        ExpandableFloatingActionButtonItem(
                            modifier = item.modifier
                                .alpha(animatedAlpha)
                                .scale(animatedScale),
                            icon = item.icon,
                            text = item.text,
                            onClick = item.onClick
                        )
                    }
                    readyToExpand = true
                }
            }
        }
    }
}

@Preview
@Composable
private fun ExpandableFloatingActionButtonPreview() {
    DnsNetTheme {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            var expanded by remember { mutableStateOf(false) }
            ExpandableFloatingActionButton(
                expanded = expanded,
                onClick = {
                    if (!expanded) {
                        expanded = true
                    }
                },
                onDismissRequest = { expanded = false },
                buttonContent = {
                    val rotation by animateFloatAsState(
                        targetValue = if (expanded) 45f else 0f,
                    )
                    Icon(
                        modifier = Modifier.rotate(rotation),
                        imageVector = Icons.Default.Add,
                        contentDescription = null
                    )
                },
                expandedContent = {
                    item(
                        icon = Icons.Default.RemoveDone,
                        text = "Remove",
                        onClick = {}
                    )
                    item(
                        icon = Icons.Default.People,
                        text = "Another action",
                        onClick = {}
                    )
                }
            )
        }
    }
}
