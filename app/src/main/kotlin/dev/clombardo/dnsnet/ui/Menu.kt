/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuItem(
    modifier: Modifier = Modifier,
    text: String,
    painter: Painter? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        modifier = modifier
            .sizeIn(minWidth = 112.dp, minHeight = 48.dp, maxWidth = 280.dp),
        text = {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (painter != null) {
                    Icon(
                        painter = painter,
                        contentDescription = text,
                    )
                    Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        enabled = enabled,
        onClick = onClick,
        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
    )
}
