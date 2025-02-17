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

import org.pcap4j.packet.IpPacket
import java.net.DatagramPacket

/**
 * Interface abstracting away [AdVpnThread].
 */
interface EventLoop {
    /**
     * Called to send a packet to a remote location
     *
     * @param packet        The packet to send
     * @param requestPacket If specified, the event loop must wait for a response, and then
     * call [DnsPacketProxy.handleDnsResponse] for the data
     * of the response, with this packet as the first argument.
     */
    @Throws(VpnNetworkException::class)
    fun forwardPacket(packet: DatagramPacket?, requestPacket: IpPacket?)

    /**
     * Write an IP packet to the local TUN device
     *
     * @param packet The packet to write (a response to a DNS request)
     */
    fun queueDeviceWrite(packet: IpPacket?)
}
