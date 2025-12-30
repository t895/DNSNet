/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.service

import dev.clombardo.dnsnet.blocklogger.BlockLogger
import uniffi.net_bindings.BlockLoggerBinding

class NativeBlockLoggerWrapper(private val logger: BlockLogger): BlockLoggerBinding {
    override fun logConnection(connectionName: String, allowed: Boolean) =
        logger.newConnection(connectionName, allowed)
}
