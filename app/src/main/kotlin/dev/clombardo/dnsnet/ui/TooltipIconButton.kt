/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider

@Composable
private fun rememberTooltipPositionProvider(
    spacingBetweenTooltipAndAnchor: Dp = 4.dp
): PopupPositionProvider {
    val tooltipAnchorSpacing =
        with(LocalDensity.current) { spacingBetweenTooltipAndAnchor.roundToPx() }
    return remember(tooltipAnchorSpacing) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2

                // Tooltip prefers to be above the anchor,
                // but if this causes the tooltip to overlap with the anchor
                // then we place it below the anchor
                var y = anchorBounds.top - popupContentSize.height - tooltipAnchorSpacing
                if ((anchorBounds.top..anchorBounds.bottom).contains(y * 2)) {
                    y = anchorBounds.bottom + tooltipAnchorSpacing
                }
                return IntOffset(x, y)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = rememberTooltipPositionProvider(),
        state = rememberTooltipState(),
        focusable = false,
        tooltip = {
            val haptics = LocalHapticFeedback.current
            LaunchedEffect(Unit) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            PlainTooltip { Text(contentDescription) }
        },
    ) {
        IconButton(
            modifier = modifier.semantics { this.contentDescription = contentDescription },
            colors = colors,
            enabled = enabled,
            onClick = onClick,
        ) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
            )
        }
    }
}

@Composable
fun BasicTooltipIconButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    TooltipIconButton(
        modifier = modifier,
        enabled = enabled,
        painter = rememberVectorPainter(icon),
        contentDescription = contentDescription,
        onClick = onClick,
    )
}
