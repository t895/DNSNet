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
import dev.clombardo.dnsnet.common.logError
import dev.clombardo.dnsnet.common.logInfo
import dev.clombardo.dnsnet.common.logWarning
import dev.clombardo.dnsnet.service.NativeFileHelperWrapper
import dev.clombardo.dnsnet.service.db.RuleDatabaseManager
import uniffi.net_bindings.BlockLoggerBinding
import uniffi.net_bindings.VpnControllerBinding
import uniffi.net_bindings.VpnErrorBinding
import uniffi.net_bindings.VpnResultBinding
import uniffi.net_bindings.runVpnNative

class VpnThread(
    private val dnsNetVpnService: DnsNetVpnService,
    private val notify: (VpnStatus) -> Unit,
    private val blockLoggerBinding: BlockLoggerBinding?,
    private val ruleDatabaseManager: RuleDatabaseManager,
    private val context: Context,
    private val isDoh3: Boolean,
) : Runnable {
    companion object {
        private const val MIN_RETRY_TIME = 5
        private const val MAX_RETRY_TIME = 2 * 60
        private const val RETRY_MULTIPLIER = 2
        private const val MAX_IMMEDIATE_RETRIES = 3

        /* If we had a successful connection for that long, reset retry timeout */
        private const val RETRY_RESET_SEC: Long = 60
    }

    private val threadLock = Any()
    private val thread = Thread(this, "VpnThread")
    private val vpnController = VpnControllerBinding()
    private var userStop = false

    init {
        thread.start()
        logInfo("Vpn Thread started")
    }

    fun stop() {
        synchronized(threadLock) {
            logInfo("Stopping")

            // Tell the Rust code to stop
            vpnController.stop(VpnResultBinding.STOPPING)
            userStop = true
            thread.interrupt()
            try {
                thread.join()
                userStop = false
            } catch (e: InterruptedException) {
                logWarning("stopThread: Interrupted while joining thread", e)
            }
            logInfo("Vpn Thread stopped")
        }
    }

    fun reconnect() {
        synchronized(threadLock) {
            logInfo("Reconnecting")
            vpnController.stop(VpnResultBinding.RECONNECTING)
            thread.interrupt()
        }
    }

    @Synchronized
    override fun run() {
        logInfo("Starting")
        ruleDatabaseManager.waitOnInit()

        var immediateRetryCount = 0
        var retryTimeout = MIN_RETRY_TIME
        // Try connecting the vpn continuously
        while (true) {
            val connectTimeMillis: Long = System.currentTimeMillis()
            var reloadOnInterrupt = false
            try {
                // If the function returns, that means it was interrupted
                val result = runVpn(context)
                immediateRetryCount = 0
                retryTimeout = MIN_RETRY_TIME
                when (result) {
                    VpnResultBinding.RECONNECTING,
                    VpnResultBinding.CONTINUING -> {
                        logInfo("Reconnecting")
                        notify(VpnStatus.RECONNECTING)
                        continue
                    }

                    VpnResultBinding.STOPPING -> {
                        logInfo("Stopping")
                        break
                    }
                }
            } catch (e: VpnErrorBinding) {
                reloadOnInterrupt = true
                when (e) {
                    is VpnErrorBinding.NoNetwork -> {
                        logError("No active network found. Waiting.", e)
                        notify(VpnStatus.WAITING_FOR_NETWORK)
                    }

                    is VpnErrorBinding.SocketFailure,
                    is VpnErrorBinding.InvalidDnsServers -> {
                        notify(VpnStatus.RECONNECTING)
                        if (immediateRetryCount < MAX_IMMEDIATE_RETRIES) {
                            logError("Minor error occurred. Retrying immediately.", e)
                            immediateRetryCount++
                            continue
                        }
                    }

                    else -> {
                        logError("Got internal VPN exception", e)
                        notify(VpnStatus.RECONNECTING)
                    }
                }
            }

            if (System.currentTimeMillis() - connectTimeMillis >= RETRY_RESET_SEC * 1000) {
                logInfo("Resetting timeout")
                retryTimeout = MIN_RETRY_TIME
            }

            // ...wait and try again
            logInfo("Pausing for $retryTimeout seconds for potential reconnection...")
            try {
                Thread.sleep(retryTimeout.toLong() * 1000)
            } catch (_: InterruptedException) {
                logInfo("Thread interrupted")
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

        logInfo("Exiting")
    }

    @Throws(VpnErrorBinding::class)
    private fun runVpn(context: Context): VpnResultBinding {
        // Authenticate and configure the virtual network interface.
        return runVpnNative(
            adVpnCallback = dnsNetVpnService,
            blockLoggerCallback = blockLoggerBinding,
            vpnController = vpnController,
            ruleDatabase = ruleDatabaseManager.ruleDatabase,
            androidFileHelper = NativeFileHelperWrapper(context),
            isDoh3 = isDoh3,
        )
    }

    fun destroy() {
        vpnController.destroy()
    }
}
