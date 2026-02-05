/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.settings

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.clombardo.dnsnet.common.logDebug
import dev.clombardo.dnsnet.common.logError
import dev.clombardo.dnsnet.common.logWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal sealed interface ResettableSetting {
    fun reset()
}

abstract class Setting<T>() : ResettableSetting {
    protected abstract var value: T

    private val mutableFlow: MutableStateFlow<T> by lazy {
        MutableStateFlow(value)
    }
    private val stateFlow: StateFlow<T> by lazy {
        mutableFlow.asStateFlow()
    }

    fun asStateFlow(): StateFlow<T> = stateFlow

    @Composable
    fun collectAsState(): State<T> = mutableFlow.collectAsState()

    fun get(): T = value
    fun set(value: T) {
        this.value = value
        mutableFlow.value = value
    }

    override fun reset() {
        mutableFlow.value = value
    }
}

abstract class SettingStateList<T> : ResettableSetting {
    protected abstract var list: List<T>

    private val snapshotList: SnapshotStateList<T> by lazy {
        val list = mutableStateListOf<T>()
        list.addAll(this@SettingStateList.list)
        return@lazy list
    }

    fun asList(): List<T> = snapshotList

    fun get(): List<T> = snapshotList.toList()

    fun add(value: T) {
        snapshotList.add(value)
        list = snapshotList.toList()
    }

    fun addAll(vararg values: T) {
        snapshotList.addAll(values)
        list = snapshotList.toList()
    }

    fun remove(value: T) {
        if (!snapshotList.remove(value)) {
            logWarning("Tried to remove value that does not exist in the list! - $value")
            return
        }
        list = snapshotList.toList()
    }

    fun replace(oldValue: T, value: T) {
        val oldIndex = snapshotList.indexOf(oldValue)
        if (!snapshotList.indices.contains(oldIndex)) {
            logError("Attempted to replace list item that doesn't exist! Ignoring.")
            return
        }

        snapshotList[oldIndex] = value
        list = snapshotList.toList()
    }

    fun clear() {
        snapshotList.clear()
        list = emptyList()
    }

    override fun reset() {
        snapshotList.clear()
        snapshotList.addAll(this@SettingStateList.list)
    }
}

class Settings @Inject constructor(
    private val configuration: ConfigurationManager,
    private val preferences: Preferences,
) {
    val autoStart = object : Setting<Boolean>() {
        override var value: Boolean
            get() = configuration.read { autoStart }
            set(value) {
                configuration.edit { autoStart = value }
            }
    }

    abstract class FiltersSettings : ResettableSetting {
        abstract val enabled: Setting<Boolean>
        abstract val automaticRefresh: Setting<Boolean>
        abstract val files: SettingStateList<FilterFile>
        abstract val singles: SettingStateList<SingleFilter>

        override fun reset() {
            enabled.reset()
            automaticRefresh.reset()
            files.reset()
            singles.reset()
        }
    }

    val filters = object : FiltersSettings() {
        override val enabled = object : Setting<Boolean>() {
            override var value: Boolean
                get() = configuration.read { filters.enabled }
                set(value) {
                    configuration.edit { filters.enabled = value }
                }
        }

        override val automaticRefresh = object : Setting<Boolean>() {
            override var value: Boolean
                get() = configuration.read { filters.automaticRefresh }
                set(value) {
                    configuration.edit { filters.automaticRefresh = value }
                }
        }

        override val files = object : SettingStateList<FilterFile>() {
            override var list: List<FilterFile>
                get() = configuration.read { filters.files }
                set(value) {
                    configuration.edit { filters.files = value.toMutableList() }
                }
        }

        override val singles = object : SettingStateList<SingleFilter>() {
            override var list: List<SingleFilter>
                get() = configuration.read { filters.singleFilters }
                set(value) {
                    configuration.edit { filters.singleFilters = value.toMutableList() }
                }
        }
    }

    abstract class DnsServersSettings : ResettableSetting {
        abstract val enabled: Setting<Boolean>
        abstract val type: Setting<DnsServerType>
        abstract val standardServers: SettingStateList<StandardDnsServer>
        abstract val doh3Servers: SettingStateList<Doh3Server>

        override fun reset() {
            enabled.reset()
            type.reset()
            standardServers.reset()
            doh3Servers.reset()
        }
    }

    val dnsServers = object : DnsServersSettings() {
        override val enabled = object : Setting<Boolean>() {
            override var value: Boolean
                get() = configuration.read { dnsServers.enabled }
                set(value) {
                    configuration.edit { dnsServers.enabled = value }
                }
        }

        override val type = object : Setting<DnsServerType>() {
            override var value: DnsServerType
                get() = configuration.read { dnsServers.type }
                set(value) {
                    configuration.edit { dnsServers.type = value }
                }
        }

        override val standardServers = object : SettingStateList<StandardDnsServer>() {
            override var list: List<StandardDnsServer>
                get() = configuration.read { dnsServers.standardServers }
                set(value) {
                    configuration.edit { dnsServers.standardServers = value.toMutableList() }
                }
        }

        override val doh3Servers = object : SettingStateList<Doh3Server>() {
            override var list: List<Doh3Server>
                get() = configuration.read { dnsServers.doh3Servers }
                set(value) {
                    configuration.edit { dnsServers.doh3Servers = value.toMutableList() }
                }
        }
    }

    abstract class AppListSettings : ResettableSetting {
        abstract val defaultMode: Setting<AllowListMode>
        abstract val onVpn: Setting<Set<String>>
        abstract val notOnVpn: Setting<Set<String>>

        abstract fun resolve(
            selfPackageName: String,
            pm: PackageManager,
            totalOnVpn: MutableSet<String>,
            totalNotOnVpn: MutableSet<String>,
        )

        override fun reset() {
            defaultMode.reset()
            onVpn.reset()
            notOnVpn.reset()
        }
    }

    val appList = object : AppListSettings() {
        override val defaultMode = object : Setting<AllowListMode>() {
            override var value: AllowListMode
                get() = configuration.read { appList.defaultMode }
                set(value) {
                    configuration.edit { appList.defaultMode = value }
                }
        }

        override val onVpn = object : Setting<Set<String>>() {
            override var value: Set<String>
                get() = configuration.read { appList.onVpn }
                set(value) {
                    configuration.edit { appList.onVpn = value.toMutableSet() }
                }
        }

        override val notOnVpn = object : Setting<Set<String>>() {
            override var value: Set<String>
                get() = configuration.read { appList.notOnVpn }
                set(value) {
                    configuration.edit { appList.notOnVpn = value.toMutableSet() }
                }
        }

        override fun resolve(
            selfPackageName: String,
            pm: PackageManager,
            totalOnVpn: MutableSet<String>,
            totalNotOnVpn: MutableSet<String>
        ) {
            configuration.read {
                appList.resolve(
                    selfPackageName,
                    pm,
                    totalOnVpn,
                    totalNotOnVpn,
                )
            }
        }
    }

    val blockLogging = object : Setting<Boolean>() {
        override var value: Boolean
            get() = configuration.read { blockLogging }
            set(value) {
                configuration.edit { blockLogging = value }
            }
    }

    val useNetworkDnsServers = object : Setting<Boolean>() {
        override var value: Boolean
            get() = configuration.read { useNetworkDnsServers }
            set(value) {
                configuration.edit { useNetworkDnsServers = value }
            }
    }

    private fun resetState() {
        autoStart.reset()
        filters.reset()
        dnsServers.reset()
        appList.reset()
        blockLogging.reset()
        useNetworkDnsServers.reset()
    }

    suspend fun saveOutUserConfiguration(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri).use {
                configuration.saveOut(it!!)
            }
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Cannot write file: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    suspend fun replaceUserConfiguration(context: Context, uri: Uri, onFinish: () -> Unit = {}) =
        withContext(Dispatchers.IO) {
            try {
                configuration.replaceInstance(context.contentResolver.openInputStream(uri)!!)
            } catch (e: Exception) {
                logDebug("Cannot read file", e)
                launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Cannot read file: ${e.message}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            onFinish()
        }

    suspend fun loadDefaultUserConfiguration(onFinish: () -> Unit = {}) =
        withContext(Dispatchers.IO) {
            configuration.resetInstance()
            resetState()
            onFinish()
        }
}
