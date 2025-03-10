/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.state

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable

sealed interface ListSortType {
    @get:StringRes
    val labelRes: Int
}

@Serializable
abstract class ListSort {
    abstract val selectedType: ListSortType
    abstract val ascending: Boolean
}

sealed interface ListFilterType {
    @get:StringRes
    val labelRes: Int
}

enum class FilterMode {
    Include,
    Exclude,
}

@Serializable
abstract class ListFilter<T : ListFilterType> {
    abstract val filters: Map<T, FilterMode>
}
