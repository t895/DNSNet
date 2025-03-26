/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.service

import android.content.Context
import dev.clombardo.dnsnet.file.FileHelper
import uniffi.net.AndroidFileHelper

class NativeFileHelperWrapper(private val context: Context) : AndroidFileHelper {
    override fun getHostFd(host: String): Int? =
        FileHelper.getDetachedReadOnlyFd(context, host)
}
