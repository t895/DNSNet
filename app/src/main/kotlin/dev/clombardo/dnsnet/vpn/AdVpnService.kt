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
import dev.clombardo.dnsnet.vpn.VpnStatus.Companion.toVpnStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
}

class AdVpnService : VpnService(), Handler.Callback {
    companion object {
        const val SERVICE_NOTIFICATION_ID = 1
        const val REQUEST_CODE_START = 43

        const val REQUEST_CODE_PAUSE = 42

        const val VPN_MSG_STATUS_UPDATE = 0

        const val COMMAND_TAG = "COMMAND"
        const val NOTIFICATION_INTENT_TAG = "NOTIFICATION_INTENT"

        private val _status = MutableStateFlow(VpnStatus.STOPPED)
        val status = _status.asStateFlow()

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
            val intent = Intents.getStartVpnIntent()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intents.getStopVpnIntent())
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

    private var vpnThread: AdVpnThread? = AdVpnThread(
        vpnService = this,
        notify = { status ->
            handler.sendMessage(handler.obtainMessage(VPN_MSG_STATUS_UPDATE, status.ordinal, 0))
        },
        log = { connectionName, allowed ->
            if (config.blockLogging) {
                logger.newConnection(connectionName, allowed)
            }
        }
    )

    private var defaultNetwork: NetworkDetails? = null
    private val connectedNetworks = mutableMapOf<String, NetworkDetails>()

    private fun printNetworks() {
        logd("Networks")
        connectedNetworks.entries.forEach {
            logd(it.value.toString())
        }
    }

    private fun onDefaultNetworkChanged(newNetwork: NetworkDetails?) {
        connectedNetworks.entries.forEach { it.value.default = false }
        if (newNetwork == null) {
            connectedNetworks.remove(defaultNetwork!!.networkId.toString())
            defaultNetwork = null
            printNetworks()
            waitForNetVpn()
            return
        }

        if (shouldReconnect(defaultNetwork, newNetwork)) {
            reconnect()
        }

        newNetwork.default = true
        connectedNetworks[newNetwork.networkId.toString()] = newNetwork
        defaultNetwork = newNetwork

        printNetworks()
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

    private var connectivityChangedCallbackRegistered = false
    private val connectivityChangedCallback = object : NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            super.onCapabilitiesChanged(network, networkCapabilities)

            val networkId = network.toString()
            val networkDetails = connectedNetworks[networkId]
            if (networkDetails == null) {
                val newNetwork = NetworkDetails(
                    default = true,
                    networkId = networkId.toInt(),
                    transports = networkCapabilities.getTransportTypes(),
                )
                onDefaultNetworkChanged(newNetwork)
            } else {
                onDefaultNetworkChanged(
                    networkDetails.copy(
                        default = true,
                        networkId = networkId.toInt(),
                        transports = networkCapabilities.getTransportTypes(),
                    )
                )
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            val networkString = network.toString()
            val lostNetwork = connectedNetworks[networkString]
            if (lostNetwork != null && defaultNetwork != null) {
                if (lostNetwork.networkId == defaultNetwork!!.networkId) {
                    onDefaultNetworkChanged(null)
                }
            }
            connectedNetworks.remove(networkString)
            printNetworks()
        }
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

        val start = {
            Preferences.VpnIsActive = true
            startVpn()
        }

        when (command) {
            Command.START,
            Command.RESUME -> {
                with(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager) {
                    cancel(SERVICE_NOTIFICATION_ID)
                }
                start()
            }

            Command.STOP -> {
                Preferences.VpnIsActive = false
                stopVpn()
            }

            Command.PAUSE -> pauseVpn()
        }

        return START_STICKY
    }

    private fun startVpn() {
        if (prepare(this) != null) {
            stopVpn()
            return
        }

        updateVpnStatus(VpnStatus.STARTING)
        restartVpnThread()
    }

    private fun updateVpnStatus(newStatus: VpnStatus) {
        serviceNotificationBuilder.setContentTitle(getString(newStatus.toTextId()))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(SERVICE_NOTIFICATION_ID, serviceNotificationBuilder.build())
        }
        _status.value = newStatus

        if (!connectivityChangedCallbackRegistered && newStatus == VpnStatus.RUNNING) {
            getSystemService(ConnectivityManager::class.java)
                .registerDefaultNetworkCallback(connectivityChangedCallback)
            connectivityChangedCallbackRegistered = true
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
        if (vpnThread == null) {
            logi("restartVpnThread: Not restarting thread, could not find thread.")
            return
        }

        vpnThread?.stopThread()
        vpnThread?.startThread()
    }

    private fun waitForNetVpn() {
        if (status.value != VpnStatus.RUNNING) {
            return
        }

        vpnThread?.stopThread()
        updateVpnStatus(VpnStatus.WAITING_FOR_NETWORK)
    }

    private fun reconnect() {
        updateVpnStatus(VpnStatus.RECONNECTING)
        restartVpnThread()
    }

    private fun stopVpn() {
        logi("Stopping Service")
        vpnThread?.stopThread() ?: return
        vpnThread = null

        updateVpnStatus(VpnStatus.STOPPED)

        if (connectivityChangedCallbackRegistered) {
            getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(connectivityChangedCallback)
            connectivityChangedCallbackRegistered = false
        }

        logger.save()

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
}
