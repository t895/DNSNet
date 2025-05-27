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
import dev.clombardo.dnsnet.log.logInfo
import dev.clombardo.dnsnet.settings.ConfigurationManager
import dev.clombardo.dnsnet.settings.Host
import dev.clombardo.dnsnet.settings.HostState
import uniffi.net.NativeHost
import uniffi.net.NativeHostState
import java.io.IOException

object HostUtil {
    /**
     * Check if all configured hosts files exist.
     *
     * @return true if all host files exist or no host files were configured.
     */
    fun areHostsFilesExistent(context: Context, configuration: ConfigurationManager): Boolean {
        return configuration.read {
            for (item in hosts.items) {
                if (item.state != HostState.IGNORE) {
                    try {
                        val reader =
                            FileHelper.openPath(context, item.data) ?: return@read false
                        reader.close()
                    } catch (e: IOException) {
                        logInfo("areHostFilesExistent: Failed to open file {$item}", e)
                        return@read false
                    }
                }
            }
            return@read true
        }
    }
}

fun HostState.toNative(): NativeHostState =
    try {
        NativeHostState.entries[ordinal]
    } catch (e: IndexOutOfBoundsException) {
        NativeHostState.IGNORE
    }

fun Host.toNative(): NativeHost = NativeHost(title, data, state.toNative())
