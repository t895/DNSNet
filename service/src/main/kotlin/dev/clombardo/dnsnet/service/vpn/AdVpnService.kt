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

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.clombardo.dnsnet.blocklogger.BlockLogger
import dev.clombardo.dnsnet.log.logd
import dev.clombardo.dnsnet.log.logi
import dev.clombardo.dnsnet.log.logw
import dev.clombardo.dnsnet.notification.NotificationChannels
import dev.clombardo.dnsnet.resources.R
import dev.clombardo.dnsnet.service.NativeBlockLoggerWrapper
import dev.clombardo.dnsnet.service.NativeFileHelperWrapper
import dev.clombardo.dnsnet.service.NetworkState
import dev.clombardo.dnsnet.service.toNative
import dev.clombardo.dnsnet.service.vpn.VpnStatus.Companion.toVpnStatus
import dev.clombardo.dnsnet.settings.ConfigurationManager
import dev.clombardo.dnsnet.settings.Preferences
import dev.clombardo.dnsnet.ui.common.FabState
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.net.AdVpnCallback
import uniffi.net.RuleDatabase
import uniffi.net.RuleDatabaseController

enum class VpnStatus(val value: Int) {
    /**
     * The service is not running and all of its resources have been released.
     *
     * This is the default state. It can transition to [STARTING] or be transitioned to from [STOPPING].
     */
    STOPPED(0),

    /**
     * The service is running but is still loading its resources and has not started the main loop yet.
     *
     * This can transition to [WAITING_FOR_NETWORK] if no network is connected or [RUNNING] if all
     * resources are loaded and the main loop starts. It can also be transitioned to from [WAITING_FOR_NETWORK].
     */
    STARTING(1),

    /**
     * The service is running but is waiting for all of its resources to be released before it stops.
     *
     * This can only transition to [STOPPED] once all resources have been released and the service is
     * destroyed. It can be transitioned to from [RUNNING] if we run into irrecoverable errors, or
     * the user stopped the service. Additionally, it can be transitioned to from any other state
     * if the service is being told to shut down.
     */
    STOPPING(2),

    /**
     * The service is running and some or all of its resources may be loaded, but the VPN configuration
     * loop is waiting for a network connection in [AdVpnThread.run].
     *
     * This can transition to [RUNNING] if we lost network connections and then reconnected or to
     * itself if no networks are discovered after a timeout. It can also be transitioned to from
     * [RUNNING] if we lose all network connections.
     */
    WAITING_FOR_NETWORK(3),

    /**
     * The service is running and some or all of its resources may be loaded, but the main loop has
     * not fully initialized since it has stopped prior.
     *
     * This can transition to [RUNNING] once the main loop has fully initialized or to [WAITING_FOR_NETWORK]
     * if we lose all network connections. It can also be transitioned to from [RUNNING] if we run
     * into a recoverable error, switch networks, or see a VPN configuration change.
     */
    RECONNECTING(4),

    /**
     * The service is running, all of its resources have been loaded, and the main loop is running.
     *
     * This can transition to [RECONNECTING] if we run into a recoverable error, switch networks, or
     * see a VPN configuration change, [WAITING_FOR_NETWORK] if we lose all network connections, or
     * [STOPPING] if we are shutting down. It can be transitioned to from [STARTING] if we loaded
     * all of our resources and the main loop is running or [RECONNECTING] if we finished reloading.
     */
    RUNNING(5);

    fun isValidTransition(newStatus: VpnStatus): Boolean {
        return when (this) {
            STOPPED -> {
                when (newStatus) {
                    STARTING -> true
                    else -> false
                }
            }

            STARTING -> {
                when (newStatus) {
                    STOPPING,
                    WAITING_FOR_NETWORK,
                    RUNNING -> true

                    else -> false
                }
            }

            STOPPING -> {
                when (newStatus) {
                    STOPPED -> true
                    else -> false
                }
            }

            WAITING_FOR_NETWORK -> {
                when (newStatus) {
                    RUNNING,
                    WAITING_FOR_NETWORK,
                    STOPPING -> true

                    else -> false
                }
            }

            RECONNECTING -> {
                when (newStatus) {
                    WAITING_FOR_NETWORK,
                    STOPPING,
                    RUNNING -> true

                    else -> false
                }
            }

            RUNNING -> {
                when (newStatus) {
                    STOPPING,
                    WAITING_FOR_NETWORK,
                    RECONNECTING -> true

                    else -> false
                }
            }
        }
    }

    @StringRes
    fun toTextId(): Int =
        when (this) {
            STARTING -> R.string.notification_starting
            RUNNING -> R.string.notification_running
            STOPPING -> R.string.notification_stopping
            WAITING_FOR_NETWORK -> R.string.notification_waiting_for_net
            RECONNECTING -> R.string.notification_reconnecting
            STOPPED -> R.string.notification_stopped
        }

    fun toFabState(): FabState =
        when (this) {
            STOPPED -> FabState.Inactive
            RECONNECTING,
            RUNNING -> FabState.Active

            else -> FabState.Loading
        }

    companion object {
        fun Int.toVpnStatus(): VpnStatus = entries.firstOrNull { it.value == this } ?: STOPPED
    }
}

enum class Command {
    /**
     * Starts the service
     */
    START,

    /**
     * Stops the service
     */
    STOP,

    /**
     * Stops the service and leaves a notification for the user to start the service again
     */
    PAUSE,

    /**
     * Reloads the main loop if we're running
     */
    RECONNECT,

    /**
     * Reloads the rule database if we're running
     */
    RELOAD_DATABASE,
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

        /**
         * Returns true if the service has at least been started
         */
        fun isActive(): Boolean {
            return status.value != VpnStatus.STOPPED
        }

        /**
         * Returns true if the service is running and all of its resources have been loaded
         */
        fun isRunning(): Boolean {
            return status.value == VpnStatus.RUNNING
        }

        fun checkStartVpnOnBoot(
            context: Context,
            configuration: ConfigurationManager,
            preferences: Preferences
        ) {
            if (!configuration.read { autoStart } || !preferences.VpnIsActive) {
                return
            }

            if (prepare(context) != null) {
                logi("VPN preparation not confirmed by user, changing enabled to false")
                configuration.edit { autoStart = false }
                return
            }

            start(context)
        }

        /**
         * Starts the service if it is not active. Does nothing otherwise.
         */
        fun start(context: Context) {
            if (isActive()) {
                logw("VPN is already active")
                return
            }

            ContextCompat.startForegroundService(context, getStartIntent(context))
        }

        /**
         * Stops the service if it is active. Does nothing otherwise.
         */
        fun stop(context: Context) {
            if (!isActive()) {
                logw("VPN is already stopped")
                return
            }

            context.startService(getStopIntent(context))
        }

        /**
         * Starts the service if it is not active and stops it otherwise.
         */
        fun toggle(context: Context) {
            if (isActive()) {
                stop(context)
            } else {
                start(context)
            }
        }

        /**
         * Reloads the main loop and reconfigures if the service is running. Does nothing otherwise.
         */
        fun reconnect(context: Context) {
            if (!isRunning()) {
                logw("VPN is stopped, cannot restart")
                return
            }

            context.startService(getReconnectIntent(context))
        }

        /**
         * Reloads the rule database if the service is active. Does nothing otherwise.
         */
        fun reloadDatabase(context: Context) {
            if (!isActive()) {
                logw("VPN is stopped, cannot reload database")
                return
            }

            context.startService(getReloadDatabaseIntent(context))
        }

        fun getStartIntent(context: Context): Intent = Intent(context, AdVpnService::class.java)
            .putExtra(COMMAND_TAG, Command.START.ordinal)
            .putExtra(
                NOTIFICATION_INTENT_TAG,
                getOpenMainActivityPendingIntent(context)
            )

        fun getStopIntent(context: Context): Intent = Intent(context, AdVpnService::class.java)
            .putExtra(COMMAND_TAG, Command.STOP.ordinal)

        fun getReconnectIntent(context: Context): Intent = Intent(context, AdVpnService::class.java)
            .putExtra(COMMAND_TAG, Command.RECONNECT.ordinal)

        fun getReloadDatabaseIntent(context: Context): Intent =
            Intent(context, AdVpnService::class.java)
                .putExtra(COMMAND_TAG, Command.RELOAD_DATABASE.ordinal)

        fun getOpenMainActivityPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                !!.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )

        private fun getPausePendingIntent(context: Context) = PendingIntent.getService(
            context,
            REQUEST_CODE_PAUSE,
            Intent(context, AdVpnService::class.java)
                .putExtra(COMMAND_TAG, Command.PAUSE.ordinal),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private fun getStartPendingIntent(context: Context) = PendingIntent.getService(
            context,
            REQUEST_CODE_START,
            Intent(context, AdVpnService::class.java).apply {
                putExtra(NOTIFICATION_INTENT_TAG, getOpenMainActivityPendingIntent(context))
                putExtra(COMMAND_TAG, Command.START.ordinal)
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AdVpnServiceEntryPoint {
        fun configuration(): ConfigurationManager
        fun preferences(): Preferences
        fun blockLogger(): BlockLogger
    }

    lateinit var configuration: ConfigurationManager

    lateinit var preferences: Preferences

    lateinit var blockLogger: BlockLogger

    private val handler = Handler(Looper.myLooper()!!, this)

    // Guard against multiple coroutines waiting to reload the database
    private val reloadPending = atomic(false)
    private val ruleDatabaseController = RuleDatabaseController()
    private lateinit var ruleDatabase: RuleDatabase

    private suspend fun RuleDatabase.initialize() = withContext(Dispatchers.IO) {
        initialize(
            androidFileHelper = NativeFileHelperWrapper(this@AdVpnService),
            hostItems = configuration.read { hosts.items.map { it.toNative() } },
            hostExceptions = configuration.read { hosts.exceptions.map { it.toNative() } },
        )
    }

    private fun RuleDatabase.reload() {
        logi("Reloading")
        if (reloadPending.getAndSet(true)) {
            logi("Reload already pending")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            waitOnInit()
            reloadPending.getAndSet(false)
            initialize()
        }
    }

    private lateinit var vpnThread: AdVpnThread

    private val networkState = NetworkState()

    @Synchronized
    private fun onDefaultNetworkChanged(newNetwork: NetworkDetails?) {
        logd("onDefaultNetworkChanged")
        if (newNetwork == null) {
            logd("New network is null")
            networkState.dropDefaultNetwork()
            logd(networkState.toString())

            // The thread will pause at the start and loop while waiting for a network
            reconnectVpn()
            return
        }

        if (networkState.shouldReconnect(newNetwork, status.value)) {
            logi("Default network changed, reconnecting")
            reconnectVpn()
        }

        logd("Setting new default network")
        networkState.setDefaultNetwork(newNetwork)

        logd(networkState.toString())
    }

    private var connectivityLock = Object()
    private var connectivityChangedCallbackRegistered = false
    private val connectivityChangedCallback =
        VpnNetworkCallback(networkState, ::onDefaultNetworkChanged)

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

        // We needed to create a custom entry point and access our dependencies
        // from onCreate because applicationContext is not valid in the constructor
        val accessor = EntryPointAccessors.fromApplication(
            applicationContext,
            AdVpnServiceEntryPoint::class.java
        )
        configuration = accessor.configuration()
        preferences = accessor.preferences()
        blockLogger = accessor.blockLogger()

        ruleDatabase = RuleDatabase(controller = ruleDatabaseController).also {
            CoroutineScope(Dispatchers.IO).launch {
                it.initialize()
            }
        }

        // Action must be added after onCreate or else we'll get an NPE
        runningServiceNotificationBuilder =
            NotificationCompat.Builder(this, NotificationChannels.SERVICE_RUNNING)
                .setSmallIcon(R.drawable.ic_state_deny)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(getOpenMainActivityPendingIntent(this))
                .addAction(
                    0,
                    getString(R.string.notification_action_pause),
                    getPausePendingIntent(this),
                )
        pausedServiceNotification =
            NotificationCompat.Builder(this, NotificationChannels.SERVICE_PAUSED)
                .setSmallIcon(R.drawable.ic_state_deny)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(getOpenMainActivityPendingIntent(this))
                .setContentTitle(getString(R.string.notification_paused_title))
                .addAction(
                    0,
                    getString(R.string.resume),
                    getStartPendingIntent(this)
                )
                .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = if (intent == null) {
            Command.START
        } else {
            Command.entries[intent.getIntExtra(COMMAND_TAG, Command.START.ordinal)]
        }
        logi("Received command - $command")

        when (command) {
            Command.START -> {
                runningServiceNotificationBuilder
                    .setContentTitle(getString(VpnStatus.STARTING.toTextId()))
                startForeground(
                    SERVICE_RUNNING_NOTIFICATION_ID,
                    runningServiceNotificationBuilder.build()
                )

                preferences.VpnIsActive = true
                startVpn()
            }

            Command.STOP -> {
                preferences.VpnIsActive = false
                stopVpn()
            }

            Command.PAUSE -> {
                stopVpn()
                stopForeground(STOP_FOREGROUND_REMOVE)
                with(getSystemService(NotificationManager::class.java)) {
                    notify(
                        SERVICE_PAUSED_NOTIFICATION_ID,
                        pausedServiceNotification
                    )
                }
            }

            Command.RECONNECT -> reconnectVpn()

            Command.RELOAD_DATABASE -> ruleDatabase.reload()
        }

        return START_STICKY
    }

    private fun startVpn() {
        if (prepare(this) != null) {
            stopSelf()
            return
        }

        updateVpnStatus(VpnStatus.STARTING)
        vpnThread = AdVpnThread(
            adVpnService = this,
            notify = { status -> notify(status.ordinal) },
            blockLoggerCallback = NativeBlockLoggerWrapper(blockLogger),
            ruleDatabase = ruleDatabase,
        )
    }

    private fun updateVpnStatus(newStatus: VpnStatus) {
        logi("Updating status ${status.value} -> $newStatus")
        if (!status.value.isValidTransition(newStatus)) {
            logw("Attempted invalid status transition! Ignoring - ${status.value} -> $newStatus")
            return
        }

        when (newStatus) {
            VpnStatus.WAITING_FOR_NETWORK,
            VpnStatus.RUNNING -> registerConnectivityChangedCallback()

            VpnStatus.STOPPING -> unregisterConnectivityChangedCallback()

            else -> {}
        }

        with(getSystemService(NotificationManager::class.java)) {
            cancel(SERVICE_PAUSED_NOTIFICATION_ID)
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
        _status.value = newStatus
    }

    private fun reconnectVpn() {
        if (status.value != VpnStatus.RUNNING && status.value != VpnStatus.WAITING_FOR_NETWORK) {
            return
        }

        logd("Reconnecting")
        unregisterConnectivityChangedCallback()
        vpnThread.reconnect()
    }

    private fun stopVpn() {
        logi("Stopping Service")

        updateVpnStatus(VpnStatus.STOPPING)

        if (this::vpnThread.isInitialized) {
            vpnThread.stop()
        }

        blockLogger.save(this)

        ruleDatabaseController.setShouldStop(true)

        updateVpnStatus(VpnStatus.STOPPED)

        stopSelf()
    }

    override fun onDestroy() {
        logi("Destroyed, shutting down")
        super.onDestroy()
        stopVpn()

        // Looks like uniffi gets confused with this setup so we need to destroy these manually
        // to prevent a memory leak. Just wait for it to finish whatever it's doing first.
        ruleDatabase.waitOnInit()
        ruleDatabase.destroy()
        ruleDatabaseController.destroy()
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
