/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.app.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.clombardo.dnsnet.blocklogger.BlockLogger
import dev.clombardo.dnsnet.blocklogger.LoggedConnection
import dev.clombardo.dnsnet.log.logd
import dev.clombardo.dnsnet.log.logw
import dev.clombardo.dnsnet.settings.BlockList
import dev.clombardo.dnsnet.settings.ConfigurationManager
import dev.clombardo.dnsnet.settings.DnsServer
import dev.clombardo.dnsnet.settings.Host
import dev.clombardo.dnsnet.settings.HostException
import dev.clombardo.dnsnet.settings.HostFile
import dev.clombardo.dnsnet.settings.HostState
import dev.clombardo.dnsnet.settings.Preferences
import dev.clombardo.dnsnet.ui.app.R
import dev.clombardo.dnsnet.ui.app.model.AppData
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject

@SuppressLint("StaticFieldLeak")
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext val context: Context,
    val configuration: ConfigurationManager,
    val preferences: Preferences,
    val blockLogger: BlockLogger,
) : ViewModel() {
    private val _showUpdateIncompleteDialog = MutableStateFlow(false)
    val showUpdateIncompleteDialog = _showUpdateIncompleteDialog.asStateFlow()

    var errors: List<String>? = null

    private var refreshingLock by atomic(false)

    private val _appListRefreshing = MutableStateFlow(false)
    val appListRefreshing = _appListRefreshing.asStateFlow()

    private val _appList = mutableStateListOf<AppData>()
    val appList: List<AppData> = _appList

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

    private val _isWritingLogcat = MutableStateFlow(false)
    val isWritingLogcat = _isWritingLogcat.asStateFlow()

    private var logcatLock = atomic(false)

    var setupShown: Boolean = savedStateHandle.get<Boolean>(KEY_SETUP_SHOWN) == true
        set(value) {
            savedStateHandle[KEY_SETUP_SHOWN] = value
            field = value
        }

    init {
        _connectionsLog.putAll(blockLogger.connections)
        blockLogger.setOnConnectionListener { name, connection ->
            _connectionsLog[name] = connection
        }
        populateAppList()

        _hosts.addAll(configuration.read { hosts.getAllHosts() })
        _dnsServers.addAll(configuration.read { dnsServers.items })
    }

    override fun onCleared() {
        super.onCleared()
        blockLogger.setOnConnectionListener(null)
    }

    fun onCheckForUpdateErrors(workerErrors: List<String>?) {
        if (!workerErrors.isNullOrEmpty()) {
            _showUpdateIncompleteDialog.value = true
            errors = workerErrors
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

        val pm = context.packageManager
        viewModelScope.launch(Dispatchers.IO) {
            val entries = ArrayList<AppData>()
            val notOnVpn = HashSet<String>()
            configuration.read { appList.resolve(context.packageName, pm, HashSet(), notOnVpn) }
            pm.getInstalledApplications(0).forEach {
                if (it.packageName != context.packageName) {
                    entries.add(
                        AppData(
                            packageManager = pm,
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
        configuration.edit {
            hosts.items.add(host)
        }
        _hosts.add(host)
    }

    private fun addHostException(host: HostException) {
        configuration.edit {
            hosts.exceptions.add(host)
        }
        _hosts.add(host)
    }

    fun addHost(host: Host) {
        when (host) {
            is HostFile -> addHostFile(host)
            is HostException -> addHostException(host)
        }
    }

    private fun removeHostFile(host: HostFile) {
        if (configuration.read { !hosts.items.contains(host) }) {
            logw("Tried to remove host that does not exist in config! - $host")
            return
        }

        configuration.edit {
            hosts.items.remove(host)
        }
        _hosts.remove(host)
    }

    private fun removeHostException(host: HostException) {
        if (configuration.read { !hosts.exceptions.contains(host) }) {
            logw("Tried to remove host that does not exist in config! - $host")
            return
        }

        configuration.edit {
            hosts.exceptions.remove(host)
        }
        _hosts.remove(host)
    }

    fun removeHost(host: Host) {
        when (host) {
            is HostFile -> removeHostFile(host)
            is HostException -> removeHostException(host)
        }
    }

    private fun replaceHostFile(oldHost: HostFile, newHost: HostFile) {
        if (configuration.read { !hosts.items.contains(oldHost) }) {
            logw("Tried to replace host that does not exist in config! - $oldHost")
            return
        }

        configuration.edit {
            val oldIndex = hosts.items.indexOf(oldHost)
            hosts.items[oldIndex] = newHost
        }
        val oldStateIndex = _hosts.indexOf(oldHost)
        _hosts[oldStateIndex] = newHost
    }

    private fun replaceHostException(oldHost: HostException, newHost: HostException) {
        if (configuration.read { !hosts.exceptions.contains(oldHost) }) {
            logw("Tried to replace host that does not exist in config! - $oldHost")
            return
        }

        configuration.edit {
            val oldIndex = hosts.exceptions.indexOf(oldHost)
            hosts.exceptions[oldIndex] = newHost
        }
        val oldStateIndex = _hosts.indexOf(oldHost)
        _hosts[oldStateIndex] = newHost
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
        blockLogger.connections.remove(hostname)
        _connectionsLog.remove(hostname)
    }

    fun addDnsServer(server: DnsServer) {
        configuration.edit {
            dnsServers.items.add(server)
        }
        _dnsServers.add(server)
    }

    fun removeDnsServer(server: DnsServer) {
        if (configuration.read { !dnsServers.items.contains(server) }) {
            logw("Tried to remove DnsServer that does not exist in config! - $server")
            return
        }

        configuration.edit {
            dnsServers.items.remove(server)
        }
        _dnsServers.remove(server)
    }

    fun replaceDnsServer(
        oldServer: DnsServer,
        newDnsServer: DnsServer
    ) {
        if (configuration.read { !dnsServers.items.contains(oldServer) }) {
            logw("Tried to replace host that does not exist in config! - $oldServer")
            return
        }

        configuration.edit {
            val oldIndex = dnsServers.items.indexOf(oldServer)
            dnsServers.items[oldIndex] = newDnsServer
        }
        val oldStateIndex = _dnsServers.indexOf(oldServer)
        _dnsServers[oldStateIndex] = newDnsServer
    }

    fun toggleDnsServer(server: DnsServer) {
        val newServer = server.copy()
        newServer.enabled = !newServer.enabled
        replaceDnsServer(server, newServer)
    }

    fun onReloadSettings() {
        populateAppList()
        _hosts.clear()
        _dnsServers.clear()
        configuration.read {
            _hosts.addAll(hosts.getAllHosts())
            _dnsServers.addAll(dnsServers.items)
            if (!blockLogging) {
                blockLogger.clear(context)
            }
        }
    }

    fun onToggleApp(app: AppData, enabled: Boolean) {
        if (!appList.contains(app)) {
            logw("Tried to toggle app that does not exist in list! - $app")
            return
        }
        app.enabled = enabled

        configuration.edit {
            if (enabled) {
                appList.notOnVpn.add(app.info.packageName)
                appList.onVpn.remove(app.info.packageName)
            } else {
                appList.notOnVpn.remove(app.info.packageName)
                appList.onVpn.add(app.info.packageName)
            }
        }
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

    fun onClearBlockLog() {
        _connectionsLog.clear()
        blockLogger.clear(context)
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
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()
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
                Toast.makeText(context, "Not supported: $e", Toast.LENGTH_LONG).show()
                failed = true
            } finally {
                proc?.destroy()
            }

            if (!failed) {
                Toast.makeText(
                    context,
                    context.getString(R.string.logcat_written_successfully),
                    Toast.LENGTH_LONG
                ).show()
            }

            logcatLock.getAndSet(false)
            _isWritingLogcat.value = false
        }
    }

    fun addBlockLists(lists: List<BlockList>) {
        configuration.edit {
            lists.forEach { blockList ->
                val listUrl = context.getString(blockList.urlResId)
                if (this.hosts.items.firstOrNull { it.data == listUrl } == null) {
                    this.hosts.items.add(
                        HostFile(
                            title = context.getString(blockList.titleResId),
                            data = listUrl,
                            state = HostState.DENY,
                        )
                    )
                }
            }
        }
        _hosts.clear()
        _hosts.addAll(configuration.read { hosts.getAllHosts() })
    }

    companion object {
        const val KEY_SETUP_SHOWN = "setupShown"
    }
}
