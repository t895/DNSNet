/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

data class DialogButton(
    val modifier: Modifier = Modifier,
    val text: String,
    val onClick: () -> Unit,
)

@Composable
fun BasicDialog(
    modifier: Modifier = Modifier,
    title: String,
    text: String,
    primaryButton: DialogButton,
    secondaryButton: DialogButton? = null,
    tertiaryButton: DialogButton? = null,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        confirmButton = {
            if (tertiaryButton != null) {
                TextButton(
                    modifier = tertiaryButton.modifier,
                    onClick = tertiaryButton.onClick,
                ) {
                    Text(text = tertiaryButton.text)
                }
            }
            if (secondaryButton != null) {
                TextButton(
                    modifier = secondaryButton.modifier,
                    onClick = secondaryButton.onClick,
                ) {
                    Text(text = secondaryButton.text)
                }
            }
            TextButton(
                modifier = primaryButton.modifier,
                onClick = primaryButton.onClick,
            ) {
                Text(text = primaryButton.text)
            }
        },
        title = { Text(text = title) },
        text = { Text(text = text) },
    )
}
