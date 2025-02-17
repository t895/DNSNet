/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.ui.Alignment

object Animation {
    val EmphasizedDecelerateEasing by lazy { CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f) }
    val EmphasizedAccelerateEasing by lazy { CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f) }

    val ShowSpinnerHorizontal by lazy {
        fadeIn() + expandHorizontally(
            animationSpec = tween(250),
            expandFrom = Alignment.Start,
            clip = false,
        )
    }
    val HideSpinnerHorizontal by lazy {
        fadeOut() + shrinkHorizontally(
            animationSpec = tween(250),
            shrinkTowards = Alignment.Start,
            clip = false,
        )
    }

    val ShowStatusBarShade by lazy { fadeIn(tween()) }
    val HideStatusBarShade by lazy { fadeOut(tween()) }
}
