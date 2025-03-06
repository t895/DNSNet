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
import kotlinx.atomicfu.atomic
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
        const val SERVICE_NOTIFICATION_ID = 1
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(getStartIntent())
            } else {
                context.startService(getStartIntent())
            }
        }

        fun start(context: Context) {
            if (isRunning()) {
                logw("VPN is already running")
                return
            }

            val intent = Intents.getStartVpnIntent()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            if (!isRunning()) {
                logw("VPN is already stopped")
                return
            }

            context.startService(Intents.getStopVpnIntent())
        }

        fun restart(context: Context) {
            if (!isRunning()) {
                logw("VPN is stopped, cannot restart")
                return
            }

            context.startService(Intents.getRestartVpnIntent())
        }

        private const val NOTIFICATION_ACTION_PENDING_INTENT_FLAGS =
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE

        private fun getOpenMainActivityPendingIntent() = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private fun getStartIntent() = Intent(applicationContext, AdVpnService::class.java).apply {
            putExtra(NOTIFICATION_INTENT_TAG, getOpenMainActivityPendingIntent())
            putExtra(COMMAND_TAG, Command.START.ordinal)
        }

        private fun getPausePendingIntent() = PendingIntent.getService(
            applicationContext,
            REQUEST_CODE_PAUSE,
            Intent(applicationContext, AdVpnService::class.java)
                .putExtra(COMMAND_TAG, Command.PAUSE.ordinal),
            NOTIFICATION_ACTION_PENDING_INTENT_FLAGS,
        )

        private fun getResumePendingIntent() = PendingIntent.getService(
            applicationContext,
            REQUEST_CODE_START,
            Intent(applicationContext, AdVpnService::class.java).apply {
                putExtra(NOTIFICATION_INTENT_TAG, getOpenMainActivityPendingIntent())
                putExtra(COMMAND_TAG, Command.RESUME.ordinal)
            },
            NOTIFICATION_ACTION_PENDING_INTENT_FLAGS,
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
        if (newNetwork == null) {
            networkState.dropDefaultNetwork()
            logd(networkState.toString())
            waitForNetVpn()
            return
        }

        if (shouldReconnect(networkState.getDefaultNetwork(), newNetwork)) {
            logi("Default network changed, reconnecting")
            reconnect()
        }

        networkState.setDefaultNetwork(newNetwork)

        logd(networkState.toString())
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
    private fun shouldReconnect(oldNetwork: NetworkDetails?, newNetwork: NetworkDetails): Boolean {
        if (oldNetwork == null) {
            return false
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

    private var connectivityChangedCallbackRegistered = atomic(false)
    private val connectivityChangedCallback = object : NetworkCallback() {
        @Synchronized
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            super.onCapabilitiesChanged(network, networkCapabilities)
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
        if (connectivityChangedCallbackRegistered.getAndSet(true)) {
            logw("Connectivity changed callback already registered")
            return
        }

        getSystemService(ConnectivityManager::class.java)
            .registerDefaultNetworkCallback(connectivityChangedCallback)
    }

    private fun unregisterConnectivityChangedCallback() {
        if (!connectivityChangedCallbackRegistered.getAndSet(false)) {
            logw("Connectivity changed callback already unregistered")
            return
        }

        getSystemService(ConnectivityManager::class.java)
            .unregisterNetworkCallback(connectivityChangedCallback)

        networkState.reset()
    }

    private val serviceNotificationBuilder =
        NotificationCompat.Builder(this, NotificationChannels.SERVICE_RUNNING)
            .setSmallIcon(R.drawable.ic_state_deny)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(getOpenMainActivityPendingIntent())

    override fun onCreate() {
        super.onCreate()

        // Action must be added after onCreate or else we'll get an NPE
        serviceNotificationBuilder.addAction(
            0,
            getString(R.string.notification_action_pause),
            getPausePendingIntent(),
        )
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
                    cancel(SERVICE_NOTIFICATION_ID)
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

    private fun updateVpnStatus(newStatus: VpnStatus) {
        serviceNotificationBuilder.setContentTitle(getString(newStatus.toTextId()))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(SERVICE_NOTIFICATION_ID, serviceNotificationBuilder.build())
        }
        _status.value = newStatus

        when (newStatus) {
            VpnStatus.STARTING,
            VpnStatus.RUNNING,
            VpnStatus.WAITING_FOR_NETWORK,
            VpnStatus.RECONNECTING,
            VpnStatus.RECONNECTING_NETWORK_ERROR -> registerConnectivityChangedCallback()

            VpnStatus.STOPPING, VpnStatus.STOPPED -> unregisterConnectivityChangedCallback()
        }
    }

    private fun pauseVpn() {
        stopVpn()
        with(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager) {
            val notification =
                NotificationCompat.Builder(this@AdVpnService, NotificationChannels.SERVICE_PAUSED)
                    .setSmallIcon(R.drawable.ic_state_deny)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentTitle(getString(R.string.notification_paused_title))
                    .addAction(0, getString(R.string.resume), getResumePendingIntent())
                    .setContentIntent(getOpenMainActivityPendingIntent())
                    .build()
            notify(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun restartVpnThread() {
        logd("Restarting thread")
        unregisterConnectivityChangedCallback()
        vpnThread.stopThread()
        vpnThread.startThread()
        registerConnectivityChangedCallback()
    }

    private fun waitForNetVpn() {
        if (status.value != VpnStatus.RUNNING) {
            return
        }

        updateVpnStatus(VpnStatus.WAITING_FOR_NETWORK)
        vpnThread.stopThread()
    }

    private fun reconnect() {
        if (status.value != VpnStatus.RUNNING && status.value != VpnStatus.WAITING_FOR_NETWORK) {
            return
        }

        updateVpnStatus(VpnStatus.RECONNECTING)
        restartVpnThread()
    }

    private fun stopVpn() {
        logi("Stopping Service")

        vpnThread.stopThread()

        logger.save()

        updateVpnStatus(VpnStatus.STOPPED)

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
