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
import dev.clombardo.dnsnet.ui.App
import dev.clombardo.dnsnet.ui.state.AppListState
import dev.clombardo.dnsnet.ui.state.FilterMode

class AppListViewModel : PersistableViewModel() {
    override val tag = "AppListViewModel"

    var searchValue by mutableStateOf("")

    var searchWidgetExpanded by mutableStateOf(false)
    var showModifyListSheet by mutableStateOf(false)

    var sort by mutableStateOf(
        getInitialPersistedValue(SORT_KEY, AppListState.Sort())
    )

    fun onSortClick(type: AppListState.SortType) {
        sort = if (sort.selectedType == type) {
            AppListState.Sort(
                selectedType = type,
                ascending = !sort.ascending,
            )
        } else {
            AppListState.Sort(
                selectedType = type,
                ascending = true,
            )
        }
        persistValue(SORT_KEY, sort)
    }

    var filter by mutableStateOf(
        getInitialPersistedValue(FILTER_KEY, AppListState.Filter())
    )

    fun onFilterClick(type: AppListState.FilterType) {
        val newFilters = filter.filters.toMutableMap()
        val currentState = filter.filters[type]
        when (currentState) {
            FilterMode.Include ->
                newFilters[type] = FilterMode.Exclude

            FilterMode.Exclude -> newFilters.remove(type)
            null -> newFilters[type] = FilterMode.Include
        }
        filter = AppListState.Filter(newFilters)
        persistValue(FILTER_KEY, filter)
    }

    fun getList(initialList: List<App>): List<App> {
        val sortedList = when (sort.selectedType) {
            AppListState.SortType.Alphabetical -> if (sort.ascending) {
                initialList.sortedBy { it.label }
            } else {
                initialList.sortedByDescending { it.label }
            }
        }

        val filteredList = sortedList.filter {
            var result = true
            filter.filters.forEach { (type, mode) ->
                when (type) {
                    AppListState.FilterType.SystemApps -> {
                        result = when (mode) {
                            FilterMode.Include -> it.isSystem
                            FilterMode.Exclude -> !it.isSystem
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
                val similarity =
                    cosineSimilarity.similarity(it.label.lowercase(), adjustedSearchValue)
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
