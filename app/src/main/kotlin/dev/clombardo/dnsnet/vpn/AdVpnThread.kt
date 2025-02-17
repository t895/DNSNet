/* Copyright (C) 2024 Charles Lombardo <clombardo169@gmail.com>
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

/**
 * Currently there is no supported way to parse the current Wifi/LTE/etc
 * network. Here we just use the deprecated NetworkInfo API and suppress
 * the warning until a better solution comes along.
 */
@file:Suppress("DEPRECATION")

package dev.clombardo.dnsnet.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import dev.clombardo.dnsnet.Configuration
import dev.clombardo.dnsnet.FileHelper
import dev.clombardo.dnsnet.MainActivity
import dev.clombardo.dnsnet.config
import dev.clombardo.dnsnet.logd
import dev.clombardo.dnsnet.loge
import dev.clombardo.dnsnet.logi
import dev.clombardo.dnsnet.logw
import org.pcap4j.packet.IpPacket
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Arrays
import java.util.LinkedList
import java.util.Queue

class AdVpnThread(
    private val vpnService: VpnService,
    private val notify: (VpnStatus) -> Unit,
    log: (name: String, allowed: Boolean) -> Unit,
) : Runnable, EventLoop {
    companion object {
        private const val MIN_RETRY_TIME = 5
        private const val MAX_RETRY_TIME = 2 * 60
        private const val RETRY_MULTIPLIER = 2

        /* If we had a successful connection for that long, reset retry timeout */
        private const val RETRY_RESET_SEC: Long = 60

        /* Maximum number of responses we want to wait for */
        const val DNS_MAXIMUM_WAITING = 1024
        const val DNS_TIMEOUT_SEC: Long = 10

        private const val PREFIX_LENGTH = 24

        private const val DNS_RESPONSE_PACKET_SIZE = 1024

        private const val PACKET_SIZE = 32767

        @Throws(VpnNetworkException::class)
        private fun getDnsServers(context: Context): List<InetAddress> {
            val known = HashSet<InetAddress>()
            val out = ArrayList<InetAddress>()

            with(context.getSystemService(VpnService.CONNECTIVITY_SERVICE) as ConnectivityManager) {
                // Seriously, Android? Seriously?
                val activeInfo: NetworkInfo =
                    activeNetworkInfo ?: throw VpnNetworkException("No DNS Server")

                for (nw in allNetworks) {
                    val ni: NetworkInfo = getNetworkInfo(nw) ?: continue
                    if (!ni.isConnected) {
                        continue
                    }
                    if (ni.type != activeInfo.type || ni.subtype != activeInfo.subtype) {
                        continue
                    }

                    val servers = getLinkProperties(nw)?.dnsServers ?: continue
                    for (address in servers) {
                        if (known.add(address)) {
                            out.add(address)
                        }
                    }
                }
            }

            return out
        }
    }

    /* Upstream DNS servers, indexed by our IP */
    val upstreamDnsServers = ArrayList<InetAddress>()

    /* Data to be written to the device */
    private val deviceWrites: Queue<ByteArray> = LinkedList()

    // HashMap that keeps an upper limit of packets
    private val dnsIn: WospList = WospList()

    // The object where we actually handle packets.
    private val dnsPacketProxy = DnsPacketProxy(this, log)

    // Watch dog that checks our connection is alive.
    private val vpnWatchDog = VpnWatchdog()

    private var thread: Thread? = null
    private var blockFd: FileDescriptor? = null
    private var interruptFd: FileDescriptor? = null

    fun startThread() {
        logi("Starting Vpn Thread")
        thread = Thread(this, "AdVpnThread")
        thread?.start()
        logi("Vpn Thread started")
    }

    fun stopThread() {
        logi("Stopping Vpn Thread")
        if (thread != null) {
            thread?.interrupt()
        }

        interruptFd =
            FileHelper.closeOrWarn(interruptFd, "stopThread: Could not close interruptFd")
        try {
            if (thread != null) {
                thread?.join(2000)
            }
        } catch (e: InterruptedException) {
            logw("stopThread: Interrupted while joining thread", e)
        }
        if (thread != null && thread!!.isAlive) {
            logw("stopThread: Could not kill VPN thread, it is still alive")
        } else {
            thread = null
            logi("Vpn Thread stopped")
        }
    }

    @Synchronized
    override fun run() {
        logi("Starting")

        notify(VpnStatus.STARTING)

        // Load the block list
        try {
            dnsPacketProxy.initialize(upstreamDnsServers)
            vpnWatchDog.initialize(config.watchDog)
        } catch (e: InterruptedException) {
            notify(VpnStatus.STOPPED)
            return
        }

        var retryTimeout = MIN_RETRY_TIME
        // Try connecting the vpn continuously
        while (true) {
            var connectTimeMillis: Long = 0
            try {
                connectTimeMillis = System.currentTimeMillis()
                // If the function returns, that means it was interrupted
                runVpn()

                logi("Told to stop")
                notify(VpnStatus.STOPPING)
                break
            } catch (e: InterruptedException) {
                break
            } catch (e: VpnLostConnectionException) {
                // We want to filter out VpnNetworkException from out crash analytics as these
                // are exceptions that we expect to happen from network errors
                logw("Network exception in vpn thread, ignoring and reconnecting", e)
                // If an exception was thrown, show to the user and try again
                notify(VpnStatus.RECONNECTING)
            } catch (e: VpnNetworkException) {
                // Same thing here, but show that there was an error
                logw("Network exception in vpn thread, ignoring and reconnecting", e)
                notify(VpnStatus.RECONNECTING_NETWORK_ERROR)
            } catch (e: Exception) {
                loge("Network exception in vpn thread, reconnecting", e)
                // ExceptionHandler.saveException(e, Thread.currentThread(), null);
                notify(VpnStatus.RECONNECTING_NETWORK_ERROR)
            }

            if (System.currentTimeMillis() - connectTimeMillis >= RETRY_RESET_SEC * 1000) {
                logi("Resetting timeout")
                retryTimeout = MIN_RETRY_TIME
            }

            // ...wait and try again
            logi("Retrying to connect in ${retryTimeout}seconds...")
            try {
                Thread.sleep(retryTimeout.toLong() * 1000)
            } catch (e: InterruptedException) {
                break
            }

            if (retryTimeout < MAX_RETRY_TIME) {
                retryTimeout *= RETRY_MULTIPLIER
            }
        }

        notify(VpnStatus.STOPPED)
        logi("Exiting")
    }

    @Throws(
        InterruptedException::class,
        ErrnoException::class,
        IOException::class,
        VpnNetworkException::class
    )
    private fun runVpn() {
        // Allocate the buffer for a single packet.
        val packet = ByteArray(PACKET_SIZE)

        // A pipe we can interrupt the poll() call with by closing the interruptFd end
        val pipes = Os.pipe()
        interruptFd = pipes[0]
        blockFd = pipes[1]

        // Authenticate and configure the virtual network interface.
        try {
            configure().use { pfd ->
                // Read and write views of the tun device
                val inputStream = FileInputStream(pfd.fileDescriptor)
                val outFd = FileOutputStream(pfd.fileDescriptor)

                // Now we are connected. Set the flag and show the message.
                notify(VpnStatus.RUNNING)

                while (doOne(inputStream, outFd, packet)) {
                    // We keep forwarding packets till something goes wrong.
                }
            }
        } finally {
            blockFd = FileHelper.closeOrWarn(blockFd, "runVpn: Could not close blockFd")
        }
    }

    @Throws(
        IOException::class,
        ErrnoException::class,
        InterruptedException::class,
        VpnNetworkException::class
    )
    private fun doOne(
        inputStream: FileInputStream,
        outFd: FileOutputStream,
        packet: ByteArray
    ): Boolean {
        val deviceFd = StructPollfd()
        deviceFd.fd = inputStream.getFD()
        deviceFd.events = OsConstants.POLLIN.toShort()
        val blockFd = StructPollfd()
        blockFd.fd = this.blockFd
        blockFd.events = (OsConstants.POLLHUP or OsConstants.POLLERR).toShort()

        if (!deviceWrites.isEmpty()) {
            deviceFd.events = (deviceFd.events.toInt() or OsConstants.POLLOUT).toShort()
        }

        val polls = arrayOfNulls<StructPollfd>(2 + dnsIn.size)
        polls[0] = deviceFd
        polls[1] = blockFd
        var offset = -1
        for (i in dnsIn.indices) {
            offset++
            polls[2 + offset] = StructPollfd()
            val pollFd = polls[2 + offset]
            pollFd!!.fd = ParcelFileDescriptor.fromDatagramSocket(dnsIn[i].socket).fileDescriptor
            pollFd.events = OsConstants.POLLIN.toShort()
        }

        logd("doOne: Polling ${polls.size} file descriptors")
        val result = Os.poll(polls, vpnWatchDog.pollTimeout)
        if (result == 0) {
            vpnWatchDog.handleTimeout()
            return true
        }

        if (blockFd.revents.toInt() != 0) {
            logi("Told to stop VPN")
            return false
        }

        // Need to do this before reading from the device, otherwise a new insertion there could
        // invalidate one of the sockets we want to read from either due to size or time out
        // constraints
        if (dnsIn.isNotEmpty()) {
            var i = -1
            val iter = dnsIn.iterator()
            while (iter.hasNext()) {
                i++
                val wosp = iter.next()
                if (polls[i + 2]!!.revents.toInt() and OsConstants.POLLIN != 0) {
                    logd("Read from DNS socket" + wosp.socket)
                    iter.remove()
                    handleRawDnsResponse(wosp.packet, wosp.socket)
                    wosp.socket.close()
                }
            }
        }

        if ((deviceFd.revents.toInt() and OsConstants.POLLOUT) != 0) {
            logd("Write to device")
            writeToDevice(outFd)
        }

        if (deviceFd.revents.toInt() and OsConstants.POLLIN != 0) {
            logd("Read from device")
            readPacketFromDevice(inputStream, packet)
        }

        return true
    }

    @Throws(VpnNetworkException::class)
    private fun writeToDevice(outFd: FileOutputStream) =
        try {
            outFd.write(deviceWrites.poll())
        } catch (e: IOException) {
            logd("writeToDevice: Failed writing", e)
            throw VpnNetworkException("Outgoing VPN output stream closed")
        }

    @Throws(VpnNetworkException::class, SocketException::class)
    private fun readPacketFromDevice(inputStream: FileInputStream, packet: ByteArray) {
        // Read the outgoing packet from the input stream.
        val length: Int = try {
            inputStream.read(packet)
        } catch (e: IOException) {
            throw VpnNetworkException("Cannot read from device", e)
        }

        if (length == 0) {
            logw("Got empty packet!")
            return
        }

        val readPacket = Arrays.copyOfRange(packet, 0, length)

        vpnWatchDog.handlePacket(readPacket)
        dnsPacketProxy.handleDnsRequest(readPacket)
    }

    @Throws(VpnNetworkException::class)
    override fun forwardPacket(packet: DatagramPacket?, requestPacket: IpPacket?) {
        var dnsSocket: DatagramSocket? = null
        try {
            // Packets to be sent to the real DNS server will need to be protected from the VPN
            dnsSocket = DatagramSocket()

            vpnService.protect(dnsSocket)

            dnsSocket.send(packet)

            if (requestPacket != null) {
                dnsIn.add(WaitingOnSocketPacket(dnsSocket, requestPacket))
            } else {
                FileHelper.closeOrWarn(
                    dnsSocket,
                    "handleDnsRequest: Cannot close socket in error"
                )
            }
        } catch (e: IOException) {
            FileHelper.closeOrWarn(dnsSocket, "handleDnsRequest: Cannot close socket in error")
            if (e.cause is ErrnoException) {
                val errnoExc = e.cause as ErrnoException
                if (errnoExc.errno == OsConstants.ENETUNREACH || errnoExc.errno == OsConstants.EPERM) {
                    throw VpnLostConnectionException("Cannot send message:", e)
                }
            }
            logw("handleDnsRequest: Could not send packet to upstream", e)
        }
    }

    @Throws(IOException::class)
    private fun handleRawDnsResponse(parsedPacket: IpPacket, dnsSocket: DatagramSocket) {
        val datagramData = ByteArray(DNS_RESPONSE_PACKET_SIZE)
        val replyPacket = DatagramPacket(datagramData, datagramData.size)
        dnsSocket.receive(replyPacket)
        dnsPacketProxy.handleDnsResponse(parsedPacket, datagramData)
    }

    override fun queueDeviceWrite(packet: IpPacket?) {
        packet ?: return
        deviceWrites.add(packet.rawData)
    }

    @Throws(UnknownHostException::class)
    fun newDNSServer(
        builder: VpnService.Builder,
        format: String?,
        ipv6Template: ByteArray?,
        addr: InetAddress
    ) {
        // Optimally we'd allow either one, but the forwarder checks if upstream size is empty, so
        // we really need to acquire both an ipv6 and an ipv4 subnet.
        if (addr is Inet6Address && ipv6Template == null) {
            logi("newDNSServer: Ignoring DNS server $addr")
        } else if (addr is Inet4Address && format == null) {
            logi("newDNSServer: Ignoring DNS server $addr")
        } else if (addr is Inet4Address) {
            upstreamDnsServers.add(addr)
            val alias = String.format(format!!, upstreamDnsServers.size + 1)
            logi("configure: Adding DNS Server $addr as $alias")
            builder.addDnsServer(alias).addRoute(alias, 32)
            vpnWatchDog.setTarget(InetAddress.getByName(alias))
        } else if (addr is Inet6Address) {
            upstreamDnsServers.add(addr)
            ipv6Template!![ipv6Template.size - 1] = (upstreamDnsServers.size + 1).toByte()
            val i6addr = Inet6Address.getByAddress(ipv6Template)
            logi("configure: Adding DNS Server $addr as $i6addr")
            builder.addDnsServer(i6addr)
            vpnWatchDog.setTarget(i6addr)
        }
    }

    fun configurePackages(builder: VpnService.Builder, config: Configuration) {
        val allowOnVpn: MutableSet<String> = HashSet()
        val doNotAllowOnVpn: MutableSet<String> = HashSet()

        config.appList.resolve(vpnService.packageManager, allowOnVpn, doNotAllowOnVpn)

        if (config.appList.defaultMode == dev.clombardo.dnsnet.AllowListMode.NOT_ON_VPN) {
            for (app in allowOnVpn) {
                try {
                    logd("configure: Allowing $app to use the DNS VPN")
                    builder.addAllowedApplication(app)
                } catch (e: Exception) {
                    logw("configure: Cannot disallow", e)
                }
            }
        } else {
            for (app in doNotAllowOnVpn) {
                try {
                    logd("configure: Disallowing $app from using the DNS VPN")
                    builder.addDisallowedApplication(app)
                } catch (e: Exception) {
                    logw("configure: Cannot disallow", e)
                }
            }
        }
    }

    @Throws(VpnNetworkException::class)
    private fun configure(): ParcelFileDescriptor {
        logi("Configuring $this")

        // Get the current DNS servers before starting the VPN
        val dnsServers = getDnsServers(vpnService)
        logi("Got DNS servers = $dnsServers")

        // Configure a builder while parsing the parameters.
        val builder = vpnService.Builder()

        // Determine a prefix we can use. These are all reserved prefixes for example
        // use, so it's possible they might be blocked.
        var format: String? = null
        for (prefix in arrayOf("192.0.2", "198.51.100", "203.0.113")) {
            try {
                builder.addAddress("$prefix.1", PREFIX_LENGTH)
            } catch (e: IllegalArgumentException) {
                logd("configure: Unable to use this prefix: $prefix", e)
                continue
            }

            format = "$prefix.%d"
            break
        }

        // For fancy reasons, this is the 2001:db8::/120 subnet of the /32 subnet reserved for
        // documentation purposes. We should do this differently. Anyone have a free /120 subnet
        // for us to use?
        var ipv6Template: ByteArray? =
            byteArrayOf(32, 1, 13, (184 and 0xFF).toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        if (hasIpV6Servers(config, dnsServers)) {
            try {
                val addr = Inet6Address.getByAddress(ipv6Template)
                logd("configure: Adding IPv6 address$addr")
                builder.addAddress(addr, 120)
            } catch (e: Exception) {
                logd("configure: Failed to add ipv6 template", e)
                ipv6Template = null
            }
        } else {
            ipv6Template = null
        }

        if (format == null) {
            logw("configure: Could not find a prefix to use, directly using DNS servers")
            builder.addAddress("192.168.50.1", PREFIX_LENGTH)
        }

        // Add configured DNS servers
        upstreamDnsServers.clear()
        if (config.dnsServers.enabled) {
            for (item in config.dnsServers.items) {
                if (item.enabled) {
                    try {
                        newDNSServer(
                            builder,
                            format,
                            ipv6Template,
                            InetAddress.getByName(item.location)
                        )
                    } catch (e: Exception) {
                        loge("configure: Cannot add custom DNS server", e)
                    }
                }
            }
        }

        // Add all known DNS servers
        if (!config.dnsServers.enabled) {
            for (addr in dnsServers) {
                try {
                    newDNSServer(builder, format, ipv6Template, addr)
                } catch (e: java.lang.Exception) {
                    loge("configure: Cannot add server:", e)
                }
            }
        }

        builder.setBlocking(true)

        // Allow applications to bypass the VPN
        builder.allowBypass()

        // Explictly allow both families, so we do not block
        // traffic for ones without DNS servers (issue 129).
        builder.allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)

        // Set the VPN to unmetered
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        configurePackages(builder, config)

        // Create a new interface using the builder and save the parameters.
        val pendingIntent = PendingIntent.getActivity(
            vpnService,
            1,
            Intent(vpnService, MainActivity::class.java),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pfd = builder
            .setSession("DNSNet")
            .setConfigureIntent(pendingIntent)
            .establish()
        logi("Configured")

        return pfd!!
    }

    fun hasIpV6Servers(config: Configuration, dnsServers: List<InetAddress>): Boolean {
        if (!config.ipV6Support) {
            return false
        }

        if (config.dnsServers.enabled) {
            for (item in config.dnsServers.items) {
                if (item.enabled && item.location.contains(":")) {
                    return true
                }
            }
        }

        for (inetAddress in dnsServers) {
            if (inetAddress is Inet6Address) {
                return true
            }
        }

        return false
    }
}
