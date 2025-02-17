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

import dev.clombardo.dnsnet.db.RuleDatabase
import dev.clombardo.dnsnet.logd
import dev.clombardo.dnsnet.loge
import dev.clombardo.dnsnet.logi
import org.pcap4j.packet.IpPacket
import org.pcap4j.packet.IpSelector
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.IpV6Packet
import org.pcap4j.packet.Packet
import org.pcap4j.packet.UdpPacket
import org.pcap4j.packet.UnknownPacket
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.SOARecord
import org.xbill.DNS.Section
import org.xbill.DNS.TextParseException
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.util.Locale

/**
 * Creates and parses packets, and sends packets to a remote socket or the device using
 * {@link AdVpnThread}.
 */
class DnsPacketProxy(
    private val eventLoop: EventLoop,
    private val log: (name: String, allowed: Boolean) -> Unit,
) {
    companion object {
        // Choose a value that is smaller than the time needed to unblock a host.
        private const val NEGATIVE_CACHE_TTL_SECONDS = 5L
        private var NEGATIVE_CACHE_SOA_RECORD: SOARecord = try {
            // Let's use a guaranteed invalid hostname here, clients are not supposed to use
            // our fake values, the whole thing just exists for negative caching.
            val name = Name("dnsnet.dnsnet.invalid.")
            SOARecord(
                name,
                DClass.IN,
                NEGATIVE_CACHE_TTL_SECONDS,
                name,
                name,
                0,
                0,
                0,
                0,
                NEGATIVE_CACHE_TTL_SECONDS
            )
        } catch (e: TextParseException) {
            throw RuntimeException(e)
        }
    }

    private val ruleDatabase = RuleDatabase()
    private var upstreamDnsServers = ArrayList<InetAddress>()

    /**
     * Initializes the rules database and the list of upstream servers.
     *
     * @param upstreamDnsServers The upstream DNS servers to use; or an empty list if no
     *                           rewriting of ip addresses takes place
     * @throws InterruptedException If the database initialization was interrupted
     */
    @Throws(InterruptedException::class)
    fun initialize(upstreamDnsServers: ArrayList<InetAddress>) {
        ruleDatabase.initialize()
        this.upstreamDnsServers = upstreamDnsServers
    }

    /**
     * Handles a responsePayload from an upstream DNS server
     *
     * @param requestPacket   The original request packet
     * @param responsePayload The payload of the response
     */
    fun handleDnsResponse(requestPacket: IpPacket, responsePayload: ByteArray) {
        val udpOutPacket = requestPacket.payload as UdpPacket
        val payloadBuilder = UdpPacket.Builder(udpOutPacket)
            .srcPort(udpOutPacket.header.dstPort)
            .dstPort(udpOutPacket.header.srcPort)
            .srcAddr(requestPacket.header.dstAddr)
            .dstAddr(requestPacket.header.srcAddr)
            .correctChecksumAtBuild(true)
            .correctChecksumAtBuild(true)
            .correctLengthAtBuild(true)
            .payloadBuilder(UnknownPacket.Builder().rawData(responsePayload))

        val ipOutPacket = if (requestPacket is IpV4Packet) {
            IpV4Packet.Builder(requestPacket)
                .srcAddr(requestPacket.header.dstAddr)
                .dstAddr(requestPacket.header.srcAddr)
                .correctChecksumAtBuild(true)
                .correctLengthAtBuild(true)
                .payloadBuilder(payloadBuilder)
                .build()
        } else {
            IpV6Packet.Builder(requestPacket as IpV6Packet)
                .srcAddr(requestPacket.header.dstAddr)
                .dstAddr(requestPacket.header.srcAddr)
                .correctLengthAtBuild(true)
                .payloadBuilder(payloadBuilder)
                .build()
        }

        eventLoop.queueDeviceWrite(ipOutPacket)
    }

    /**
     * Handles a DNS request, by either blocking it or forwarding it to the remote location.
     *
     * @param packetData The packet data to read
     * @throws VpnNetworkException If some network error occurred
     */
    @Throws(VpnNetworkException::class)
    fun handleDnsRequest(packetData: ByteArray) {
        val parsedPacket = try {
            IpSelector.newPacket(packetData, 0, packetData.size) as IpPacket
        } catch (e: Exception) {
            logi("handleDnsRequest: Discarding invalid IP packet", e)
            return
        }

        val parsedUdp: UdpPacket?
        val udpPayload: Packet?
        if (parsedPacket.payload is UdpPacket) {
            parsedUdp = parsedPacket.payload as UdpPacket
            udpPayload = parsedUdp.payload
        } else {
            logi("handleDnsRequest: Discarding unknown packet type ${parsedPacket.header}")
            return
        }

        val destAddr = translateDestinationAddress(parsedPacket) ?: return

        if (udpPayload == null) {
            logi("handleDnsRequest: Sending UDP packet without payload: $parsedUdp")

            // Let's be nice to Firefox. Firefox uses an empty UDP packet to
            // the gateway to reduce the RTT. For further details, please see
            // https://bugzilla.mozilla.org/show_bug.cgi?id=888268
            try {
                val outPacket = DatagramPacket(
                    ByteArray(0),
                    0,
                    0,
                    destAddr,
                    parsedUdp.header.dstPort.valueAsInt()
                )
                eventLoop.forwardPacket(outPacket, null)
            } catch (e: Exception) {
                logi("handleDnsRequest: Could not send empty UDP packet", e)
            }
            return
        }

        val dnsRawData = udpPayload.rawData
        val dnsMsg = try {
            Message(dnsRawData)
        } catch (e: IOException) {
            logi("handleDnsRequest: Discarding non-DNS or invalid packet", e)
            return
        }

        if (dnsMsg.question == null) {
            logi("handleDnsRequest: Discarding DNS packet with no query $dnsMsg")
            return
        }

        val dnsQueryName = dnsMsg.question.name.toString(true).lowercase()
        if (!ruleDatabase.isBlocked(dnsQueryName)) {
            logi("handleDnsRequest: DNS Name $dnsQueryName Allowed, sending to $destAddr")
            log(dnsQueryName, true)
            val outPacket = DatagramPacket(
                dnsRawData,
                0,
                dnsRawData.size,
                destAddr,
                parsedUdp.header.dstPort.valueAsInt()
            )
            eventLoop.forwardPacket(outPacket, parsedPacket)
        } else {
            logi("handleDnsRequest: DNS Name $dnsQueryName Blocked!")
            log(dnsQueryName, false)
            dnsMsg.header.setFlag(Flags.QR.toInt())
            dnsMsg.header.rcode = Rcode.NOERROR
            dnsMsg.addRecord(NEGATIVE_CACHE_SOA_RECORD, Section.AUTHORITY)
            handleDnsResponse(parsedPacket, dnsMsg.toWire())
        }
    }

    /**
     * Translates the destination address in the packet to the real one. In
     * case address translation is not used, this just returns the original one.
     *
     * @param parsedPacket Packet to get destination address for.
     * @return The translated address or null on failure.
     */
    private fun translateDestinationAddress(parsedPacket: IpPacket): InetAddress? {
        val destAddr: InetAddress
        if (upstreamDnsServers.isNotEmpty()) {
            val addr = parsedPacket.header.dstAddr.address
            val index = addr[addr.size - 1] - 2

            try {
                destAddr = upstreamDnsServers[index]
            } catch (e: Exception) {
                loge(
                    """
                        handleDnsRequest: Cannot handle packets to:
                        ${parsedPacket.header.dstAddr.hostAddress}
                        Not a valid address for this network
                    """.trimIndent(),
                    e,
                )
                return null
            }
            logd {
                String.format(
                    Locale.ENGLISH,
                    "handleDnsRequest: Incoming packet to %s AKA %d AKA %s",
                    parsedPacket.header.dstAddr.hostAddress,
                    index,
                    destAddr
                )
            }
        } else {
            destAddr = parsedPacket.header.dstAddr
            logd {
                "handleDnsRequest: Incoming packet to ${parsedPacket.header.dstAddr.hostAddress} - is upstream"
            }
        }
        return destAddr
    }
}
