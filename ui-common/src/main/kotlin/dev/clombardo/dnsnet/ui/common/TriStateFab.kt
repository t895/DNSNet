/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star

enum class FabState(internal val progress: Float) {
    Inactive(0f),
    Loading(1f),
    Active(2f),
}

object TriStateFab {
    val size = 128.dp
    val safeInsets: WindowInsets
        @Composable get() = WindowInsets.displayCutout.union(WindowInsets.systemBars)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TriStateFab(
    modifier: Modifier = Modifier,
    state: FabState,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val elevation by animateDpAsState(
        targetValue = if (isPressed) 0.dp else 6.dp,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "elevation",
    )
    val containerColor by animateColorAsState(
        targetValue = when (state) {
            FabState.Active -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "containerColor",
    )

    val cornerSize by animateDpAsState(
        targetValue = if (isPressed) 48.dp else 64.dp
    )
    val containerShape by remember {
        derivedStateOf { AbsoluteRoundedCornerShape(cornerSize) }
    }
    Box(
        modifier = modifier
            // Shadow with minimal recompositions
            .graphicsLayer {
                shadowElevation = elevation.toPx()
                shape = containerShape
                clip = false
            }
            // Container transforms with minimal recompositions
            .drawWithCache {
                val outline =
                    containerShape.createOutline(size, layoutDirection, Density(density))
                onDrawBehind {
                    drawOutline(
                        outline = outline,
                        color = containerColor,
                    )
                }
            }
            // Clip ripple
            .graphicsLayer {
                clip = true
                shape = containerShape
            }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
            )
            .size(TriStateFab.size)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val contentColor by animateColorAsState(
            targetValue = when (state) {
                FabState.Active -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onPrimary
            },
            animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
            label = "contentColor",
        )

        val inactiveShape = remember {
            RoundedPolygon(
                numVertices = 3,
                rounding = CornerRounding(0.2f)
            )
        }
        val loadingShape = remember {
            RoundedPolygon.star(
                numVerticesPerRadius = 12,
                radius = 2f,
                rounding = CornerRounding(0.2f)
            )
        }
        val activeShape = remember {
            RoundedPolygon(
                numVertices = 4,
                radius = 0.9f,
                rounding = CornerRounding(0.2f)
            )
        }
        val inactiveToLoadingMorph = remember {
            Morph(inactiveShape, loadingShape)
        }
        val loadingToActiveMorph = remember {
            Morph(loadingShape, activeShape)
        }
        val progress by animateFloatAsState(
            targetValue = state.progress,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        )
        val infiniteTransition = rememberInfiniteTransition()
        val infiniteAnimatedRotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            )
        )
        val animatedRotation by animateFloatAsState(
            targetValue = if (state == FabState.Inactive) {
                360f
            } else if (state == FabState.Active) {
                405f
            } else {
                infiniteAnimatedRotation
            },
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        )
        Box(
            modifier = Modifier
                .size(80.dp)
                .drawWithCache {
                    val shape = if (progress < 1f) {
                        RotatingMorphShape(
                            morph = inactiveToLoadingMorph,
                            percentage = progress,
                            rotation = if (state == FabState.Inactive) {
                                animatedRotation
                            } else {
                                infiniteAnimatedRotation
                            }
                        )
                    } else if (progress > 1f) {
                        RotatingMorphShape(
                            morph = loadingToActiveMorph,
                            percentage = progress - 1f,
                            rotation = if (state == FabState.Active) {
                                animatedRotation
                            } else {
                                infiniteAnimatedRotation
                            }
                        )
                    } else {
                        RotatingMorphShape(
                            morph = inactiveToLoadingMorph,
                            percentage = 1f,
                            rotation = infiniteAnimatedRotation,
                        )
                    }
                    val outline = shape.createOutline(size, layoutDirection, Density(density))
                    onDrawBehind {
                        drawOutline(
                            outline = outline,
                            color = contentColor,
                        )
                    }
                },
        )
    }
}

@Preview
@Composable
private fun TriStateFabPreview() {
    var state by remember { mutableStateOf(FabState.Inactive) }
    TriStateFab(state = state) {
        state = when (state) {
            FabState.Inactive -> FabState.Loading
            FabState.Loading -> FabState.Active
            FabState.Active -> FabState.Inactive
        }
    }
}
