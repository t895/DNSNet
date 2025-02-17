/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.runtime.collection.MutableVector
import androidx.compose.runtime.collection.mutableVectorOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.clombardo.dnsnet.DnsNetApplication.Companion.applicationContext

sealed interface NavigationScope {
    val itemList: MutableVector<NavigationItem>

    fun item(
        modifier: Modifier = Modifier,
        selected: Boolean,
        icon: ImageVector,
        text: String,
        onClick: () -> Unit,
    )

    fun item(
        modifier: Modifier = Modifier,
        selected: Boolean,
        icon: ImageVector,
        @StringRes textId: Int,
        onClick: () -> Unit,
    )
}

class NavigationScopeImpl : NavigationScope {
    override val itemList: MutableVector<NavigationItem> = mutableVectorOf()

    override fun item(
        modifier: Modifier,
        selected: Boolean,
        icon: ImageVector,
        text: String,
        onClick: () -> Unit
    ) {
        itemList.add(
            NavigationItem(
                modifier = modifier,
                selected = selected,
                icon = icon,
                text = text,
                onClick = onClick,
            )
        )
    }

    override fun item(
        modifier: Modifier,
        selected: Boolean,
        icon: ImageVector,
        textId: Int,
        onClick: () -> Unit
    ) {
        itemList.add(
            NavigationItem(
                modifier = modifier,
                selected = selected,
                icon = icon,
                text = applicationContext.getString(textId),
                onClick = onClick,
            )
        )
    }
}
