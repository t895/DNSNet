/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.service

import dev.clombardo.dnsnet.settings.Host
import dev.clombardo.dnsnet.settings.HostState
import uniffi.net.NativeHost
import uniffi.net.NativeHostState

fun HostState.toNative(): NativeHostState =
    try {
        NativeHostState.entries[ordinal]
    } catch (e: IndexOutOfBoundsException) {
        NativeHostState.IGNORE
    }

fun Host.toNative(): NativeHost = NativeHost(title, data, state.toNative())
