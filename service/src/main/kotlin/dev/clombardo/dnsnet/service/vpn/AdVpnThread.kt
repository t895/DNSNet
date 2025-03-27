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

package dev.clombardo.dnsnet.service.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import dev.clombardo.dnsnet.log.logd
import dev.clombardo.dnsnet.log.loge
import dev.clombardo.dnsnet.log.logi
import dev.clombardo.dnsnet.log.logw
import dev.clombardo.dnsnet.resources.R
import dev.clombardo.dnsnet.settings.AllowListMode
import uniffi.net.BlockLoggerCallback
import uniffi.net.RuleDatabase
import uniffi.net.VpnController
import uniffi.net.VpnException
import uniffi.net.VpnResult
import uniffi.net.runVpnNative
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

class AdVpnThread(
    private val adVpnService: AdVpnService,
    private val notify: (VpnStatus) -> Unit,
    private val blockLoggerCallback: BlockLoggerCallback,
    private val ruleDatabase: RuleDatabase,
) : Runnable {
    companion object {
        private const val MIN_RETRY_TIME = 5
        private const val MAX_RETRY_TIME = 2 * 60
        private const val RETRY_MULTIPLIER = 2

        /* If we had a successful connection for that long, reset retry timeout */
        private const val RETRY_RESET_SEC: Long = 60

        private const val PREFIX_LENGTH = 24

        /**
         * Currently there is no supported way to parse the current Wifi/LTE/etc
         * network. Here we just use the deprecated NetworkInfo API and suppress
         * the warning until a better solution comes along.
         */
        @Suppress("DEPRECATION")
        @Throws(NoNetworkException::class)
        private fun getDnsServers(context: Context): List<InetAddress> {
            val known = HashSet<InetAddress>()
            val out = ArrayList<InetAddress>()

            with(context.getSystemService(VpnService.CONNECTIVITY_SERVICE) as ConnectivityManager) {
                // Seriously, Android? Seriously?
                val activeInfo: NetworkInfo =
                    activeNetworkInfo ?: throw NoNetworkException("No active network")

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
    private val upstreamDnsServers = ArrayList<InetAddress>()

    private val threadLock = Object()
    private val thread = Thread(this, "AdVpnThread")
    private val vpnController = VpnController()
    private var userStop = false

    init {
        thread.start()
        logi("Vpn Thread started")
    }

    fun stop() {
        synchronized(threadLock) {
            logi("Stopping")

            // Tell the Rust code to stop
            vpnController.stop(VpnResult.STOPPING)
            userStop = true
            thread.interrupt()
            try {
                thread.join()
                userStop = false
            } catch (e: InterruptedException) {
                logw("stopThread: Interrupted while joining thread", e)
            }
            logi("Vpn Thread stopped")
        }
    }

    fun reconnect() {
        synchronized(threadLock) {
            logi("Reconnecting")
            vpnController.stop(VpnResult.RECONNECTING)
            thread.interrupt()
        }
    }

    @Synchronized
    override fun run() {
        logi("Starting")
        ruleDatabase.waitOnInit()

        var retryTimeout = MIN_RETRY_TIME
        // Try connecting the vpn continuously
        while (true) {
            val connectTimeMillis: Long = System.currentTimeMillis()

            var reloadOnInterrupt: Boolean
            try {
                // If the function returns, that means it was interrupted
                val result = runVpn()
                retryTimeout = MIN_RETRY_TIME
                when (result) {
                    VpnResult.RECONNECTING,
                    VpnResult.CONTINUING -> {
                        logi("Reconnecting")
                        notify(VpnStatus.RECONNECTING)
                        continue
                    }

                    VpnResult.STOPPING -> {
                        logi("Stopping")
                        break
                    }
                }
            } catch (e: Exception) {
                when (e) {
                    is NoNetworkException -> {
                        loge("No active network found. Waiting.", e)
                        notify(VpnStatus.WAITING_FOR_NETWORK)
                        reloadOnInterrupt = true
                    }

                    is VpnException, is PrepareFailedException -> {
                        if (e is VpnException) {
                            loge("Got internal VPN exception", e)
                        } else {
                            loge("Failed to prepare VPN", e)
                        }
                        notify(VpnStatus.RECONNECTING)
                        reloadOnInterrupt = true
                    }

                    else -> {
                        loge("Thread dropped. Stopping.", e)
                        break
                    }
                }
            }

            if (System.currentTimeMillis() - connectTimeMillis >= RETRY_RESET_SEC * 1000) {
                logi("Resetting timeout")
                retryTimeout = MIN_RETRY_TIME
            }

            // ...wait and try again
            logi("Pausing for $retryTimeout seconds for potential reconnection...")
            try {
                Thread.sleep(retryTimeout.toLong() * 1000)
            } catch (_: InterruptedException) {
                logi("Thread interrupted")
                if (reloadOnInterrupt && !userStop) {
                    continue
                } else {
                    break
                }
            }

            if (retryTimeout < MAX_RETRY_TIME) {
                retryTimeout *= RETRY_MULTIPLIER
            }
        }

        logi("Exiting")
    }

    @Throws(
        NoNetworkException::class,
        VpnException::class,
        PrepareFailedException::class
    )
    private fun runVpn(): VpnResult {
        // Authenticate and configure the virtual network interface.
        val vpnFd = configure() ?: throw PrepareFailedException("Got null descriptor from system")
        return runVpnNative(
            adVpnCallback = adVpnService,
            blockLoggerCallback = blockLoggerCallback,
            upstreamDnsServers = upstreamDnsServers.map { it.address },
            vpnFd = vpnFd.detachFd(),
            vpnController = vpnController,
            ruleDatabase = ruleDatabase,
        )
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
        } else if (addr is Inet6Address) {
            upstreamDnsServers.add(addr)
            ipv6Template!![ipv6Template.size - 1] = (upstreamDnsServers.size + 1).toByte()
            val i6addr = Inet6Address.getByAddress(ipv6Template)
            logi("configure: Adding DNS Server $addr as $i6addr")
            builder.addDnsServer(i6addr)
        }
    }

    fun configurePackages(builder: VpnService.Builder) {
        val allowOnVpn: MutableSet<String> = HashSet()
        val doNotAllowOnVpn: MutableSet<String> = HashSet()

        adVpnService.configuration.read {
            appList.resolve(
                adVpnService.packageName,
                adVpnService.packageManager,
                allowOnVpn,
                doNotAllowOnVpn
            )
        }

        if (adVpnService.configuration.read { appList.defaultMode } == AllowListMode.NOT_ON_VPN) {
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

    @Throws(NoNetworkException::class)
    private fun configure(): ParcelFileDescriptor? {
        logd("Configuring")

        // Get the current DNS servers before starting the VPN
        val dnsServers = getDnsServers(adVpnService)
        logi("Got DNS servers = $dnsServers")

        // Configure a builder while parsing the parameters.
        val builder = adVpnService.Builder()

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

        if (hasIpV6Servers(dnsServers)) {
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
        if (adVpnService.configuration.read { this.dnsServers.enabled }) {
            for (item in adVpnService.configuration.read { this.dnsServers.items }) {
                if (item.enabled) {
                    for (address in item.getAddresses()) {
                        try {
                            newDNSServer(
                                builder,
                                format,
                                ipv6Template,
                                InetAddress.getByName(address)
                            )
                        } catch (e: Exception) {
                            loge("configure: Cannot add custom DNS server", e)
                        }
                    }
                }
            }
        }

        // Add all known DNS servers from local network
        val addLocalDnsServers = adVpnService.configuration.read {
            !this.dnsServers.enabled || this.dnsServers.items.none { it.enabled }
                    || useNetworkDnsServers
        }
        if (addLocalDnsServers) {
            for (addr in dnsServers) {
                try {
                    newDNSServer(builder, format, ipv6Template, addr)
                } catch (e: Exception) {
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

        configurePackages(builder)

        // Create a new interface using the builder and save the parameters.
        val pendingIntent = AdVpnService.getOpenMainActivityPendingIntent(adVpnService)
        val pfd = builder
            .setSession(adVpnService.getString(R.string.app_name))
            .setConfigureIntent(pendingIntent)
            .establish()
        logi("Configured")

        return pfd
    }

    fun hasIpV6Servers(dnsServers: List<InetAddress>): Boolean {
        if (!adVpnService.configuration.read { ipV6Support }) {
            return false
        }

        if (adVpnService.configuration.read { this.dnsServers.enabled }) {
            for (item in adVpnService.configuration.read { this.dnsServers.items }) {
                if (item.enabled && item.addresses.contains(":")) {
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
