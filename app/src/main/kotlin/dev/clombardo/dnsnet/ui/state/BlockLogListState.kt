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

object BlockLogListState {
    enum class SortType(override val labelRes: Int) : ListSortType {
        Attempts(R.string.attempts),
        LastConnected(R.string.last_connected),
        Alphabetical(R.string.alphabetical),
    }

    @Serializable
    data class Sort(
        override val selectedType: SortType = SortType.Attempts,
        override val ascending: Boolean = true,
    ) : ListSort()

    enum class FilterType(override val labelRes: Int) : ListFilterType {
        Blocked(R.string.blocked),
    }

    @Serializable
    data class Filter(
        override val filters: Map<FilterType, FilterMode> = emptyMap()
    ) : ListFilter<FilterType>()
}
