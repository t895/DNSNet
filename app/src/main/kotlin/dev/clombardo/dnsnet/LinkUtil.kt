/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.UriHandler

/**
 * This prevents a rare crash where a user does not have a web browser installed to open a link.
 * This only happens when someone is messing around with root/custom roms but I'd prefer that they
 * get a friendly error message instead of crashing.
 */
fun UriHandler.tryOpenUri(context: Context, uri: Uri) {
    try {
        openUri(uri.toString())
    } catch (e: Exception) {
        logw("Failed to open link: $uri", e)
        Toast.makeText(context, R.string.failed_to_open_link, Toast.LENGTH_SHORT).show()
    }
}
