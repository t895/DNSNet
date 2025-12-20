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

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.system.OsConstants
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.clombardo.dnsnet.blocklogger.BlockLogger
import dev.clombardo.dnsnet.common.logDebug
import dev.clombardo.dnsnet.common.logError
import dev.clombardo.dnsnet.common.logInfo
import dev.clombardo.dnsnet.common.logWarning
import dev.clombardo.dnsnet.common.NotificationChannels
import dev.clombardo.dnsnet.resources.R
import dev.clombardo.dnsnet.service.NativeBlockLoggerWrapper
import dev.clombardo.dnsnet.service.NetworkState
import dev.clombardo.dnsnet.service.db.RuleDatabaseManager
import dev.clombardo.dnsnet.service.vpn.VpnStatus.Companion.toVpnStatus
import dev.clombardo.dnsnet.settings.AllowListMode
import dev.clombardo.dnsnet.settings.ConfigurationManager
import dev.clombardo.dnsnet.settings.DnsServerType
import dev.clombardo.dnsnet.settings.Preferences
import dev.clombardo.dnsnet.ui.common.FabState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.net.DnsCache
import uniffi.net.VpnCallback
import uniffi.net.ValidateDnsException
import uniffi.net.ValidateDnsResult
import uniffi.net.VpnConfigurationResult
import uniffi.net.VpnController
import uniffi.net.networkHasIpv6Support
import uniffi.net.validateDnsServers
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

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
     * loop is waiting for a network connection in [VpnThread.run].
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

@SuppressLint("VpnServicePolicy")
class DnsNetVpnService : VpnService(), Handler.Callback, VpnCallback {
    companion object {
        const val SERVICE_RUNNING_NOTIFICATION_ID = 1
        const val SERVICE_PAUSED_NOTIFICATION_ID = 2
        const val SERVICE_DOH_ERROR_NOTIFICATION_ID = 3
        const val REQUEST_CODE_START = 43

        const val REQUEST_CODE_PAUSE = 42

        const val VPN_MSG_STATUS_UPDATE = 0

        const val COMMAND_TAG = "COMMAND"
        const val NOTIFICATION_INTENT_TAG = "NOTIFICATION_INTENT"

        private val _status = MutableStateFlow(VpnStatus.STOPPED)
        val status = _status.asStateFlow()

        private const val PREFIX_LENGTH = 24

        /**
         * Returns true if the service has at least been started
         */
        fun isActive(): Boolean {
            return status.value != VpnStatus.STOPPED
        }

        /**
         * Returns true if the service is [VpnStatus.RUNNING] or will transition to it
         */
        fun isRunning(): Boolean {
            return status.value == VpnStatus.RUNNING || status.value == VpnStatus.RECONNECTING ||
                    status.value == VpnStatus.STARTING
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
                logInfo("VPN preparation not confirmed by user, changing enabled to false")
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
                logWarning("VPN is already active")
                return
            }

            ContextCompat.startForegroundService(context, getStartIntent(context))
        }

        /**
         * Stops the service if it is active. Does nothing otherwise.
         */
        fun stop(context: Context) {
            if (!isActive()) {
                logWarning("VPN is already stopped")
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
                logWarning("VPN is stopped, cannot restart")
                return
            }

            context.startService(getReconnectIntent(context))
        }

        /**
         * Reloads the rule database if the service is active. Does nothing otherwise.
         */
        fun reloadDatabase(context: Context) {
            if (!isActive()) {
                logWarning("VPN is stopped, cannot reload database")
                return
            }

            context.startService(getReloadDatabaseIntent(context))
        }

        fun getStartIntent(context: Context): Intent = Intent(context, DnsNetVpnService::class.java)
            .putExtra(COMMAND_TAG, Command.START.ordinal)
            .putExtra(
                NOTIFICATION_INTENT_TAG,
                getOpenMainActivityPendingIntent(context)
            )

        fun getStopIntent(context: Context): Intent = Intent(context, DnsNetVpnService::class.java)
            .putExtra(COMMAND_TAG, Command.STOP.ordinal)

        fun getReconnectIntent(context: Context): Intent = Intent(context, DnsNetVpnService::class.java)
            .putExtra(COMMAND_TAG, Command.RECONNECT.ordinal)

        fun getReloadDatabaseIntent(context: Context): Intent =
            Intent(context, DnsNetVpnService::class.java)
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
            Intent(context, DnsNetVpnService::class.java)
                .putExtra(COMMAND_TAG, Command.PAUSE.ordinal),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private fun getStartPendingIntent(context: Context) = PendingIntent.getService(
            context,
            REQUEST_CODE_START,
            Intent(context, DnsNetVpnService::class.java).apply {
                putExtra(NOTIFICATION_INTENT_TAG, getOpenMainActivityPendingIntent(context))
                putExtra(COMMAND_TAG, Command.START.ordinal)
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface VpnServiceEntryPoint {
        fun configuration(): ConfigurationManager
        fun preferences(): Preferences
        fun blockLogger(): BlockLogger
    }

    lateinit var configuration: ConfigurationManager

    lateinit var preferences: Preferences

    lateinit var blockLogger: BlockLogger

    private val handler = Handler(Looper.myLooper()!!, this)

    private lateinit var ruleDatabaseManager: RuleDatabaseManager

    private lateinit var vpnThread: VpnThread

    private val networkState = NetworkState()

    @Synchronized
    private fun onDefaultNetworkChanged(newNetwork: NetworkDetails?) {
        logDebug("onDefaultNetworkChanged")
        if (newNetwork == null) {
            logDebug("New network is null")
            networkState.dropDefaultNetwork()
            logDebug(networkState.toString())

            // The thread will pause at the start and loop while waiting for a network
            reconnectVpnNetwork()
            return
        }

        if (networkState.shouldReconnect(newNetwork, status.value)) {
            logInfo("Default network changed, reconnecting")
            reconnectVpnNetwork()
        }

        logDebug("Setting new default network")
        networkState.setDefaultNetwork(newNetwork)

        logDebug(networkState.toString())
    }

    private var connectivityLock = Object()
    private var connectivityChangedCallbackRegistered = false
    private val connectivityChangedCallback =
        VpnNetworkCallback(networkState, ::onDefaultNetworkChanged)

    private fun registerConnectivityChangedCallback() {
        synchronized(connectivityLock) {
            if (connectivityChangedCallbackRegistered) {
                logWarning("Connectivity changed callback already registered")
                return
            }

            try {
                getSystemService(ConnectivityManager::class.java)
                    .registerDefaultNetworkCallback(connectivityChangedCallback)
            } catch (e: Exception) {
                logWarning("Failed to register connectivity changed callback", e)
            }
            connectivityChangedCallbackRegistered = true
        }
    }

    private fun unregisterConnectivityChangedCallback() {
        synchronized(connectivityLock) {
            if (!connectivityChangedCallbackRegistered) {
                logWarning("Connectivity changed callback already unregistered")
                return
            }

            try {
                getSystemService(ConnectivityManager::class.java)
                    .unregisterNetworkCallback(connectivityChangedCallback)
            } catch (e: Exception) {
                logWarning("Failed to unregister connectivity changed callback", e)
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
            VpnServiceEntryPoint::class.java
        )
        configuration = accessor.configuration()
        preferences = accessor.preferences()
        blockLogger = accessor.blockLogger()

        ruleDatabaseManager = RuleDatabaseManager(
            context = applicationContext,
            configuration = configuration,
        )
        ruleDatabaseManager.reload()

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
        logInfo("Received command - $command")

        when (command) {
            Command.START -> {
                getSystemService(NotificationManager::class.java)
                    .cancel(SERVICE_DOH_ERROR_NOTIFICATION_ID)
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

            Command.RELOAD_DATABASE -> ruleDatabaseManager.reload()
        }

        return START_STICKY
    }

    private fun startVpn() {
        if (prepare(this) != null) {
            stopSelf()
            return
        }

        updateVpnStatus(VpnStatus.STARTING)
        vpnThread = VpnThread(
            dnsNetVpnService = this,
            notify = { status -> updateStatus(status.ordinal) },
            blockLoggerCallback = if (configuration.read { blockLogging }) {
                NativeBlockLoggerWrapper(blockLogger)
            } else {
                null
            },
            ruleDatabaseManager = ruleDatabaseManager,
            context = applicationContext
        )
    }

    private fun updateVpnStatus(newStatus: VpnStatus) {
        logInfo("Updating status ${status.value} -> $newStatus")
        if (!status.value.isValidTransition(newStatus)) {
            logWarning("Attempted invalid status transition! Ignoring - ${status.value} -> $newStatus")
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

    private fun reconnectVpnNetwork() {
        if (status.value != VpnStatus.RUNNING && status.value != VpnStatus.WAITING_FOR_NETWORK) {
            logDebug("Reconnection rejected. Vpn is either running or waiting for network")
            return
        }

        reconnectVpn()
    }

    private fun reconnectVpn() {
        logDebug("Reconnecting")
        unregisterConnectivityChangedCallback()
        vpnThread.reconnect()
    }

    private fun stopVpn() {
        logInfo("Stopping Service")

        updateVpnStatus(VpnStatus.STOPPING)

        if (this::vpnThread.isInitialized) {
            vpnThread.stop()
        }

        blockLogger.save(this)

        ruleDatabaseManager.setShouldStop(true)

        updateVpnStatus(VpnStatus.STOPPED)

        stopSelf()
    }

    override fun onDestroy() {
        logInfo("Destroyed, shutting down")
        super.onDestroy()
        stopVpn()

        // Looks like uniffi gets confused with this setup so we need to destroy this manually
        // to prevent a memory leak. Just wait for it to finish whatever it's doing first.
        ruleDatabaseManager.destroy()
    }

    fun configurePackages(builder: VpnService.Builder) {
        val allowOnVpn: MutableSet<String> = HashSet()
        val doNotAllowOnVpn: MutableSet<String> = HashSet()

        configuration.read {
            appList.resolve(
                packageName,
                packageManager,
                allowOnVpn,
                doNotAllowOnVpn
            )
        }

        if (configuration.read { appList.defaultMode } == AllowListMode.NOT_ON_VPN) {
            for (app in allowOnVpn) {
                try {
                    logDebug("configure: Allowing $app to use the DNS VPN")
                    builder.addAllowedApplication(app)
                } catch (e: Exception) {
                    logWarning("configure: Cannot disallow", e)
                }
            }
        } else {
            for (app in doNotAllowOnVpn) {
                try {
                    logDebug("configure: Disallowing $app from using the DNS VPN")
                    builder.addDisallowedApplication(app)
                } catch (e: Exception) {
                    logWarning("configure: Cannot disallow", e)
                }
            }
        }
    }

    @Throws(NoNetworkException::class)
    override fun configure(vpnController: VpnController, dnsCache: DnsCache): VpnConfigurationResult {
        logDebug("Configuring")
        val unvalidatedDnsServers = mutableListOf<String>()
        // Get the current DNS servers before starting the VPN
        val localDnsServers = try {
            getDnsServers()
        } catch (e: NoNetworkException) {
            logDebug("configure: No network found", e)
            return VpnConfigurationResult.NoNetwork
        }
        logInfo("configure: Got local DNS servers = $localDnsServers")

        // The VPN's DNS configuration is sensitive to the order of servers that are added.
        // The servers that are added first will be prioritized so we want to add the user-configured
        // servers first. See issue DNSNet/#91.
        configuration.read {
            if (this.dnsServers.enabled) {
                this.dnsServers.getCurrentServers().forEach {
                    unvalidatedDnsServers.addAll(it.getAddresses())
                }
            }
        }

        // Add all known DNS servers from local network
        val addLocalDnsServers = configuration.read {
            val noConfigServersEnabled = this.dnsServers.items.none { it.enabled } || !this.dnsServers.enabled
            if (this.dnsServers.type == DnsServerType.DoH3) {
                noConfigServersEnabled
            } else {
                noConfigServersEnabled || useNetworkDnsServers
            }
        }
        if (addLocalDnsServers) {
            localDnsServers.forEach { unvalidatedDnsServers.add(it.hostAddress!!) }
        }

        // Check if the local network has IPv6 DNS servers. If so, this implies that the network
        // supports IPv6 and we can add an IPv6 address to the builder.
        val ipv6Support = networkHasIpv6Support()
        logDebug("configure: IPv6 support = $ipv6Support")

        logInfo("configure: Unvalidated DNS servers = $unvalidatedDnsServers")
        val validatedDnsServers = try {
            val result = validateDnsServers(
                vpnController = vpnController,
                ipv6Support = ipv6Support,
                userServers = unvalidatedDnsServers,
                dnsCache = dnsCache
            )

            when (result) {
                is ValidateDnsResult.Interrupted -> return VpnConfigurationResult.Interrupted(result.v1)
                is ValidateDnsResult.Success -> result.v1
            }
        } catch (e: ValidateDnsException) {
            logError("Failed to validate DNS servers", e)
            return VpnConfigurationResult.InvalidDnsServers
        }
        logInfo("configure: Valid DNS servers = ${validatedDnsServers.map { it.getAddress().contentToString() }}")

        if (validatedDnsServers.isEmpty()) {
            return VpnConfigurationResult.InvalidDnsServers
        }

        // Configure a builder while parsing the parameters.
        val builder = Builder()

        // Determine a prefix we can use. These are all reserved prefixes for example
        // use, so it's possible they might be blocked.
        var format: String? = null
        for (prefix in arrayOf("192.0.2", "198.51.100", "203.0.113")) {
            try {
                builder.addAddress("$prefix.1", PREFIX_LENGTH)
            } catch (e: IllegalArgumentException) {
                logDebug("configure: Unable to use this prefix: $prefix", e)
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
        if (ipv6Support) {
            try {
                val addr = Inet6Address.getByAddress(ipv6Template)
                logDebug("configure: Adding IPv6 address $addr")
                builder.addAddress(addr, 120)
            } catch (e: Exception) {
                logDebug("configure: Failed to add ipv6 template", e)
                ipv6Template = null
            }
        } else {
            ipv6Template = null
        }

        if (format == null) {
            logWarning("configure: Could not find a prefix to use, directly using DNS servers")
            builder.addAddress("192.168.50.1", PREFIX_LENGTH)
        }

        /* Upstream DNS servers, indexed by our IP */
        validatedDnsServers.forEachIndexed { index, server ->
            try {
                // Optimally we'd allow either one, but the forwarder checks if upstream size is empty, so
                // we really need to acquire both an ipv6 and an ipv4 subnet.
                val address = InetAddress.getByAddress(server.getAddress())
                when (address) {
                    is Inet4Address -> {
                        if (format == null) {
                            logInfo("configure: Ignoring DNS server $address")
                        } else {
                            val alias = String.format(format, index + 2)
                            logInfo("configure: Adding DNS Server $address as $alias")
                            builder.addDnsServer(alias).addRoute(alias, 32)
                        }
                    }

                    is Inet6Address -> {
                        if (ipv6Support) {
                            if (ipv6Template == null) {
                                logInfo("configure: Ignoring DNS server $address")
                            } else {
                                ipv6Template[ipv6Template.size - 1] = (index + 2).toByte()
                                val i6addr = Inet6Address.getByAddress(ipv6Template)
                                logInfo("configure: Adding DNS Server $address. as $i6addr")
                                builder.addDnsServer(i6addr)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logError("configure: Cannot add custom DNS server", e)
            }
        }

        builder.setBlocking(true)

        // Allow applications to bypass the VPN
        builder.allowBypass()

        // Explictly allow both families, so we do not block
        // traffic for ones without DNS servers (issue 129).
        builder.allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        configurePackages(builder)

        // Create a new interface using the builder and save the parameters.
        val pendingIntent = getOpenMainActivityPendingIntent(this)
        val pfd = builder
            .setSession(getString(R.string.app_name))
            .setConfigureIntent(pendingIntent)
            .establish()
        logInfo("Configured")

        return if (pfd == null) {
            logError("configure: Got null descriptor from VpnService.Builder")
            VpnConfigurationResult.BuilderFailure
        } else {
            VpnConfigurationResult.Success(pfd.detachFd(), validatedDnsServers)
        }
    }

    /**
     * Currently there is no supported way to parse the current Wifi/LTE/etc
     * network. Here we just use the deprecated NetworkInfo API and suppress
     * the warning until a better solution comes along.
     */
    @Suppress("DEPRECATION")
    @Throws(NoNetworkException::class)
    private fun getDnsServers(): List<InetAddress> {
        val known = HashSet<InetAddress>()
        val out = ArrayList<InetAddress>()

        with(getSystemService(ConnectivityManager::class.java)) {
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

    override fun updateStatus(nativeStatus: Int) {
        handler.sendMessage(handler.obtainMessage(VPN_MSG_STATUS_UPDATE, nativeStatus, 0))
    }
}
