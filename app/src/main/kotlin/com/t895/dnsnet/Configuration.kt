/* Copyright (C) 2024 Charles Lombardo <clombardo169@gmail.com>
 *
 * Derived from DNS66:
 * Copyright (C) 2016 - 2019 Julian Andres Klode <jak@jak-linux.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.t895.dnsnet

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import androidx.annotation.Keep
import com.t895.dnsnet.HostState.Companion.toHostState
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.Reader
import java.io.Writer

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
        private const val TAG = "Configuration"

        private const val VERSION = 1

        /* Default tweak level */
        private const val MINOR_VERSION = 0

        @Throws(IOException::class)
        fun read(reader: Reader?): Configuration {
            val config = reader?.use {
                val data = it.readText()
                try {
                    Json.decodeFromString<Configuration>(data)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode config! - ${e.localizedMessage}")
                    throw IOException()
                }
            } ?: Configuration()
            if (config.version > VERSION) {
                throw IOException("Unhandled file format version")
            }

            for (i in config.minorVersion + 1..MINOR_VERSION) {
                config.runUpdate(i)
            }

            return config
        }
    }

    fun runUpdate(level: Int) {
        // Uncomment this block to set up db updates
        // when (level) {
        // }
        minorVersion = level
    }

    fun updateURL(oldURL: String, newURL: String?, newState: HostState) =
        hosts.items.forEach {
            if (it.location == oldURL) {
                if (newURL != null) {
                    it.location = newURL
                }

                it.state = newState
            }
        }

    fun updateDNS(oldIP: String, newIP: String) =
        dnsServers.items.forEach {
            if (it.location == oldIP) {
                it.location = newIP
            }
        }

    fun addDNS(title: String, location: String, isEnabled: Boolean) =
        dnsServers.items.add(
            DnsServer(
                title = title,
                location = location,
                enabled = isEnabled,
            )
        )

    fun addURL(index: Int, title: String, location: String, state: HostState) =
        hosts.items.add(
            index = index,
            element = Host(
                title = title,
                location = location,
                state = state,
            ),
        )

    fun removeURL(oldURL: String) =
        hosts.items.removeAll { it.location == oldURL }

    fun disableURL(oldURL: String) {
        Log.d(TAG, String.format("disableURL: Disabling %s", oldURL))
        hosts.items.forEach {
            if (it.location == oldURL) {
                it.state = HostState.IGNORE
            }
        }
    }

    @Throws(IOException::class)
    fun write(writer: Writer?) {
        val error = { e: Exception ->
            Log.e(TAG, "Failed to write config to disk! - ${e.localizedMessage}")
            throw IOException()
        }
        try {
            val data = Json.encodeToString(this)
            writer?.write(data)
        } catch (e: SerializationException) {
            error(e)
        } catch (e: IllegalArgumentException) {
            error(e)
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
    var location: String = "",
    var enabled: Boolean = false,
) : Parcelable

@Parcelize
@Serializable
data class Host(
    var title: String = "",
    var location: String = "",
    var state: HostState = HostState.IGNORE,
) : Parcelable {
    fun isDownloadable(): Boolean =
        location.startsWith("https://") || location.startsWith("http://")

    companion object : Parceler<Host> {
        override fun Host.write(parcel: Parcel, flags: Int) {
            parcel.apply {
                writeString(title)
                writeString(location)
                writeInt(state.ordinal)
            }
        }

        override fun create(parcel: Parcel): Host =
            Host(
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
    var items: MutableList<Host> = defaultHosts.toMutableList(),
) {
    companion object {
        val defaultHosts = listOf(
            Host(
                title = "StevenBlack's unified hosts file (adware + malware)",
                location = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                state = HostState.DENY,
            ),
            Host(
                title = "Adaway hosts file",
                location = "https://adaway.org/hosts.txt",
                state = HostState.IGNORE,
            ),
            Host(
                title = "Dan Pollock's hosts file",
                location = "https://someonewhocares.org/hosts/hosts",
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
                title = "Cloudflare (1)",
                location = "1.1.1.1",
                enabled = false,
            ),
            DnsServer(
                title = "Cloudflare (2)",
                location = "1.0.0.1",
                enabled = false,
            ),
            DnsServer(
                title = "Quad9",
                location = "9.9.9.9",
                enabled = false,
            ),
        )
    }
}

// DO NOT change the order of these states. They correspond to UI functionality.
@Keep
enum class HostState {
    IGNORE, DENY, ALLOW;

    companion object {
        fun Int.toHostState(): HostState = entries.firstOrNull { it.ordinal == this } ?: IGNORE
    }
}
