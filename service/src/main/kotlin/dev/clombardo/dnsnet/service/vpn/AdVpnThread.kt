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

import dev.clombardo.dnsnet.log.loge
import dev.clombardo.dnsnet.log.logi
import dev.clombardo.dnsnet.log.logw
import dev.clombardo.dnsnet.service.db.RuleDatabaseManager
import uniffi.net.BlockLoggerCallback
import uniffi.net.VpnController
import uniffi.net.VpnException
import uniffi.net.VpnResult
import uniffi.net.runVpnNative

class AdVpnThread(
    private val adVpnService: AdVpnService,
    private val notify: (VpnStatus) -> Unit,
    private val blockLoggerCallback: BlockLoggerCallback,
    private val ruleDatabaseManager: RuleDatabaseManager,
) : Runnable {
    companion object {
        private const val MIN_RETRY_TIME = 5
        private const val MAX_RETRY_TIME = 2 * 60
        private const val RETRY_MULTIPLIER = 2

        /* If we had a successful connection for that long, reset retry timeout */
        private const val RETRY_RESET_SEC: Long = 60
    }

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
        ruleDatabaseManager.waitOnInit()

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
            } catch (e: VpnException) {
                when (e) {
                    is VpnException.NoNetwork -> {
                        loge("No active network found. Waiting.", e)
                        notify(VpnStatus.WAITING_FOR_NETWORK)
                        reloadOnInterrupt = true
                    }

                    else -> {
                        loge("Got internal VPN exception", e)
                        notify(VpnStatus.RECONNECTING)
                        reloadOnInterrupt = true
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

    @Throws(VpnException::class)
    private fun runVpn(): VpnResult {
        // Authenticate and configure the virtual network interface.
        return runVpnNative(
            adVpnCallback = adVpnService,
            blockLoggerCallback = blockLoggerCallback,
            vpnController = vpnController,
            ruleDatabase = ruleDatabaseManager.ruleDatabase,
        )
    }
}
