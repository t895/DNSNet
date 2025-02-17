/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.clombardo.dnsnet.R
import dev.clombardo.dnsnet.ui.theme.DnsNetTheme
import dev.clombardo.dnsnet.ui.theme.VpnFabSize
import dev.clombardo.dnsnet.vpn.VpnStatus

@Composable
fun VpnFab(
    modifier: Modifier = Modifier,
    status: VpnStatus,
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
        targetValue = when (status) {
            VpnStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(durationMillis = animationDurationMillis),
        label = "containerColor",
    )

    Box(
        modifier = modifier
            .navigationBarsPadding()
            .shadow(
                elevation = elevation,
                shape = shape,
            )
            .clip(shape)
            .background(containerColor)
            .size(VpnFabSize)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val contentSize = 42.dp
        val contentColor by animateColorAsState(
            targetValue = when (status) {
                VpnStatus.RUNNING -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onPrimary
            },
            animationSpec = tween(durationMillis = animationDurationMillis),
            label = "contentColor",
        )

        if (status == VpnStatus.RUNNING) {
            Icon(
                modifier = Modifier.size(contentSize),
                imageVector = Icons.Default.Stop,
                contentDescription = stringResource(R.string.action_stop),
                tint = contentColor,
            )
        } else if (status == VpnStatus.STOPPED) {
            Icon(
                modifier = Modifier.size(contentSize),
                imageVector = Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.action_start),
                tint = contentColor,
            )
        } else {
            CircularProgressIndicator(color = contentColor)
        }
    }
}

@Preview
@Composable
private fun VpnFabPreview() {
    DnsNetTheme {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .size(128.dp),
            contentAlignment = Alignment.Center,
        ) {
            var status by remember { mutableStateOf(VpnStatus.STOPPED) }
            VpnFab(status = status) {
                if (status == VpnStatus.RUNNING) {
                    status = VpnStatus.STOPPED
                } else if (status == VpnStatus.STARTING) {
                    status = VpnStatus.RUNNING
                } else if (status == VpnStatus.STOPPED) {
                    status = VpnStatus.STARTING
                }
            }
        }
    }
}
