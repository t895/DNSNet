/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.clombardo.dnsnet.ui.LoggedConnectionState
import dev.clombardo.dnsnet.ui.state.BlockLogListState
import dev.clombardo.dnsnet.ui.state.FilterMode
import dev.clombardo.dnsnet.vpn.LoggedConnection

class BlockLogListViewModel : PersistableViewModel() {
    override val tag = "BlockLogListViewModel"

    var searchValue by mutableStateOf("")

    var sort by mutableStateOf(
        getInitialPersistedValue(SORT_KEY, BlockLogListState.Sort())
    )

    fun onSortClick(type: BlockLogListState.SortType) {
        sort = if (sort.selectedType == type) {
            BlockLogListState.Sort(
                selectedType = type,
                ascending = !sort.ascending,
            )
        } else {
            BlockLogListState.Sort(
                selectedType = type,
                ascending = true,
            )
        }
        persistValue(SORT_KEY, sort)
    }

    var filter by mutableStateOf(
        getInitialPersistedValue(FILTER_KEY, BlockLogListState.Filter())
    )

    fun onFilterClick(type: BlockLogListState.FilterType) {
        val newFilters = filter.filters.toMutableMap()
        val currentState = filter.filters[type]
        when (currentState) {
            FilterMode.Include ->
                newFilters[type] = FilterMode.Exclude

            FilterMode.Exclude -> newFilters.remove(type)
            null -> newFilters[type] = FilterMode.Include
        }
        filter = BlockLogListState.Filter(newFilters)
        persistValue(FILTER_KEY, filter)
    }

    fun getList(initialList: Map<String, LoggedConnection>): List<LoggedConnectionState> {
        val list = initialList.map {
            LoggedConnectionState(
                it.key,
                it.value.allowed,
                it.value.attempts,
                it.value.lastAttemptTime,
            )
        }

        val sortedList = when (sort.selectedType) {
            BlockLogListState.SortType.Alphabetical -> if (sort.ascending) {
                list.sortedByDescending { it.hostname }
            } else {
                list.sortedBy { it.hostname }
            }

            BlockLogListState.SortType.LastConnected -> if (sort.ascending) {
                list.sortedByDescending { it.lastAttemptTime }
            } else {
                list.sortedBy { it.lastAttemptTime }
            }

            BlockLogListState.SortType.Attempts -> if (sort.ascending) {
                list.sortedByDescending { it.attempts }
            } else {
                list.sortedBy { it.attempts }
            }
        }

        val filteredList = sortedList.filter {
            var result = true
            filter.filters.forEach { (type, mode) ->
                when (type) {
                    BlockLogListState.FilterType.Blocked -> {
                        result = when (mode) {
                            FilterMode.Include -> !it.allowed
                            FilterMode.Exclude -> it.allowed
                        }
                    }
                }
            }
            result
        }

        return if (searchValue.isEmpty()) {
            filteredList
        } else {
            val adjustedSearchValue = searchValue.trim().lowercase()
            filteredList.mapNotNull {
                val similarity = cosineSimilarity.similarity(it.hostname, adjustedSearchValue)
                if (similarity > 0) {
                    similarity to it
                } else {
                    null
                }
            }.sortedByDescending {
                it.first
            }.map { it.second }
        }
    }

    companion object {
        private const val SORT_KEY = "sort"
        private const val FILTER_KEY = "filter"
    }
}
