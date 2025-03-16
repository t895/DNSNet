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

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.clombardo.dnsnet.DnsNetApplication.Companion.applicationContext
import dev.clombardo.dnsnet.Intents
import dev.clombardo.dnsnet.MainActivity
import dev.clombardo.dnsnet.NotificationChannels
import dev.clombardo.dnsnet.Preferences
import dev.clombardo.dnsnet.R
import dev.clombardo.dnsnet.config
import dev.clombardo.dnsnet.logd
import dev.clombardo.dnsnet.logi
import dev.clombardo.dnsnet.logw
import dev.clombardo.dnsnet.vpn.VpnStatus.Companion.toVpnStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.net.AdVpnCallback

enum class VpnStatus {
    STARTING,
    RUNNING,
    STOPPING,
    WAITING_FOR_NETWORK,
    RECONNECTING,
    RECONNECTING_NETWORK_ERROR,
    STOPPED;

    @StringRes
    fun toTextId(): Int =
        when (this) {
            STARTING -> R.string.notification_starting
            RUNNING -> R.string.notification_running
            STOPPING -> R.string.notification_stopping
            WAITING_FOR_NETWORK -> R.string.notification_waiting_for_net
            RECONNECTING -> R.string.notification_reconnecting
            RECONNECTING_NETWORK_ERROR -> R.string.notification_reconnecting_error
            STOPPED -> R.string.notification_stopped
        }

    companion object {
        fun Int.toVpnStatus(): VpnStatus = entries.firstOrNull { it.ordinal == this } ?: STOPPED
    }
}

enum class Command {
    START,
    STOP,
    PAUSE,
    RESUME,
    RESTART,
}

class AdVpnService : VpnService(), Handler.Callback, AdVpnCallback {
    companion object {
        const val SERVICE_RUNNING_NOTIFICATION_ID = 1
        const val SERVICE_PAUSED_NOTIFICATION_ID = 2
        const val REQUEST_CODE_START = 43

        const val REQUEST_CODE_PAUSE = 42

        const val VPN_MSG_STATUS_UPDATE = 0

        const val COMMAND_TAG = "COMMAND"
        const val NOTIFICATION_INTENT_TAG = "NOTIFICATION_INTENT"

        private val _status = MutableStateFlow(VpnStatus.STOPPED)
        val status = _status.asStateFlow()

        fun isRunning(): Boolean {
            return status.value != VpnStatus.STOPPED
        }

        val logger by lazy { BlockLogger.load() }

        fun checkStartVpnOnBoot(context: Context) {
            if (!config.autoStart || !Preferences.VpnIsActive) {
                return
            }

            if (prepare(context) != null) {
                logi("VPN preparation not confirmed by user, changing enabled to false")
                config.autoStart = false
                config.save()
                return
            }

            ContextCompat.startForegroundService(context, Intents.getStartVpnIntent())
        }

        fun start(context: Context) {
            if (isRunning()) {
                logw("VPN is already running")
                return
            }

            ContextCompat.startForegroundService(context, Intents.getStartVpnIntent())
        }

        fun stop(context: Context) {
            if (!isRunning()) {
                logw("VPN is already stopped")
                return
            }

            ContextCompat.startForegroundService(context, Intents.getStopVpnIntent())
        }

        fun restart(context: Context) {
            if (!isRunning()) {
                logw("VPN is stopped, cannot restart")
                return
            }

            ContextCompat.startForegroundService(context, Intents.getRestartVpnIntent())
        }

        private fun getOpenMainActivityPendingIntent() = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private fun getPausePendingIntent() = PendingIntent.getService(
            applicationContext,
            REQUEST_CODE_PAUSE,
            Intent(applicationContext, AdVpnService::class.java)
                .putExtra(COMMAND_TAG, Command.PAUSE.ordinal),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private fun getResumePendingIntent() = PendingIntent.getService(
            applicationContext,
            REQUEST_CODE_START,
            Intent(applicationContext, AdVpnService::class.java).apply {
                putExtra(NOTIFICATION_INTENT_TAG, getOpenMainActivityPendingIntent())
                putExtra(COMMAND_TAG, Command.RESUME.ordinal)
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private val handler = Handler(Looper.myLooper()!!, this)

    private val vpnThread = AdVpnThread(
        adVpnService = this,
        notify = { status -> notify(status.ordinal) },
        blockLoggerCallback = logger,
    )

    internal data class NetworkState(
        private var defaultNetwork: NetworkDetails? = null,
        private val connectedNetworks: MutableMap<String, NetworkDetails> = mutableMapOf(),
    ) {
        private val networkLock = Object()

        fun removeNetwork(networkDetails: NetworkDetails) {
            synchronized(networkLock) {
                connectedNetworks.remove(networkDetails.networkId.toString())
                if (defaultNetwork == networkDetails) {
                    defaultNetwork = null
                }
            }
        }

        fun getDefaultNetwork(): NetworkDetails? {
            return synchronized(networkLock) { defaultNetwork?.copy() }
        }

        fun setDefaultNetwork(networkDetails: NetworkDetails) {
            synchronized(networkLock) {
                defaultNetwork = networkDetails
                connectedNetworks[networkDetails.networkId.toString()] = networkDetails
            }
        }

        fun dropDefaultNetwork() {
            synchronized(networkLock) {
                if (defaultNetwork != null) {
                    connectedNetworks.remove(defaultNetwork!!.networkId.toString())
                    defaultNetwork = null
                }
            }
        }

        fun getConnectedNetwork(networkId: String): NetworkDetails? {
            return synchronized(networkLock) {
                connectedNetworks[networkId]?.copy()
            }
        }

        fun reset() {
            synchronized(networkLock) {
                defaultNetwork = null
                connectedNetworks.clear()
            }
        }

        /**
         * Both the transports and network id used by a given [Network] and its [NetworkCapabilities]
         * object can be different for the same network, so we need a specialized method to see the
         * changes we care about.
         * Specifically, we need to know when our default network has lost the
         * [NetworkCapabilities.TRANSPORT_VPN] transport or one of the other transports have changed.
         * However, we also want to ignore times that the default network goes from having the same
         * transports as the previous network but now includes [NetworkCapabilities.TRANSPORT_VPN].
         * This is because during VPN startup, the default network will receive an update to include the
         * new constant. We can't just rely on checking for the same network id since that too will
         * sometimes change for the same (effective) network.
         */
        fun shouldReconnect(newNetwork: NetworkDetails, currentStatus: VpnStatus): Boolean {
            if (currentStatus == VpnStatus.WAITING_FOR_NETWORK) {
                return true
            }

            synchronized(networkLock) {
                val oldNetwork = defaultNetwork
                if (oldNetwork == null && connectedNetworks.isEmpty()) {
                    return false
                } else if (oldNetwork == null) {
                    return true
                }

                if (oldNetwork.transports == null && newNetwork.transports == null) {
                    return false
                }
                if (oldNetwork.transports != null && newNetwork.transports == null) {
                    return true
                }
                if (oldNetwork.transports == null && newNetwork.transports != null) {
                    return true
                }

                val oldTransports = oldNetwork.transports!!.toMutableList()
                val newTransports = newNetwork.transports!!.toMutableList()
                val oldNetworkHasVpn = oldTransports.remove(NetworkCapabilities.TRANSPORT_VPN)
                val newNetworkHasVpn = newTransports.remove(NetworkCapabilities.TRANSPORT_VPN)
                if (oldNetworkHasVpn && !newNetworkHasVpn) {
                    return true
                }
                if (!oldNetworkHasVpn && newNetworkHasVpn) {
                    return false
                }
                return !oldNetwork.transports.contentEquals(newNetwork.transports)
            }
        }

        override fun toString(): String {
            return synchronized(networkLock) {
                """
                    Default network - ${defaultNetwork.toString()}
                    Connected networks - $connectedNetworks
                """.trimIndent()
            }
        }
    }

    private val networkState = NetworkState()

    @Synchronized
    private fun onDefaultNetworkChanged(newNetwork: NetworkDetails?) {
        logd("onDefaultNetworkChanged")
        if (newNetwork == null) {
            logd("New network is null")
            networkState.dropDefaultNetwork()
            logd(networkState.toString())

            // The thread will pause at the start and loop while waiting for a network
            restartVpnThread()
            return
        }

        if (networkState.shouldReconnect(newNetwork, status.value)) {
            logi("Default network changed, reconnecting")
            reconnect()
        }

        logd("Setting new default network")
        networkState.setDefaultNetwork(newNetwork)

        logd(networkState.toString())
    }

    private var connectivityLock = Object()
    private var connectivityChangedCallbackRegistered = false
    private val connectivityChangedCallback = object : NetworkCallback() {
        @Synchronized
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            logd("onCapabilitiesChanged")
            val networkId = network.toString()
            val networkDetails = networkState.getConnectedNetwork(networkId)
            if (networkDetails == null) {
                val newNetwork = NetworkDetails(
                    networkId = networkId.toInt(),
                    transports = networkCapabilities.getTransportTypes(),
                )
                onDefaultNetworkChanged(newNetwork)
            } else {
                onDefaultNetworkChanged(
                    networkDetails.copy(
                        networkId = networkId.toInt(),
                        transports = networkCapabilities.getTransportTypes(),
                    )
                )
            }
        }

        @Synchronized
        override fun onLost(network: Network) {
            super.onLost(network)
            logd("onLost")
            val networkString = network.toString()
            val lostNetwork = networkState.getConnectedNetwork(networkString)
            if (lostNetwork != null) {
                val defaultNetwork = networkState.getDefaultNetwork()
                if (defaultNetwork != null && lostNetwork.networkId == defaultNetwork.networkId) {
                    onDefaultNetworkChanged(null)
                }
                networkState.removeNetwork(lostNetwork)
            }
            logd(networkState.toString())
        }
    }

    private fun registerConnectivityChangedCallback() {
        synchronized(connectivityLock) {
            if (connectivityChangedCallbackRegistered) {
                logw("Connectivity changed callback already registered")
                return
            }

            try {
                getSystemService(ConnectivityManager::class.java)
                    .registerDefaultNetworkCallback(connectivityChangedCallback)
            } catch (e: Exception) {
                logw("Failed to register connectivity changed callback", e)
            }
            connectivityChangedCallbackRegistered = true
        }
    }

    private fun unregisterConnectivityChangedCallback() {
        synchronized(connectivityLock) {
            if (!connectivityChangedCallbackRegistered) {
                logw("Connectivity changed callback already unregistered")
                return
            }

            try {
                getSystemService(ConnectivityManager::class.java)
                    .unregisterNetworkCallback(connectivityChangedCallback)
            } catch (e: Exception) {
                logw("Failed to unregister connectivity changed callback", e)
            }
            connectivityChangedCallbackRegistered = false

            networkState.reset()
        }
    }

    private lateinit var runningServiceNotificationBuilder: NotificationCompat.Builder

    private lateinit var pausedServiceNotification: Notification

    override fun onCreate() {
        super.onCreate()

        // Action must be added after onCreate or else we'll get an NPE
        runningServiceNotificationBuilder =
            NotificationCompat.Builder(this, NotificationChannels.SERVICE_RUNNING)
                .setSmallIcon(R.drawable.ic_state_deny)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(getOpenMainActivityPendingIntent())
                .addAction(
                    0,
                    getString(R.string.notification_action_pause),
                    getPausePendingIntent(),
                )
        pausedServiceNotification =
            NotificationCompat.Builder(this, NotificationChannels.SERVICE_PAUSED)
                .setSmallIcon(R.drawable.ic_state_deny)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(getOpenMainActivityPendingIntent())
                .setContentTitle(getString(R.string.notification_paused_title))
                .addAction(
                    0,
                    getString(R.string.resume),
                    getResumePendingIntent()
                )
                .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logi("onStartCommand$intent")
        val command = if (intent == null) {
            Command.START
        } else {
            Command.entries[intent.getIntExtra(COMMAND_TAG, Command.START.ordinal)]
        }

        when (command) {
            Command.START,
            Command.RESUME -> {
                with(getSystemService(NotificationManager::class.java)) {
                    cancel(SERVICE_PAUSED_NOTIFICATION_ID)
                }
                Preferences.VpnIsActive = true
                startVpn()
            }

            Command.STOP -> {
                Preferences.VpnIsActive = false
                stopVpn()
            }

            Command.PAUSE -> pauseVpn()

            Command.RESTART -> restartVpnThread()
        }

        return START_STICKY
    }

    private fun startVpn() {
        if (prepare(this) != null) {
            stopVpn()
            return
        }

        updateVpnStatus(VpnStatus.STARTING)
        vpnThread.startThread()
    }

    private fun checkStatusTransition(old: VpnStatus, new: VpnStatus): Boolean {
        if (old == new) {
            return true
        }

        return when (old) {
            VpnStatus.STOPPED -> {
                when (new) {
                    VpnStatus.STARTING -> true
                    else -> false
                }
            }

            VpnStatus.STOPPING -> {
                when (new) {
                    VpnStatus.RUNNING,
                    VpnStatus.STARTING,
                    VpnStatus.WAITING_FOR_NETWORK,
                    VpnStatus.STOPPED -> true

                    else -> false
                }
            }

            VpnStatus.STARTING -> {
                when (new) {
                    VpnStatus.WAITING_FOR_NETWORK,
                    VpnStatus.RECONNECTING_NETWORK_ERROR,
                    VpnStatus.RUNNING -> true
                    else -> false
                }
            }

            VpnStatus.WAITING_FOR_NETWORK -> {
                when (new) {
                    VpnStatus.STARTING -> true
                    else -> false
                }
            }

            VpnStatus.RECONNECTING -> {
                when (new) {
                    VpnStatus.STOPPING -> true
                    else -> false
                }
            }

            VpnStatus.RECONNECTING_NETWORK_ERROR -> {
                when (new) {
                    VpnStatus.STARTING -> true
                    else -> false
                }
            }

            VpnStatus.RUNNING -> {
                when (new) {
                    VpnStatus.STOPPING,
                    VpnStatus.WAITING_FOR_NETWORK,
                    VpnStatus.RECONNECTING,
                    VpnStatus.RECONNECTING_NETWORK_ERROR -> true

                    else -> false
                }
            }
        }
    }

    private fun updateVpnStatus(newStatus: VpnStatus, paused: Boolean = false) {
        if (!checkStatusTransition(status.value, newStatus)) {
            logw("Attempted invalid status transition! Ignoring. - ${status.value} -> $newStatus")
            return
        }

        when (newStatus) {
            VpnStatus.STARTING -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForeground(
                        SERVICE_RUNNING_NOTIFICATION_ID,
                        runningServiceNotificationBuilder.build()
                    )
                }
            }

            VpnStatus.WAITING_FOR_NETWORK,
            VpnStatus.RUNNING -> registerConnectivityChangedCallback()

            VpnStatus.STOPPING -> unregisterConnectivityChangedCallback()

            else -> {}
        }

        with(getSystemService(NotificationManager::class.java)) {
            if (paused) {
                notify(
                    SERVICE_PAUSED_NOTIFICATION_ID,
                    pausedServiceNotification
                )
            } else {
                if (newStatus == VpnStatus.STOPPED) {
                    cancel(SERVICE_RUNNING_NOTIFICATION_ID)
                } else {
                    runningServiceNotificationBuilder.setContentTitle(getString(newStatus.toTextId()))
                    notify(
                        SERVICE_RUNNING_NOTIFICATION_ID,
                        runningServiceNotificationBuilder.build()
                    )
                }
            }
        }
        _status.value = newStatus
    }

    private fun pauseVpn() = stopVpn(paused = true)

    private fun restartVpnThread() {
        logd("Restarting thread")
        unregisterConnectivityChangedCallback()
        vpnThread.stopThread()
        vpnThread.startThread()
        registerConnectivityChangedCallback()
    }

    private fun reconnect() {
        if (status.value != VpnStatus.RUNNING && status.value != VpnStatus.WAITING_FOR_NETWORK) {
            return
        }

        updateVpnStatus(VpnStatus.RECONNECTING)
        restartVpnThread()
    }

    private fun stopVpn(paused: Boolean = false) {
        logi("Stopping Service")

        updateVpnStatus(VpnStatus.STOPPING)

        vpnThread.stopThread()

        logger.save()

        updateVpnStatus(VpnStatus.STOPPED, paused)

        stopSelf()
    }

    override fun onDestroy() {
        logi("Destroyed, shutting down")
        stopVpn()
    }

    override fun handleMessage(msg: Message): Boolean {
        when (msg.what) {
            VPN_MSG_STATUS_UPDATE -> updateVpnStatus(msg.arg1.toVpnStatus())
            else -> throw IllegalArgumentException("Invalid message with what = ${msg.what}")
        }
        return true
    }

    override fun protectRawSocketFd(socketFd: Int): Boolean {
        return protect(socketFd)
    }

    override fun notify(nativeStatus: Int) {
        handler.sendMessage(handler.obtainMessage(VPN_MSG_STATUS_UPDATE, nativeStatus, 0))
    }
}
