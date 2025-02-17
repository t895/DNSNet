/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * Derived from DNS66:
 * Copyright (C) 2017 Julian Andres Klode <jak@jak-linux.org>
 *
 * Parsing code derived from AdBuster:
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
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException

/**
 * Ensures that the connection is alive and sets various timeouts and delays in response.
 * <p>
 * The implementation is a bit weird: Success and Failure cases are both handled in the timeout
 * case. When a packet is received, we simply store the time.
 * <p>
 * If poll() times out and we have not seen a packet after we last sent a ping, then we force
 * a reconnect and increase the reconnect delay.
 * <p>
 * If poll() times out and we have seen a packet after we last sent a ping, we increase the
 * poll() time out, causing the next check to run later, and send a ping packet.
 */

class VpnWatchdog {
    companion object {
        // Polling is quadrupled on every success, and values range from 4s to 1h8m.
        private const val POLL_TIMEOUT_START = 1000
        private const val POLL_TIMEOUT_END = 4096000
        private const val POLL_TIMEOUT_WAITING = 7000
        private const val POLL_TIMEOUT_GROW = 4

        // Reconnect penalty ranges from 0s to 5s, in increments of 200 ms.
        private const val INIT_PENALTY_START = 0
        private const val INIT_PENALTY_END = 5000
        private const val INIT_PENALTY_INC = 200

        private const val DNS_PORT = 53
    }

    var initPenalty = INIT_PENALTY_START

    /**
     * Returns the current poll time out.
     */
    var pollTimeout = POLL_TIMEOUT_START
        get() = if (!enabled) {
            -1
        } else if (lastPacketReceived < lastPacketSent) {
            POLL_TIMEOUT_WAITING
        } else {
            field
        }

    // Information about when packets where received.
    var lastPacketSent: Long = 0
    var lastPacketReceived: Long = 0

    private var enabled = false
    private var target: InetAddress? = null

    /**
     * Sets the target address ping packets should be sent to.
     */
    fun setTarget(target: InetAddress) {
        this.target = target
    }

    /**
     * An initialization method. Sleeps the penalty and sends initial packet.
     *
     * @param enabled If the watchdog should be enabled.
     * @throws InterruptedException If interrupted
     */
    @Throws(InterruptedException::class)
    fun initialize(enabled: Boolean) {
        logd("initialize: Initializing watchdog")

        pollTimeout = POLL_TIMEOUT_START
        lastPacketSent = 0
        this.enabled = enabled

        if (!enabled) {
            logd("initialize: Disabled.")
            return
        }

        if (initPenalty > 0) {
            logd("init penalty: Sleeping for " + initPenalty + "ms")
            Thread.sleep(initPenalty.toLong())
        }
    }

    /**
     * Handles a timeout of poll()
     *
     * @throws VpnNetworkException When the watchdog timed out
     */
    @Throws(VpnNetworkException::class)
    fun handleTimeout() {
        if (!enabled) {
            return
        }

        logd(
            "handleTimeout: Milliseconds elapsed between last receive and sent: ${lastPacketReceived - lastPacketSent}"
        )

        // Receive really timed out.
        if (lastPacketReceived < lastPacketSent && lastPacketSent != 0L) {
            initPenalty += INIT_PENALTY_INC
            if (initPenalty > INIT_PENALTY_END) {
                initPenalty = INIT_PENALTY_END
            }
            throw VpnNetworkException("Watchdog timed out")
        }

        // We received a packet after sending it, so we can be more confident and grow our wait
        // time.
        pollTimeout *= POLL_TIMEOUT_GROW
        if (pollTimeout > POLL_TIMEOUT_END) {
            pollTimeout = POLL_TIMEOUT_END
        }

        sendPacket()
    }

    /**
     * Handles an incoming packet on a device.
     *
     * @param packetData The data of the packet
     */
    fun handlePacket(packetData: ByteArray) {
        if (!enabled) {
            return
        }

        logd("handlePacket: Received packet of length ${packetData.size}")
        lastPacketReceived = System.currentTimeMillis()
    }

    /**
     * Sends an empty check-alive packet to the configured target address.
     *
     * @throws VpnNetworkException If sending failed and we should restart
     */
    @Throws(VpnNetworkException::class)
    fun sendPacket() {
        if (!enabled) {
            return
        }

        logd("sendPacket: Sending packet, poll timeout is $pollTimeout")
        val outPacket = DatagramPacket(ByteArray(0), 0, 0, target, DNS_PORT)
        try {
            newDatagramSocket().use {
                it.send(outPacket)
            }
            lastPacketSent = System.currentTimeMillis()
        } catch (e: IOException) {
            throw VpnNetworkException("Received exception", e)
        }
    }

    @Throws(SocketException::class)
    fun newDatagramSocket(): DatagramSocket = DatagramSocket()
}
