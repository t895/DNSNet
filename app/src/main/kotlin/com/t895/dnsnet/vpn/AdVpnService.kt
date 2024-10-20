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

package com.t895.dnsnet.vpn

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.t895.dnsnet.FileHelper
import com.t895.dnsnet.MainActivity
import com.t895.dnsnet.NotificationChannels
import com.t895.dnsnet.R
import com.t895.dnsnet.vpn.VpnStatus.Companion.toVpnStatus
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

class AdVpnService : VpnService(), Handler.Callback {
    companion object {
        private const val TAG = "VpnService"

        const val NOTIFICATION_ID_STATE = 10
        const val REQUEST_CODE_START = 43

        const val REQUEST_CODE_PAUSE = 42

        const val VPN_MSG_STATUS_UPDATE = 0

        private val _status = MutableStateFlow(VpnStatus.STOPPED)
        val status = _status.asStateFlow()

        private const val logFilename = "connections.json"
        val logger = BlockLogger.load(logFilename)

        fun checkStartVpnOnBoot(context: Context) {
            Log.i("BOOT", "Checking whether to start ad buster on boot")
            val config = FileHelper.loadCurrentSettings()
            if (!config.autoStart) {
                return
            }
            if (!context.getSharedPreferences("state", Context.MODE_PRIVATE)
                    .getBoolean("isActive", false)
            ) {
                return
            }

            if (prepare(context) != null) {
                Log.i("BOOT", "VPN preparation not confirmed by user, changing enabled to false")
            }

            Log.i("BOOT", "Starting ad buster from boot")
            NotificationChannels.onCreate(context)

            val intent = getStartIntent(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun getStartIntent(context: Context): Intent =
            Intent(context, AdVpnService::class.java).apply {
                putExtra("COMMAND", Command.START.ordinal)

                val intent = Intent(context, MainActivity::class.java)
                val pendingIntent =
                    PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                putExtra("NOTIFICATION_INTENT", pendingIntent)
            }

        private fun getResumeIntent(context: Context): Intent =
            Intent(context, AdVpnService::class.java).apply {
                putExtra("COMMAND", Command.RESUME.ordinal)

                val intent = Intent(context, MainActivity::class.java)
                val pendingIntent =
                    PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                putExtra("NOTIFICATION_INTENT", pendingIntent)
            }
    }

    private val handler = Handler(Looper.myLooper()!!, this)

    private var vpnThread: AdVpnThread? = AdVpnThread(
        vpnService = this,
        notify = { status ->
            handler.sendMessage(handler.obtainMessage(VPN_MSG_STATUS_UPDATE, status.ordinal, 0))
        },
        log = if (FileHelper.loadCurrentSettings().blockLogging) {
            { connectionName, allowed ->
                logger.newConnection(connectionName, allowed)
            }
        } else {
            null
        }
    )

    private val connectivityChangedCallback = object : NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            reconnect()
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            waitForNetVpn()
        }
    }

    private val notificationBuilder =
        NotificationCompat.Builder(this, NotificationChannels.SERVICE_RUNNING)
            .setSmallIcon(R.drawable.ic_state_deny)
            .setPriority(NotificationCompat.PRIORITY_MIN)

    private fun getOpenAppIntent(context: Context): PendingIntent {
        val mainActivityIntent = Intent(applicationContext, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
                applicationContext,
                0,
                mainActivityIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.onCreate(this)

        notificationBuilder
            .setContentIntent(getOpenAppIntent(applicationContext))

        val intent = Intent(this, AdVpnService::class.java)
            .putExtra("COMMAND", Command.PAUSE.ordinal)
        val pendingIntent =
            PendingIntent.getService(this, REQUEST_CODE_PAUSE, intent, PendingIntent.FLAG_IMMUTABLE)
        notificationBuilder
            .addAction(
                R.drawable.ic_pause,
                getString(R.string.notification_action_pause),
                pendingIntent
            )

        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        connectivityManager.registerDefaultNetworkCallback(connectivityChangedCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand$intent")
        val command = if (intent == null) {
            Command.START
        } else {
            Command.entries[intent.getIntExtra("COMMAND", Command.START.ordinal)]
        }

        val start = {
            getSharedPreferences("state", MODE_PRIVATE).edit()
                .putBoolean("isActive", true)
                .apply()
            startVpn()
        }

        when (command) {
            Command.RESUME -> {
                with(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager) {
                    cancelAll()
                }
                start()
            }

            Command.START -> start()
            Command.STOP -> {
                getSharedPreferences("state", Context.MODE_PRIVATE).edit()
                    .putBoolean("isActive", false)
                    .apply()
                stopVpn()
            }

            Command.PAUSE -> pauseVpn()
        }

        return Service.START_STICKY
    }

    private fun pauseVpn() {
        stopVpn()
        with(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager) {
            val pendingIntent = PendingIntent.getService(
                this@AdVpnService,
                REQUEST_CODE_START,
                getResumeIntent(this@AdVpnService),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification =
                NotificationCompat.Builder(this@AdVpnService, NotificationChannels.SERVICE_PAUSED)
                    .setSmallIcon(R.drawable.ic_state_deny)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentTitle(getString(R.string.notification_paused_title))
                    .addAction(0, getString(R.string.resume), pendingIntent)
                    .setContentIntent(getOpenAppIntent(applicationContext))
                    .build()
            notify(NOTIFICATION_ID_STATE, notification)
        }
    }

    private fun updateVpnStatus(newStatus: VpnStatus) {
        val notificationTextId = newStatus.toTextId()
        notificationBuilder.setContentTitle(getString(notificationTextId))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ||
            FileHelper.loadCurrentSettings().showNotification
        ) {
            startForeground(NOTIFICATION_ID_STATE, notificationBuilder.build())
        }
        _status.value = newStatus
    }

    private fun startVpn() {
        notificationBuilder.setContentTitle(getString(R.string.app_name))
        updateVpnStatus(VpnStatus.STARTING)

        restartVpnThread()
    }

    private fun restartVpnThread() {
        if (vpnThread == null) {
            Log.i(TAG, "restartVpnThread: Not restarting thread, could not find thread.")
            return
        }

        vpnThread?.stopThread()
        vpnThread?.startThread()
    }

    private fun stopVpnThread() = vpnThread?.stopThread()

    private fun waitForNetVpn() {
        if (status.value != VpnStatus.RUNNING) {
            return
        }

        stopVpnThread()
        updateVpnStatus(VpnStatus.WAITING_FOR_NETWORK)
    }

    private fun reconnect() {
        if (status.value != VpnStatus.WAITING_FOR_NETWORK) {
            return
        }

        updateVpnStatus(VpnStatus.RECONNECTING)
        restartVpnThread()
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping Service")
        if (vpnThread != null) {
            stopVpnThread()
        }
        vpnThread = null

        updateVpnStatus(VpnStatus.STOPPED)

        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        connectivityManager.unregisterNetworkCallback(connectivityChangedCallback)

        logger.save(logFilename)

        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "Destroyed, shutting down")
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
