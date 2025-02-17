/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable

val topAppBarInsets: WindowInsets
    @Composable
    get() = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)
        .add(WindowInsets.systemBars.only(WindowInsetsSides.Top))
        .add(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
