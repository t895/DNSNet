/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * Derived from DNS66:
 * Copyright (C) 2016-2019 Julian Andres Klode <jak@jak-linux.org>
 *
 * Derived from AdBuster:
 * Copyright (C) 2016 Daniel Brodie <dbrodie@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Contributions shall also be provided under any later versions of the
 * GPL.
 */

package dev.clombardo.dnsnet.vpn

import dev.clombardo.dnsnet.logd
import java.util.LinkedList

/**
 * Queue of WaitingOnSocketPacket, bound on time and space.
 */
class WospList : LinkedList<WaitingOnSocketPacket>() {
    override fun add(wosp: WaitingOnSocketPacket): Boolean {
        if (size > AdVpnThread.DNS_MAXIMUM_WAITING) {
            logd("Dropping socket due to space constraints: ${element().socket}")
            element().socket.close()
            remove()
        }

        while (!isEmpty() && element().ageSeconds() > AdVpnThread.DNS_TIMEOUT_SEC) {
            logd("Timeout on socket " + element().socket)
            element().socket.close()
            remove()
        }

        return super.add(wosp)
    }
}
