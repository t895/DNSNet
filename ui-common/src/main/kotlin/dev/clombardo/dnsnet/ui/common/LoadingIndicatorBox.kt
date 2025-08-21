/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.IndicatorBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star

object LoadingIndicatorBox {
    val indicatorSize = 32.dp
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingIndicatorBox(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    indicatorMaxDistance: Dp = PullToRefreshDefaults.IndicatorMaxDistance,
) {
    IndicatorBox(
        modifier = modifier,
        state = state,
        isRefreshing = isRefreshing,
        containerColor = containerColor,
        maxDistance = indicatorMaxDistance,
    ) {
        val preloadingIndicatorAlpha by animateFloatAsState(
            targetValue = if (isRefreshing) {
                0f
            } else {
                1f
            },
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        )
        val startPreLoadingIndicatorShape = remember {
            RoundedPolygon(
                numVertices = 12,
                rounding = CornerRounding(1f),
            )
        }
        val endPreLoadingIndicatorShape = remember {
            RoundedPolygon.star(
                numVerticesPerRadius = 12,
                rounding = CornerRounding(1f),
            )
        }
        val preLoadingIndicatorMorph = remember {
            Morph(startPreLoadingIndicatorShape, endPreLoadingIndicatorShape)
        }
        Box(
            modifier = Modifier
                .graphicsLayer {
                    alpha = preloadingIndicatorAlpha
                }
                .size(LoadingIndicatorBox.indicatorSize)
                .drawWithCache {
                    val shape = RotatingMorphShape(
                        morph = preLoadingIndicatorMorph,
                        percentage = state.distanceFraction.coerceIn(0f, 1.2f),
                        rotation = 0f,
                    )
                    val outline = shape.createOutline(size, layoutDirection, Density(density))

                    onDrawBehind {
                        drawOutline(
                            outline = outline,
                            color = color,
                        )
                    }
                }
        )

        val indicatorPolygons = remember {
            LoadingIndicatorDefaults.IndeterminateIndicatorPolygons.toMutableList().apply {
                add(0, endPreLoadingIndicatorShape)
            }
        }
        LoadingIndicator(
            modifier = Modifier
                .size(LoadingIndicatorBox.indicatorSize)
                .graphicsLayer {
                    alpha = 1f - preloadingIndicatorAlpha
                },
            color = color,
            polygons = indicatorPolygons,
        )
    }
}
