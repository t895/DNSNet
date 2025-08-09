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
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.clombardo.dnsnet.blocklogger.BlockLogger
import dev.clombardo.dnsnet.blocklogger.LoggedConnection
import dev.clombardo.dnsnet.log.logDebug
import dev.clombardo.dnsnet.settings.BlockList
import dev.clombardo.dnsnet.settings.DnsServer
import dev.clombardo.dnsnet.settings.Filter
import dev.clombardo.dnsnet.settings.FilterFile
import dev.clombardo.dnsnet.settings.FilterState
import dev.clombardo.dnsnet.settings.Preferences
import dev.clombardo.dnsnet.settings.Settings
import dev.clombardo.dnsnet.settings.SingleFilter
import dev.clombardo.dnsnet.ui.app.R
import dev.clombardo.dnsnet.ui.app.model.AppData
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject

@SuppressLint("StaticFieldLeak")
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext val context: Context,
    val settings: Settings,
    val preferences: Preferences,
    val blockLogger: BlockLogger,
) : ViewModel() {
    private val _showUpdateIncompleteDialog = MutableStateFlow(false)
    val showUpdateIncompleteDialog = _showUpdateIncompleteDialog.asStateFlow()

    var errors: List<String>? = null

    private var refreshingLock by atomic(false)

    private val _appListRefreshing = MutableStateFlow(false)
    val appListRefreshing = _appListRefreshing.asStateFlow()

    private val applicationInfoList = MutableStateFlow<List<ApplicationInfo>>(emptyList())

    val appList = combine(
        flow = settings.appList.onVpn.asStateFlow(),
        flow2 = settings.appList.notOnVpn.asStateFlow(),
        flow3 = applicationInfoList
    ) { onVpn, notOnVpn, applicationInfoList ->
        val notOnVpn = HashSet<String>()
        val pm = context.packageManager
        settings.appList.resolve(context.packageName, pm, HashSet(), notOnVpn)
        val newList = mutableListOf<AppData>()
        applicationInfoList.forEach {
            newList.add(
                AppData(
                    packageManager = pm,
                    info = it,
                    label = it.loadLabel(pm).toString(),
                    enabled = notOnVpn.contains(it.packageName),
                    isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            )
        }
        return@combine newList.toList()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    private val _showFilterFilesNotFoundDialog = MutableStateFlow(false)
    val showFilterFilesNotFoundDialog = _showFilterFilesNotFoundDialog.asStateFlow()

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

    private val _showDeleteFilterWarningDialog = MutableStateFlow(false)
    val showDeleteFilterWarningDialog = _showDeleteFilterWarningDialog.asStateFlow()

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
            val entries = ArrayList<ApplicationInfo>()
            pm.getInstalledApplications(0).forEach {
                if (it.packageName != context.packageName) {
                    entries.add(it)
                }
            }

            applicationInfoList.value = entries
            _appListRefreshing.value = false
            refreshingLock = false
            Runtime.getRuntime().gc()
        }
    }

    fun onFilterFilesNotFound() {
        _showFilterFilesNotFoundDialog.value = true
    }

    fun onDismissFilterFilesNotFound() {
        _showFilterFilesNotFoundDialog.value = false
    }

    private fun addFilterFile(filter: FilterFile) {
        settings.filters.files.add(filter)
    }

    private fun addSingleFilter(filter: SingleFilter) {
        settings.filters.singles.add(filter)
    }

    fun addFilter(filter: Filter) {
        when (filter) {
            is FilterFile -> addFilterFile(filter)
            is SingleFilter -> addSingleFilter(filter)
        }
    }

    private fun removeFilterFile(filter: FilterFile) {
        settings.filters.files.remove(filter)
    }

    private fun removeSingleFilter(filter: SingleFilter) {
        settings.filters.singles.remove(filter)
    }

    fun removeFilter(filter: Filter) {
        when (filter) {
            is FilterFile -> removeFilterFile(filter)
            is SingleFilter -> removeSingleFilter(filter)
        }
    }

    private fun replaceFilterFile(oldFilter: FilterFile, newFilter: FilterFile) {
        settings.filters.files.replace(oldFilter, newFilter)
    }

    private fun replaceSingleFilter(oldFilter: SingleFilter, newFilter: SingleFilter) {
        settings.filters.singles.replace(oldFilter, newFilter)
    }

    fun replaceFilter(oldFilter: Filter, newFilter: Filter) {
        if (oldFilter is FilterFile && newFilter is FilterFile) {
            replaceFilterFile(oldFilter, newFilter)
        } else if (oldFilter is SingleFilter && newFilter is SingleFilter) {
            replaceSingleFilter(oldFilter, newFilter)
        }
    }

    private fun cycleFilterFile(filter: FilterFile) {
        val newFilter = filter.copy()
        newFilter.state = when (newFilter.state) {
            FilterState.IGNORE -> FilterState.DENY
            FilterState.DENY -> FilterState.ALLOW
            FilterState.ALLOW -> FilterState.IGNORE
        }
        replaceFilterFile(filter, newFilter)
    }

    private fun cycleSingleFilter(filter: SingleFilter) {
        val newFilter = filter.copy()
        newFilter.state = when (newFilter.state) {
            FilterState.IGNORE -> FilterState.DENY
            FilterState.DENY -> FilterState.ALLOW
            FilterState.ALLOW -> FilterState.IGNORE
        }
        replaceSingleFilter(filter, newFilter)
    }

    fun cycleFilter(filter: Filter) {
        when (filter) {
            is FilterFile -> cycleFilterFile(filter)
            is SingleFilter -> cycleSingleFilter(filter)
        }
    }

    fun removeBlockLogEntry(hostname: String) {
        blockLogger.connections.remove(hostname)
        _connectionsLog.remove(hostname)
    }

    fun addDnsServer(server: DnsServer) {
        settings.dnsServers.items.add(server)
    }

    fun removeDnsServer(server: DnsServer) {
        settings.dnsServers.items.remove(server)
    }

    fun replaceDnsServer(
        oldServer: DnsServer,
        newDnsServer: DnsServer
    ) {
        settings.dnsServers.items.replace(oldServer, newDnsServer)
    }

    fun toggleDnsServer(server: DnsServer) {
        val newServer = server.copy(enabled = !server.enabled)
        replaceDnsServer(server, newServer)
    }

    fun onToggleApp(app: AppData, enabled: Boolean) {
        app.enabled = enabled
        val notOnVpn = settings.appList.notOnVpn.get().toMutableSet()
        val onVpn = settings.appList.onVpn.get().toMutableSet()
        if (enabled) {
            notOnVpn.add(app.info.packageName)
            onVpn.remove(app.info.packageName)
        } else {
            notOnVpn.remove(app.info.packageName)
            onVpn.add(app.info.packageName)
        }
        settings.appList.notOnVpn.set(notOnVpn)
        settings.appList.onVpn.set(onVpn)
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

    fun onDeleteFilterWarning() {
        _showDeleteFilterWarningDialog.value = true
    }

    fun onDismissDeleteFilterWarning() {
        _showDeleteFilterWarningDialog.value = false
    }

    fun onDisableBlockLog() {
        settings.blockLogging.set(false)
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
                logDebug("sendLogcat: Not supported", e)
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
        lists.forEach { blockList ->
            val listUrl = context.getString(blockList.urlResId)
            if (settings.filters.files.get().firstOrNull { it.data == listUrl } == null) {
                settings.filters.files.add(
                    FilterFile(
                        title = context.getString(blockList.titleResId),
                        data = listUrl,
                        state = FilterState.DENY,
                    )
                )
            }
        }
    }

    companion object {
        const val KEY_SETUP_SHOWN = "setupShown"
    }
}
