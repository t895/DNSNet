/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.service

import android.content.Context
import android.os.ParcelFileDescriptor
import dev.clombardo.dnsnet.common.FileHelper
import uniffi.net_bindings.AndroidFileHelper
import java.io.File

class NativeFileHelperWrapper(private val context: Context) : AndroidFileHelper {
    override fun getFilterFileFd(path: String): Int? =
        FileHelper.getDetachedFd(context, path)

    override fun getDnsCacheFileFd(): Int? =
        FileHelper.getDetachedFd(
            context = context,
            path = File(context.externalCacheDir, "dnscache.blob").absolutePath,
            mode = ParcelFileDescriptor.MODE_READ_WRITE
        )
}
