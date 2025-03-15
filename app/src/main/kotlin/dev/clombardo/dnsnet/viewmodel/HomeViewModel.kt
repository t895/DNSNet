/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.viewmodel

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.clombardo.dnsnet.DnsNetApplication.Companion.applicationContext
import dev.clombardo.dnsnet.DnsServer
import dev.clombardo.dnsnet.Host
import dev.clombardo.dnsnet.HostException
import dev.clombardo.dnsnet.HostFile
import dev.clombardo.dnsnet.HostState
import dev.clombardo.dnsnet.R
import dev.clombardo.dnsnet.config
import dev.clombardo.dnsnet.db.RuleDatabaseUpdateWorker
import dev.clombardo.dnsnet.logd
import dev.clombardo.dnsnet.logw
import dev.clombardo.dnsnet.ui.App
import dev.clombardo.dnsnet.vpn.AdVpnService
import dev.clombardo.dnsnet.vpn.LoggedConnection
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

class HomeViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _showUpdateIncompleteDialog = MutableStateFlow(false)
    val showUpdateIncompleteDialog = _showUpdateIncompleteDialog.asStateFlow()

    var errors: List<String>? = null

    private var refreshingLock by atomic(false)

    private val _appListRefreshing = MutableStateFlow(false)
    val appListRefreshing = _appListRefreshing.asStateFlow()

    private val _appList = mutableStateListOf<App>()
    val appList: List<App> = _appList

    private val _hosts = mutableStateListOf<Host>()
    val hosts: List<Host> = _hosts

    private val _dnsServers = mutableStateListOf<DnsServer>()
    val dnsServers: List<DnsServer> = _dnsServers

    private val _showHostsFilesNotFoundDialog = MutableStateFlow(false)
    val showHostsFilesNotFoundDialog = _showHostsFilesNotFoundDialog.asStateFlow()

    private val _showFilePermissionDeniedDialog = MutableStateFlow(false)
    val showFilePermissionDeniedDialog = _showFilePermissionDeniedDialog.asStateFlow()

    private val _showVpnConfigurationFailureDialog = MutableStateFlow(false)
    val showVpnConfigurationFailureDialog = _showVpnConfigurationFailureDialog.asStateFlow()

    private val _showDisablePrivateDnsDialog = MutableStateFlow(false)
    val showDisablePrivateDnsDialog = _showDisablePrivateDnsDialog.asStateFlow()

    private val _connectionsLog = mutableStateMapOf<String, LoggedConnection>()
    val connectionsLog: Map<String, LoggedConnection> = _connectionsLog

    private val _showDisableBlockLogWarningDialog = MutableStateFlow(false)
    val showDisableBlockLogWarningDialog = _showDisableBlockLogWarningDialog.asStateFlow()

    private val _showResetSettingsWarningDialog = MutableStateFlow(false)
    val showResetSettingsWarningDialog = _showResetSettingsWarningDialog.asStateFlow()

    private val _showDeleteDnsServerWarningDialog = MutableStateFlow(false)
    val showDeleteDnsServerWarningDialog = _showDeleteDnsServerWarningDialog.asStateFlow()

    private val _showDeleteHostWarningDialog = MutableStateFlow(false)
    val showDeleteHostWarningDialog = _showDeleteHostWarningDialog.asStateFlow()

    private val _showStatusBarShade = MutableStateFlow(true)
    val showStatusBarShade = _showStatusBarShade.asStateFlow()

    private val _isWritingLogcat = MutableStateFlow(false)
    val isWritingLogcat = _isWritingLogcat.asStateFlow()

    private var logcatLock = atomic(false)

    var setupShown: Boolean = savedStateHandle.get<Boolean>(KEY_SETUP_SHOWN) == true
        set(value) {
            savedStateHandle[KEY_SETUP_SHOWN] = value
            field = value
        }

    init {
        _connectionsLog.putAll(AdVpnService.logger.connections)
        AdVpnService.logger.setOnConnectionListener { name, connection ->
            _connectionsLog[name] = connection
        }
        populateAppList()
        _hosts.addAll(config.hosts.getAllHosts())
        _dnsServers.addAll(config.dnsServers.items)
    }

    override fun onCleared() {
        super.onCleared()
        AdVpnService.logger.setOnConnectionListener(null)
    }

    fun onCheckForUpdateErrors() {
        val workerErrors = RuleDatabaseUpdateWorker.lastErrors
        if (!workerErrors.isNullOrEmpty()) {
            _showUpdateIncompleteDialog.value = true
            errors = workerErrors
            RuleDatabaseUpdateWorker.lastErrors = null
        }
    }

    fun onDismissUpdateIncomplete() {
        errors = null
        _showUpdateIncompleteDialog.value = false
    }

    fun populateAppList() {
        if (refreshingLock) {
            return
        }
        refreshingLock = true
        _appListRefreshing.value = true

        val pm = applicationContext.packageManager
        viewModelScope.launch(Dispatchers.IO) {
            val entries = ArrayList<App>()
            val notOnVpn = HashSet<String>()
            config.appList.resolve(pm, HashSet(), notOnVpn)
            pm.getInstalledApplications(0).forEach {
                if (it.packageName != dev.clombardo.dnsnet.BuildConfig.APPLICATION_ID) {
                    entries.add(
                        App(
                            info = it,
                            label = it.loadLabel(pm).toString(),
                            enabled = notOnVpn.contains(it.packageName),
                            isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        )
                    )
                }
            }

            _appList.clear()
            _appList.addAll(entries)
            _appListRefreshing.value = false
            refreshingLock = false
            Runtime.getRuntime().gc()
        }
    }

    fun onHostsFilesNotFound() {
        _showHostsFilesNotFoundDialog.value = true
    }

    fun onDismissHostsFilesNotFound() {
        _showHostsFilesNotFoundDialog.value = false
    }

    private fun addHostFile(host: HostFile) {
        config.hosts.items.add(host)
        _hosts.add(host)
        config.save()
    }

    private fun addHostException(host: HostException) {
        config.hosts.exceptions.add(host)
        _hosts.add(host)
        config.save()
    }

    fun addHost(host: Host) {
        when (host) {
            is HostFile -> addHostFile(host)
            is HostException -> addHostException(host)
        }
    }

    private fun removeHostFile(host: HostFile) {
        if (!config.hosts.items.contains(host)) {
            logw("Tried to remove host that does not exist in config! - $host")
            return
        }
        config.hosts.items.remove(host)
        _hosts.remove(host)
        config.save()
    }

    private fun removeHostException(host: HostException) {
        if (!config.hosts.exceptions.contains(host)) {
            logw("Tried to remove host that does not exist in config! - $host")
            return
        }
        config.hosts.exceptions.remove(host)
        _hosts.remove(host)
        config.save()
    }

    fun removeHost(host: Host) {
        when (host) {
            is HostFile -> removeHostFile(host)
            is HostException -> removeHostException(host)
        }
    }

    private fun replaceHostFile(oldHost: HostFile, newHost: HostFile) {
        if (!config.hosts.items.contains(oldHost)) {
            logw("Tried to replace host that does not exist in config! - $oldHost")
            return
        }
        val oldIndex = config.hosts.items.indexOf(oldHost)
        config.hosts.items[oldIndex] = newHost
        val oldStateIndex = _hosts.indexOf(oldHost)
        _hosts[oldStateIndex] = newHost
        config.save()
    }

    private fun replaceHostException(oldHost: HostException, newHost: HostException) {
        if (!config.hosts.exceptions.contains(oldHost)) {
            logw("Tried to replace host that does not exist in config! - $oldHost")
            return
        }
        val oldIndex = config.hosts.exceptions.indexOf(oldHost)
        config.hosts.exceptions[oldIndex] = newHost
        val oldStateIndex = _hosts.indexOf(oldHost)
        _hosts[oldStateIndex] = newHost
        config.save()
    }

    fun replaceHost(oldHost: Host, newHost: Host) {
        if (oldHost is HostFile && newHost is HostFile) {
            replaceHostFile(oldHost, newHost)
        } else if (oldHost is HostException && newHost is HostException) {
            replaceHostException(oldHost, newHost)
        }
    }

    private fun cycleHostFile(host: HostFile) {
        val newHost = host.copy()
        newHost.state = when (newHost.state) {
            HostState.IGNORE -> HostState.DENY
            HostState.DENY -> HostState.ALLOW
            HostState.ALLOW -> HostState.IGNORE
        }
        replaceHostFile(host, newHost)
    }

    private fun cycleHostException(host: HostException) {
        val newHost = host.copy()
        newHost.state = when (newHost.state) {
            HostState.IGNORE -> HostState.DENY
            HostState.DENY -> HostState.ALLOW
            HostState.ALLOW -> HostState.IGNORE
        }
        replaceHostException(host, newHost)
    }

    fun cycleHost(host: Host) {
        when (host) {
            is HostFile -> cycleHostFile(host)
            is HostException -> cycleHostException(host)
        }
    }

    fun removeBlockLogEntry(hostname: String) {
        AdVpnService.logger.connections.remove(hostname)
        _connectionsLog.remove(hostname)
    }

    fun addDnsServer(server: DnsServer) {
        config.dnsServers.items.add(server)
        _dnsServers.add(server)
        config.save()
    }

    fun removeDnsServer(server: DnsServer) {
        if (!config.dnsServers.items.contains(server)) {
            logw("Tried to remove DnsServer that does not exist in config! - $server")
            return
        }
        config.dnsServers.items.remove(server)
        _dnsServers.remove(server)
        config.save()
    }

    fun replaceDnsServer(
        oldServer: DnsServer,
        newDnsServer: DnsServer
    ) {
        if (!config.dnsServers.items.contains(oldServer)) {
            logw("Tried to replace host that does not exist in config! - $oldServer")
            return
        }
        val oldIndex = config.dnsServers.items.indexOf(oldServer)
        config.dnsServers.items[oldIndex] = newDnsServer
        _dnsServers[oldIndex] = newDnsServer
        config.save()
    }

    fun toggleDnsServer(server: DnsServer) {
        val newServer = server.copy()
        newServer.enabled = !newServer.enabled
        replaceDnsServer(server, newServer)
    }

    fun onReloadSettings() {
        populateAppList()
        _hosts.clear()
        _hosts.addAll(config.hosts.getAllHosts())
        _dnsServers.clear()
        _dnsServers.addAll(config.dnsServers.items)

        if (!config.blockLogging) {
            AdVpnService.logger.clear()
        }
    }

    fun onToggleApp(app: App, enabled: Boolean) {
        if (!appList.contains(app)) {
            logw("Tried to toggle app that does not exist in list! - $app")
            return
        }
        app.enabled = enabled

        if (enabled) {
            config.appList.notOnVpn.add(app.info.packageName)
            config.appList.onVpn.remove(app.info.packageName)
        } else {
            config.appList.notOnVpn.remove(app.info.packageName)
            config.appList.onVpn.add(app.info.packageName)
        }
        config.save()
    }

    fun onFilePermissionDenied() {
        _showFilePermissionDeniedDialog.value = true
    }

    fun onDismissFilePermissionDenied() {
        _showFilePermissionDeniedDialog.value = false
    }

    fun onVpnConfigurationFailure() {
        _showVpnConfigurationFailureDialog.value = true
    }

    fun onDismissVpnConfigurationFailure() {
        _showVpnConfigurationFailureDialog.value = false
    }

    fun onPrivateDnsEnabledWarning() {
        _showDisablePrivateDnsDialog.value = true
    }

    fun onDismissPrivateDnsEnabledWarning() {
        _showDisablePrivateDnsDialog.value = false
    }

    fun onDisableBlockLogWarning() {
        _showDisableBlockLogWarningDialog.value = true
    }

    fun onDismissDisableBlockLogWarning() {
        _showDisableBlockLogWarningDialog.value = false
    }

    fun onResetSettingsWarning() {
        _showResetSettingsWarningDialog.value = true
    }

    fun onDismissResetSettingsDialog() {
        _showResetSettingsWarningDialog.value = false
    }

    fun onDeleteDnsServerWarning() {
        _showDeleteDnsServerWarningDialog.value = true
    }

    fun onDismissDeleteDnsServerWarning() {
        _showDeleteDnsServerWarningDialog.value = false
    }

    fun onDeleteHostWarning() {
        _showDeleteHostWarningDialog.value = true
    }

    fun onDismissDeleteHostWarning() {
        _showDeleteHostWarningDialog.value = false
    }

    fun showStatusBarShade() {
        _showStatusBarShade.value = true
    }

    fun hideStatusBarShade() {
        _showStatusBarShade.value = false
    }

    fun onClearBlockLog() {
        _connectionsLog.clear()
        AdVpnService.logger.clear()
    }

    fun onWriteLogcat(uri: Uri) {
        if (logcatLock.getAndSet(true)) {
            return
        }
        _isWritingLogcat.value = true

        viewModelScope.launch {
            var failed = false
            var proc: Process? = null
            try {
                proc = Runtime.getRuntime().exec("logcat -d")
                applicationContext.contentResolver.openOutputStream(uri)?.bufferedWriter()
                    .use { outputStream ->
                        BufferedReader(InputStreamReader(proc.inputStream)).use { inputStream ->
                            var line: String?
                            while (inputStream.readLine().also { line = it } != null) {
                                outputStream?.write("$line\n")
                            }
                        }
                    }
            } catch (e: Exception) {
                logd("sendLogcat: Not supported", e)
                Toast.makeText(applicationContext, "Not supported: $e", Toast.LENGTH_LONG).show()
                failed = true
            } finally {
                proc?.destroy()
            }

            if (!failed) {
                Toast.makeText(
                    applicationContext,
                    applicationContext.getString(R.string.logcat_written_successfully),
                    Toast.LENGTH_LONG
                ).show()
            }

            logcatLock.getAndSet(false)
            _isWritingLogcat.value = false
        }
    }

    companion object {
        const val KEY_SETUP_SHOWN = "setupShown"
    }
}
