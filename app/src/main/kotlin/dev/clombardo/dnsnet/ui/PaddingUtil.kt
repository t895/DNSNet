/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp

@Composable
fun PaddingValues.add(
    start: Dp = Dp.Unspecified,
    top: Dp = Dp.Unspecified,
    end: Dp = Dp.Unspecified,
    bottom: Dp = Dp.Unspecified,
): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current

    var currentStart = this.calculateStartPadding(layoutDirection)
    if (start != Dp.Unspecified) {
        currentStart += start
    }

    var currentTop = this.calculateTopPadding()
    if (top != Dp.Unspecified) {
        currentTop += top
    }

    var currentEnd = this.calculateEndPadding(layoutDirection)
    if (end != Dp.Unspecified) {
        currentEnd += end
    }

    var currentBottom = this.calculateBottomPadding()
    if (bottom != Dp.Unspecified) {
        currentBottom += bottom
    }

    return PaddingValues(
        start = currentStart,
        top = currentTop,
        end = currentEnd,
        bottom = currentBottom,
    )
}

@Composable
operator fun PaddingValues.plus(other: PaddingValues): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = this.calculateStartPadding(layoutDirection) + other.calculateStartPadding(layoutDirection),
        top = this.calculateTopPadding() + other.calculateTopPadding(),
        end = this.calculateEndPadding(layoutDirection) + other.calculateEndPadding(layoutDirection),
        bottom = this.calculateBottomPadding() + other.calculateBottomPadding(),
    )
}
