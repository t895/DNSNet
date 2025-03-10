/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.state

import dev.clombardo.dnsnet.R
import kotlinx.serialization.Serializable

object AppListState {
    enum class SortType(override val labelRes: Int) : ListSortType {
        Alphabetical(R.string.alphabetical),
    }

    @Serializable
    data class Sort(
        override val selectedType: SortType = SortType.Alphabetical,
        override val ascending: Boolean = true,
    ) : ListSort()

    enum class FilterType(override val labelRes: Int) : ListFilterType {
        SystemApps(R.string.system_apps),
    }

    @Serializable
    data class Filter(
        override val filters: Map<FilterType, FilterMode> =
            mapOf(FilterType.SystemApps to FilterMode.Exclude)
    ) : ListFilter<FilterType>()
}
