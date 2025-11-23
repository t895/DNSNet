/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.common.theme

import android.os.Build
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import dev.clombardo.dnsnet.resources.R

@OptIn(ExperimentalTextApi::class)
val displayEmphasizedFontFamily = FontFamily(
    Font(
        resId = R.font.roboto_flex,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(1000),
            FontVariation.grade(150),
            FontVariation.slant(-10f),
            FontVariation.width(60f),
        )
    )
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val AppTypography = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    Typography(
        displayLargeEmphasized = TextStyle(fontFamily = displayEmphasizedFontFamily),
        displayMediumEmphasized = TextStyle(fontFamily = displayEmphasizedFontFamily),
        displaySmallEmphasized = TextStyle(fontFamily = displayEmphasizedFontFamily),
    )
} else {
    Typography()
}
