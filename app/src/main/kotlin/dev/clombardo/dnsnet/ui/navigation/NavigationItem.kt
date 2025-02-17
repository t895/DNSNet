/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.navigation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.clombardo.dnsnet.ui.theme.Animation
import dev.clombardo.dnsnet.ui.theme.DnsNetTheme

data class NavigationItem(
    val modifier: Modifier,
    val selected: Boolean,
    val icon: ImageVector,
    val text: String,
    val onClick: () -> Unit,
)

@Composable
fun NavigationItem(
    modifier: Modifier = Modifier,
    layoutType: LayoutType = LayoutType.NavigationBar,
    item: NavigationItem,
) {
    val interactionSource = remember { MutableInteractionSource() }

    val indicatorWidth = when (layoutType) {
        LayoutType.NavigationBar -> NavigationBarDefaults.itemSelectedIndicatorWidth
        LayoutType.NavigationRail -> NavigationRailDefaults.itemSelectedIndicatorWidth
    }
    val itemHeight = when (layoutType) {
        LayoutType.NavigationBar -> NavigationBarDefaults.itemHeight
        LayoutType.NavigationRail -> NavigationRailDefaults.itemHeight
    }
    Column(
        modifier = modifier
            .then(item.modifier)
            .sizeIn(minWidth = indicatorWidth, minHeight = itemHeight)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = item.onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (layoutType == LayoutType.NavigationBar) {
            Spacer(Modifier.padding(top = 12.dp))
        }
        Box(contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(16.dp)
            val animatedWidth by animateDpAsState(
                targetValue = if (item.selected) indicatorWidth else 0.dp,
                animationSpec = tween(
                    durationMillis = if (item.selected) 250 else 0,
                    easing = Animation.EmphasizedDecelerateEasing,
                ),
                label = "animatedWidth",
            )

            Box(
                modifier = Modifier
                    .size(width = animatedWidth, height = 32.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = shape,
                    )
            )
            Box(
                modifier = Modifier
                    .size(width = indicatorWidth, height = 32.dp)
                    .clip(shape)
                    .indication(
                        interactionSource = interactionSource,
                        indication = ripple(
                            color = MaterialTheme.colorScheme.secondary
                        ),
                    )
            )
            Icon(
                modifier = Modifier.size(24.dp),
                imageVector = item.icon,
                contentDescription = null,
                tint = if (item.selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.padding(vertical = 2.dp))
        Text(
            text = item.text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            color = if (item.selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        if (layoutType == LayoutType.NavigationBar) {
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Preview
@Composable
private fun NavigationItemPreview() {
    DnsNetTheme {
        var selected by remember { mutableStateOf(false) }
        NavigationItem(
            item = NavigationItem(
                modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                selected = selected,
                icon = Icons.Default.VpnKey,
                text = "Start",
                onClick = { selected = !selected },
            )
        )
    }
}
