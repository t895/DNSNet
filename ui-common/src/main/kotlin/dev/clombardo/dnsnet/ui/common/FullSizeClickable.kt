/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * A [Modifier] that adds [minimumInteractiveComponentSize] and [clickable] without clipping the indication
 *
 * Intended to be used over [androidx.compose.material3.IconButton] in certain circumstances
 */
fun Modifier.fullSizeClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit
) = this.then(
    Modifier
        .clickable(
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
        .minimumInteractiveComponentSize()
)
