/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class FabState {
    Inactive,
    Loading,
    Active,
}

object TriStateFab {
    val size = 96.dp
    val safeInsets: WindowInsets
        @Composable get() = WindowInsets.displayCutout.union(WindowInsets.systemBars)
}

@Composable
fun TriStateFab(
    modifier: Modifier = Modifier,
    state: FabState,
    inactiveContent: @Composable () -> Unit = {},
    loadingContent: @Composable () -> Unit = {},
    activeContent: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(32.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val animationDurationMillis = 100
    val elevation by animateDpAsState(
        targetValue = if (isPressed) 0.dp else 6.dp,
        animationSpec = tween(durationMillis = animationDurationMillis),
        label = "elevation",
    )
    val containerColor by animateColorAsState(
        targetValue = when (state) {
            FabState.Active -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(durationMillis = animationDurationMillis),
        label = "containerColor",
    )

    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
            )
            .clip(shape)
            .background(containerColor)
            .size(TriStateFab.size)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val contentColor by animateColorAsState(
            targetValue = when (state) {
                FabState.Active -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onPrimary
            },
            animationSpec = tween(durationMillis = animationDurationMillis),
            label = "contentColor",
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                when (state) {
                    FabState.Active -> activeContent()
                    FabState.Inactive -> inactiveContent()
                    FabState.Loading -> loadingContent()
                }
            }
        }
    }
}

@Preview
@Composable
private fun TriStateFabPreview() {
    var state by remember { mutableStateOf(FabState.Inactive) }
    TriStateFab(
        state = state,
        inactiveContent = {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Add",
            )
        },
        loadingContent = {
            CircularProgressIndicator(color = LocalContentColor.current)
        },
        activeContent = {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = "Stop",
            )
        },
    ) {
        state = when (state) {
            FabState.Inactive -> FabState.Loading
            FabState.Loading -> FabState.Active
            FabState.Active -> FabState.Inactive
        }
    }
}
