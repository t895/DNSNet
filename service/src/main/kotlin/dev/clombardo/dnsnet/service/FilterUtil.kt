/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.service

import android.content.Context
import dev.clombardo.dnsnet.common.FileHelper
import dev.clombardo.dnsnet.common.logInfo
import dev.clombardo.dnsnet.settings.Filter
import dev.clombardo.dnsnet.settings.FilterState
import dev.clombardo.dnsnet.settings.Settings
import uniffi.net_bindings.FilterBinding
import uniffi.net_bindings.FilterStateBinding
import java.io.IOException

object FilterUtil {
    /**
     * Check if all configured filter files exist.
     *
     * @return true if all filter files exist or no filter files were configured.
     */
    fun areFilterFilesExistent(context: Context, settings: Settings): Boolean {
        for (item in settings.filters.files.get()) {
            if (item.state != FilterState.IGNORE) {
                try {
                    val reader =
                        FileHelper.openPath(context, item.data) ?: return false
                    reader.close()
                } catch (e: IOException) {
                    logInfo("areFilterFilesExistent: Failed to open file {$item}", e)
                    return false
                }
            }
        }
        return true
    }
}

fun FilterState.toNative(): FilterStateBinding =
    FilterStateBinding.entries.getOrNull(ordinal) ?: FilterStateBinding.IGNORE

fun Filter.toNative(): FilterBinding = FilterBinding(title, data, state.toNative())
