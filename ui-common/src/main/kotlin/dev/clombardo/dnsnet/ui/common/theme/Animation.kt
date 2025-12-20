/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.common.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object Animation {
    val EmphasizedDecelerateEasing by lazy { CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f) }
    val EmphasizedAccelerateEasing by lazy { CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f) }

    val ShowSpinnerHorizontal: EnterTransition
        @ReadOnlyComposable @Composable get() {
            return fadeIn(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            ) + expandHorizontally(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                expandFrom = Alignment.Start,
                clip = false,
            )
        }

    val HideSpinnerHorizontal: ExitTransition
        @ReadOnlyComposable @Composable get() {
            return fadeOut(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            ) + shrinkHorizontally(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                shrinkTowards = Alignment.Start,
                clip = false,
            )
        }
}
