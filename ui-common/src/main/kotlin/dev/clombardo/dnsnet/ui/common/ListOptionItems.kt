/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.common

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable

interface ListSortType {
    @get:StringRes
    val labelRes: Int
}

@Serializable
abstract class ListSort {
    abstract val selectedType: ListSortType
    abstract val ascending: Boolean
}

interface ListFilterType {
    @get:StringRes
    val labelRes: Int
}

@Serializable
abstract class ListFilter<T : ListFilterType> {
    abstract val filters: Map<T, FilterMode>
}

@Composable
fun ListOptionItem(
    modifier: Modifier = Modifier,
    text: String,
    endContent: @Composable BoxScope.() -> Unit,
) {
    ContentSetting(
        modifier = modifier.padding(horizontal = 16.dp),
        title = text,
        endContent = endContent
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SortItem(
    modifier: Modifier = Modifier,
    selected: Boolean,
    ascending: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    ListOptionItem(
        modifier = modifier
            .clickable(
                role = Role.Button,
                onClick = onClick,
            ),
        text = label,
    ) {
        if (selected) {
            val animatedRotation by animateFloatAsState(
                targetValue = if (ascending) 0f else -180f,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "animatedRotation",
            )
            Icon(
                modifier = Modifier.rotate(animatedRotation),
                imageVector = Icons.Filled.ArrowUpward,
                contentDescription = if (ascending) {
                    stringResource(R.string.ascending)
                } else {
                    stringResource(R.string.descending)
                },
            )
        }
    }
}

enum class FilterMode {
    Include,
    Exclude,
}

@Composable
fun FilterItem(
    modifier: Modifier = Modifier,
    label: String,
    mode: FilterMode?,
    onClick: () -> Unit,
) {
    val state = when (mode) {
        FilterMode.Include -> ToggleableState.On
        FilterMode.Exclude -> ToggleableState.Indeterminate
        null -> ToggleableState.Off
    }
    ListOptionItem(
        modifier = modifier
            .triStateToggleable(
                state = state,
                role = Role.Checkbox,
                onClick = onClick,
            ),
        text = label,
    ) {
        TriStateCheckbox(
            state = state,
            onClick = onClick,
        )
    }
}
