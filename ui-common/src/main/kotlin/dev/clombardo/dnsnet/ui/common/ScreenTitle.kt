/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.clombardo.dnsnet.ui.common.theme.DnsNetTheme

@Composable
fun ScreenTitle(
    modifier: Modifier = Modifier,
    text: String,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(bottom = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.displaySmall,
            fontSize = 32.sp,
        )
    }
}

@Preview
@Composable
private fun ScreenTitlePreview() {
    DnsNetTheme {
        val state = rememberLazyListState()
        InsetScaffold(
            topBar = {
                val isAtTop by rememberAtTop(state)
                FloatingTopActions(
                    elevated = !isAtTop,
                    navigationIcon = {
                        BasicTooltipButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up),
                            onClick = {},
                        )
                    }
                )
            }
        ) { contentPadding ->
            LazyColumn(
                state = state,
                contentPadding = contentPadding
            ) {
                item {
                    ScreenTitle(text = "Screen Title")
                }

                repeat(50) {
                    item {
                        Text(text = "Item $it")
                    }
                }
            }
        }
    }
}
