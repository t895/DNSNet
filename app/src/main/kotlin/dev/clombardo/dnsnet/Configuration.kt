/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * Derived from DNS66:
 * Copyright (C) 2016 - 2019 Julian Andres Klode <jak@jak-linux.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import android.widget.Toast
import androidx.annotation.Keep
import dev.clombardo.dnsnet.DnsNetApplication.Companion.applicationContext
import dev.clombardo.dnsnet.HostState.Companion.toHostState
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import uniffi.net.NativeHost
import uniffi.net.NativeHostState
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@Serializable
data class Configuration(
    var version: Int = 1,
    var minorVersion: Int = 0,
    var autoStart: Boolean = false,
    var hosts: Hosts = Hosts(),
    var dnsServers: DnsServers = DnsServers(),
    var appList: AppList = AppList(),
    var showNotification: Boolean = true,
    var nightMode: Boolean = false,
    var watchDog: Boolean = false,
    var ipV6Support: Boolean = true,
    var blockLogging: Boolean = false,
) {
    companion object {
        const val DEFAULT_CONFIG_FILENAME = "settings.json"
        private const val CONFIG_BACKUP_EXTENSION = ".bak"

        private const val VERSION = 1

        /* Default tweak level */
        private const val MINOR_VERSION = 1

        private val json by lazy {
            Json {
                ignoreUnknownKeys = true
            }
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun load(inputStream: InputStream): Configuration {
            val config = try {
                json.decodeFromStream<Configuration>(inputStream)
            } catch (e: Exception) {
                loge("Failed to decode config!", e)
                Toast.makeText(
                    applicationContext,
                    applicationContext.getString(R.string.cannot_read_config),
                    Toast.LENGTH_LONG
                ).show()
                loadBackup()
            }
            if (config.version > VERSION) {
                Toast.makeText(
                    applicationContext,
                    applicationContext.getString(R.string.cannot_read_config),
                    Toast.LENGTH_LONG
                ).show()
                throw IOException("Unhandled file format version")
            }

            for (i in config.minorVersion + 1..MINOR_VERSION) {
                config.runUpdate(i)
            }

            return config
        }

        fun load(name: String = DEFAULT_CONFIG_FILENAME): Configuration {
            val inputStream = FileHelper.openRead(name)
            if (inputStream == null) {
                logd("Config file not found, creating new file")
                return Configuration()
            }

            return load(inputStream)
        }

        private fun loadBackup(
            name: String = "$DEFAULT_CONFIG_FILENAME$CONFIG_BACKUP_EXTENSION"
        ): Configuration = load(name)
    }

    fun runUpdate(level: Int) {
        when (level) {
            1 -> {
                // This is always enabled after v0.2.3
                hosts.enabled = true
                logi("Updated to config v1.1 successfully")
            }
        }
        minorVersion = level
    }

    fun save(name: String = DEFAULT_CONFIG_FILENAME) {
        val outputStream = FileHelper.openWrite(name)
        save(outputStream)
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun save(writer: OutputStream) {
        try {
            json.encodeToStream(this, writer)
        } catch (e: Exception) {
            loge("Failed to write config to disk!", e)
            Toast.makeText(
                applicationContext,
                applicationContext.getString(R.string.cannot_write_config, e.localizedMessage),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

@Serializable
data class AppList(
    var showSystemApps: Boolean = false,
    var defaultMode: AllowListMode = AllowListMode.ON_VPN,
    var onVpn: MutableList<String> = mutableListOf(),
    var notOnVpn: MutableList<String> = mutableListOf(),
) {
    /**
     * Categorizes all packages in the system into an allowlist
     * and denylist based on the [Configuration]-defined
     * [AppList.onVpn] and [AppList.notOnVpn].
     *
     * @param pm             A [PackageManager]
     * @param totalOnVpn     Names of packages to use the VPN
     * @param totalNotOnVpn  Names of packages not to use the VPN
     */
    fun resolve(
        pm: PackageManager,
        totalOnVpn: MutableSet<String>,
        totalNotOnVpn: MutableSet<String>,
    ) {
        val webBrowserPackageNames: MutableSet<String> = HashSet()
        val resolveInfoList = pm.queryIntentActivities(newBrowserIntent(), 0)
        for (resolveInfo in resolveInfoList) {
            webBrowserPackageNames.add(resolveInfo.activityInfo.packageName)
        }

        webBrowserPackageNames.apply {
            add("com.google.android.webview")
            add("com.android.htmlviewer")
            add("com.google.android.backuptransport")
            add("com.google.android.gms")
            add("com.google.android.gsf")
        }

        for (applicationInfo in pm.getInstalledApplications(0)) {
            // We need to always keep ourselves using the VPN, otherwise our
            // watchdog does not work.
            if (applicationInfo.packageName == BuildConfig.APPLICATION_ID) {
                totalOnVpn.add(applicationInfo.packageName)
            } else if (onVpn.contains(applicationInfo.packageName)) {
                totalOnVpn.add(applicationInfo.packageName)
            } else if (notOnVpn.contains(applicationInfo.packageName)) {
                totalNotOnVpn.add(applicationInfo.packageName)
            } else if (defaultMode == AllowListMode.ON_VPN) {
                totalOnVpn.add(applicationInfo.packageName)
            } else if (defaultMode == AllowListMode.NOT_ON_VPN) {
                totalNotOnVpn.add(applicationInfo.packageName)
            } else if (defaultMode == AllowListMode.AUTO) {
                if (webBrowserPackageNames.contains(applicationInfo.packageName)) {
                    totalOnVpn.add(applicationInfo.packageName)
                } else if (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
                    totalNotOnVpn.add(applicationInfo.packageName)
                } else {
                    totalOnVpn.add(applicationInfo.packageName)
                }
            }
        }
    }

    /**
     * Returns an intent for opening a website, used for finding
     * web browsers. Extracted method for mocking.
     */
    fun newBrowserIntent(): Intent =
        Intent(Intent.ACTION_VIEW).setData(Uri.parse("https://isabrowser.dnsnet.t895.com/"))
}

// DO NOT change the order of these states. They correspond to UI functionality.
enum class AllowListMode {
    /**
     * All apps use the VPN.
     */
    ON_VPN,

    /**
     * No apps use the VPN.
     */
    NOT_ON_VPN,

    /**
     * System apps (excluding browsers) do not use the VPN.
     */
    AUTO;

    companion object {
        fun Int.toAllowListMode(): AllowListMode =
            AllowListMode.entries.firstOrNull { it.ordinal == this } ?: ON_VPN
    }
}

@Parcelize
@Serializable
data class DnsServer(
    var title: String = "",
    @SerialName("location") var addresses: String = "",
    var enabled: Boolean = false,
) : Parcelable {
    fun getAddresses(): List<String> = addresses.split(",").map { it.trim() }
}

interface Host : Parcelable {
    var title: String
    var data: String
    var state: HostState

    fun toNative(): NativeHost = NativeHost(title, data, state.toNative())
}

@Parcelize
@Serializable
data class HostFile(
    override var title: String = "",
    @SerialName("location") override var data: String = "",
    override var state: HostState = HostState.IGNORE,
) : Host {
    fun isDownloadable(): Boolean =
        data.startsWith("https://") || data.startsWith("http://")

    companion object : Parceler<HostFile> {
        override fun HostFile.write(parcel: Parcel, flags: Int) {
            parcel.apply {
                writeString(title)
                writeString(data)
                writeInt(state.ordinal)
            }
        }

        override fun create(parcel: Parcel): HostFile =
            HostFile(
                parcel.readString() ?: "",
                parcel.readString() ?: "",
                parcel.readInt().toHostState(),
            )
    }
}

@Parcelize
@Serializable
data class HostException(
    override var title: String = "",
    @SerialName("hostname") override var data: String = "",
    override var state: HostState = HostState.IGNORE,
) : Host {
    companion object : Parceler<HostException> {
        override fun HostException.write(parcel: Parcel, flags: Int) {
            parcel.apply {
                writeString(title)
                writeString(data)
                writeInt(state.ordinal)
            }
        }

        override fun create(parcel: Parcel): HostException =
            HostException(
                parcel.readString() ?: "",
                parcel.readString() ?: "",
                parcel.readInt().toHostState(),
            )
    }
}

@Serializable
data class Hosts(
    var enabled: Boolean = true,
    var automaticRefresh: Boolean = false,
    var items: MutableList<HostFile> = defaultHosts.toMutableList(),
    var exceptions: MutableList<HostException> = mutableListOf(),
) {
    fun getAllHosts(): List<Host> = items + exceptions

    companion object {
        val defaultHosts = listOf(
            HostFile(
                title = "StevenBlack's unified hosts file",
                data = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                state = HostState.DENY,
            ),
            HostFile(
                title = "Adaway hosts file",
                data = "https://adaway.org/hosts.txt",
                state = HostState.IGNORE,
            ),
            HostFile(
                title = "Dan Pollock's hosts file",
                data = "https://someonewhocares.org/hosts/hosts",
                state = HostState.IGNORE,
            ),
        )
    }
}

@Serializable
data class DnsServers(
    var enabled: Boolean = false,
    var items: MutableList<DnsServer> = defaultServers.toMutableList(),
) {
    companion object {
        val defaultServers = listOf(
            DnsServer(
                title = "Cloudflare",
                addresses = "1.1.1.1,1.0.0.1",
                enabled = false,
            ),
            DnsServer(
                title = "Quad9",
                addresses = "9.9.9.9",
                enabled = false,
            ),
        )
    }
}

// DO NOT change the order of these states. They correspond to UI functionality.
@Keep
enum class HostState {
    IGNORE, DENY, ALLOW;

    fun toNative(): NativeHostState = when (this.ordinal) {
        1 -> NativeHostState.DENY
        2 -> NativeHostState.ALLOW
        else -> NativeHostState.IGNORE
    }

    companion object {
        fun Int.toHostState(): HostState = entries.firstOrNull { it.ordinal == this } ?: IGNORE
    }
}
