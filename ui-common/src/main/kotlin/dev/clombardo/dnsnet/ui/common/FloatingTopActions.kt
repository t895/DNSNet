/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.clombardo.dnsnet.ui.common.theme.DnsNetTheme

object FloatingTopActionsDefaults {
    val windowInsets: WindowInsets
        @Composable
        get() = WindowInsets.statusBars.union(WindowInsets.displayCutout)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingTopActions(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    windowInsets: WindowInsets = FloatingTopActionsDefaults.windowInsets,
    navigationIcon: @Composable SplitContentContainerScope.() -> Unit,
    actions: SplitContentContainerScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(windowInsets.asPaddingValues())
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        SplitContentRowContainer(elevated = elevated) {
            item {
                navigationIcon()
            }
        }
        Box(modifier = Modifier.weight(1f))
        SplitContentRowContainer(elevated = elevated, content = actions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun FloatingTopActionsPreview() {
    DnsNetTheme {
        val scrollState = rememberLazyListState()
        Scaffold(
            topBar = {
                val elevated by rememberAtTop(scrollState)
                FloatingTopActions(
                    elevated = elevated,
                    navigationIcon = {
                        Icon(
                            modifier = Modifier.fullSizeClickable(onClick = {}),
                            imageVector = Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = null
                        )
                    },
                    actions = {
                        item {
                            Icon(
                                modifier = Modifier.fullSizeClickable(onClick = {}),
                                imageVector = Icons.Default.VpnKey,
                                contentDescription = null
                            )
                        }
                        item {
                            Icon(
                                modifier = Modifier.fullSizeClickable(onClick = {}),
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = null
                            )
                        }
                    },
                )
            }
        ) { contentPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = scrollState,
                contentPadding = contentPadding,
            ) {
                repeat(50) {
                    item {
                        Text("Something")
                    }
                }
            }
        }
    }
}
