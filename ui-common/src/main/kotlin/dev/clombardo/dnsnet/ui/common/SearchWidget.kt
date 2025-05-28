/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import dev.clombardo.dnsnet.ui.common.theme.DnsNetTheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object SearchWidgetDefaults {
    val searchEnterTransition: EnterTransition
        @Composable get() {
            return expandHorizontally(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            )
        }
    val searchExitTransition: ExitTransition
        @Composable get() {
            return shrinkHorizontally(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            )
        }
}

@Composable
fun SearchWidget(
    modifier: Modifier = Modifier,
    expanded: Boolean,
    searchValue: String,
    onSearchButtonClick: () -> Unit,
    onSearchValueChange: (String) -> Unit,
    onClearButtonClick: () -> Unit,
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
    ),
    searchEnterTransition: EnterTransition = SearchWidgetDefaults.searchEnterTransition,
    searchExitTransition: ExitTransition = SearchWidgetDefaults.searchExitTransition,
) {
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = TextFieldDefaults.MinHeight),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val focusRequester = remember { FocusRequester() }
        BasicTooltipIconButton(
            icon = Icons.Default.Search,
            contentDescription = stringResource(R.string.search),
            onClick = onSearchButtonClick,
        )
        AnimatedVisibility(
            visible = expanded,
            enter = searchEnterTransition,
            exit = searchExitTransition,
        ) {
            TextField(
                modifier = Modifier.focusRequester(focusRequester),
                value = searchValue,
                onValueChange = onSearchValueChange,
                singleLine = true,
                keyboardOptions = keyboardOptions,
                placeholder = { Text(stringResource(R.string.search)) },
                trailingIcon = {
                    BasicTooltipIconButton(
                        icon = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        onClick = onClearButtonClick,
                    )
                },
            )

            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        }
    }
}

@Preview
@Composable
private fun SearchWidgetPreview() {
    DnsNetTheme {
        var searchValue by rememberSaveable { mutableStateOf("") }
        SearchWidget(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            expanded = true,
            onSearchButtonClick = {},
            searchValue = searchValue,
            onSearchValueChange = { searchValue = it },
            onClearButtonClick = { searchValue = "" },
        )
    }
}
