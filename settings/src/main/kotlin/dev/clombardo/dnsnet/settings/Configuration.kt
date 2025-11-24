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

package dev.clombardo.dnsnet.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Parcelable
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.clombardo.dnsnet.file.FileHelper
import dev.clombardo.dnsnet.log.logDebug
import dev.clombardo.dnsnet.log.logError
import dev.clombardo.dnsnet.log.logInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigurationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: Preferences,
) {
    private val configLock = Object()
    private var configuration = Configuration.load(context, preferences, replaced = false)

    private val savers = 1
    private val pendingSaveLock = Semaphore(savers)
    private var savingLock = Semaphore(savers)

    fun replaceInstance(newConfigStream: InputStream) {
        synchronized(configLock) {
            val newConfig = Configuration.load(newConfigStream, preferences, replaced = true)
            configuration = newConfig
        }
        saveAsync()
    }

    fun resetInstance() {
        synchronized(configLock) {
            configuration = Configuration()
            configuration.runUpdates(preferences, replaced = true)
        }
        saveAsync()
    }

    fun edit(block: Configuration.() -> Unit) {
        synchronized(configLock) {
            block(configuration)
        }
        saveAsync()
    }

    fun <T> read(block: Configuration.() -> T): T =
        synchronized(configLock) {
            block(configuration.copy())
        }

    fun saveOut(writer: OutputStream): Result<Unit> =
        configuration.save(writer)

    private fun saveAsync() {
        // File must be created here because we rely on it to know if we should show the Presets screen
        File(context.filesDir, Configuration.DEFAULT_CONFIG_FILENAME).createNewFile()
        if (!pendingSaveLock.tryAcquire()) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            savingLock.acquire()
            pendingSaveLock.release()
            configuration.save(context)
            logDebug("Saved configuration")
            savingLock.release()
        }
    }
}

@Serializable
data class Configuration(
    var version: Int = 1,
    var minorVersion: Int = 0,
    var autoStart: Boolean = false,
    @SerialName("hosts") var filters: Filters = Filters(),
    var dnsServers: DnsServers = DnsServers(),
    var appList: AppList = AppList(),
    var showNotification: Boolean = true,
    var nightMode: Boolean = false,
    var watchDog: Boolean = false,
    var ipV6Support: Boolean = true,
    var blockLogging: Boolean = false,
    var useNetworkDnsServers: Boolean = false,
) {
    companion object {
        const val DEFAULT_CONFIG_FILENAME = "settings.json"

        private const val VERSION = 1

        /* Default tweak level */
        private const val MINOR_VERSION = 3

        private val json by lazy {
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        }

        @OptIn(ExperimentalSerializationApi::class)
        internal fun load(inputStream: InputStream, preferences: Preferences, replaced: Boolean): Configuration {
            val config = try {
                json.decodeFromStream<Configuration>(inputStream)
            } catch (e: Exception) {
                logError("Failed to decode config!", e)
                Configuration()
            }
            if (config.version > VERSION) {
                logError("Unhandled file format version - ${config.version}")
                return Configuration()
            }

            config.runUpdates(preferences, replaced)

            return config
        }

        internal fun load(context: Context, preferences: Preferences, replaced: Boolean): Configuration {
            val inputStream = FileHelper.openRead(context, DEFAULT_CONFIG_FILENAME)
            if (inputStream == null) {
                logDebug("Config file not found, creating new file")
                return Configuration()
            }

            return load(inputStream, preferences, replaced)
        }
    }

    internal fun runUpdates(preferences: Preferences, replaced: Boolean) {
        for (i in minorVersion + 1..MINOR_VERSION) {
            runMinorUpdate(i, preferences, replaced)
        }
    }

    internal fun runMinorUpdate(level: Int, preferences: Preferences, replaced: Boolean) {
        when (level) {
            1 -> {
                // This is always enabled after v0.2.3
                filters.enabled = true
                logInfo("Updated to config v1.1 successfully")
            }

            3 -> {
                if (dnsServers.items.none { it.type == DnsServerType.DoH3 }) {
                    dnsServers.items.apply {
                        add(
                            DnsServer(
                                title = "Cloudflare DoH3",
                                addresses = "cloudflare-dns.com",
                                enabled = true,
                                type = DnsServerType.DoH3,
                            )
                        )
                        add(
                            DnsServer(
                                title = "Google DoH3",
                                addresses = "dns.google",
                                enabled = false,
                                type = DnsServerType.DoH3,
                            )
                        )
                        add(
                            DnsServer(
                                title = "Google DoH3 IPv6-only",
                                addresses = "dns64.dns.google",
                                enabled = false,
                                type = DnsServerType.DoH3,
                            )
                        )
                    }
                }
            }
        }
        minorVersion = level
    }

    internal fun save(context: Context): Result<Unit> {
        val outputStream = FileHelper.openWrite(context, DEFAULT_CONFIG_FILENAME)
        return save(outputStream)
    }

    @OptIn(ExperimentalSerializationApi::class)
    internal fun save(writer: OutputStream): Result<Unit> = runCatching {
        json.encodeToStream(this, writer)
    }
}

@Serializable
data class AppList(
    var showSystemApps: Boolean = false,
    var defaultMode: AllowListMode = AllowListMode.ON_VPN,
    var onVpn: MutableSet<String> = mutableSetOf(),
    var notOnVpn: MutableSet<String> = mutableSetOf(),
) {
    /**
     * Categorizes all packages in the system into an allowlist
     * and denylist based on the [Configuration]-defined
     * [AppList.onVpn] and [AppList.notOnVpn].
     *
     * @param selfPackageName Our package name
     * @param pm              A [PackageManager]
     * @param totalOnVpn      Names of packages to use the VPN
     * @param totalNotOnVpn   Names of packages not to use the VPN
     */
    fun resolve(
        selfPackageName: String,
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
            if (applicationInfo.packageName == selfPackageName) {
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
        Intent(Intent.ACTION_VIEW).setData("https://isabrowser.dnsnet.t895.com/".toUri())
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
    var type: DnsServerType = DnsServerType.Standard,
) : Parcelable {
    fun getAddresses(): List<String> = addresses.split(",").map { it.trim() }
}

@Keep
@Serializable
enum class DnsServerType {
    Standard, DoH3
}

sealed interface Filter : Parcelable {
    var title: String
    var data: String
    var state: FilterState
}

@Parcelize
@Serializable
data class FilterFile(
    override var title: String = "",
    @SerialName("location") override var data: String = "",
    override var state: FilterState = FilterState.IGNORE,
) : Filter {
    fun isDownloadable(): Boolean =
        data.startsWith("https://") || data.startsWith("http://")
}

@Parcelize
@Serializable
data class SingleFilter(
    override var title: String = "",
    @SerialName("hostname") override var data: String = "",
    override var state: FilterState = FilterState.IGNORE,
) : Filter

@Serializable
data class Filters(
    var enabled: Boolean = true,
    var automaticRefresh: Boolean = false,
    @SerialName("items") var files: MutableList<FilterFile> = mutableListOf(),
    @SerialName("exceptions") var singleFilters: MutableList<SingleFilter> = mutableListOf(),
) {
    fun getAllFilters(): MutableList<Filter> = (files + singleFilters).toMutableList()
}

@Serializable
data class DnsServers(
    var enabled: Boolean = false,
    var type: DnsServerType = DnsServerType.Standard,
    var items: MutableList<DnsServer> = defaultServers.toMutableList(),
) {
    companion object {
        val defaultServers = listOf(
            DnsServer(
                title = "Cloudflare",
                addresses = "1.1.1.1,1.0.0.1",
                enabled = true,
                type = DnsServerType.Standard,
            ),
            DnsServer(
                title = "Quad9",
                addresses = "9.9.9.9",
                enabled = false,
                type = DnsServerType.Standard,
            ),
            DnsServer(
                title = "Cloudflare DoH3",
                addresses = "cloudflare-dns.com",
                enabled = true,
                type = DnsServerType.DoH3,
            ),
            DnsServer(
                title = "Google DoH3",
                addresses = "dns.google",
                enabled = false,
                type = DnsServerType.DoH3,
            ),
        )
    }
}

// DO NOT change the order of these states. They correspond to UI functionality.
@Keep
enum class FilterState {
    IGNORE, DENY, ALLOW;

    companion object {
        fun Int.toFilterState(): FilterState = entries.firstOrNull { it.ordinal == this } ?: IGNORE
    }
}

sealed interface BlockListProvider {
    @get:StringRes
    val titleResId: Int

    @get:StringRes
    val descriptionResId: Int

    @get:StringRes
    val sourceUrlResId: Int
}

sealed interface BlockList {
    @get:StringRes
    val titleResId: Int

    @get:StringRes
    val descriptionResId: Int

    @get:StringRes
    val urlResId: Int
}

data class BlockListVariantProvider(
    override val titleResId: Int,
    override val descriptionResId: Int = 0,
    override val sourceUrlResId: Int,
    val singleSelection: Boolean,
    val variants: List<BlockListVariant>,
) : BlockListProvider

data class BlockListVariant(
    override val titleResId: Int,
    override val descriptionResId: Int = 0,
    override val urlResId: Int,
) : BlockList

data class BlockListUrlProvider(
    override val titleResId: Int,
    override val descriptionResId: Int = 0,
    override val sourceUrlResId: Int,
    override val urlResId: Int,
) : BlockListProvider, BlockList

object BlockListDefaults {
    val providers = listOf<BlockListProvider>(
        BlockListVariantProvider(
            titleResId = R.string.hagezi_dns_blocklists,
            descriptionResId = R.string.hagezi_dns_blocklists_description,
            sourceUrlResId = R.string.hagezi_dns_blocklists_source_url,
            singleSelection = true,
            variants = listOf(
                BlockListVariant(
                    titleResId = R.string.hagezi_dns_blocklists_light,
                    descriptionResId = R.string.hagezi_dns_blocklists_light_description,
                    urlResId = R.string.hagezi_dns_blocklists_light_url,
                ),
                BlockListVariant(
                    titleResId = R.string.hagezi_dns_blocklists_normal,
                    descriptionResId = R.string.hagezi_dns_blocklists_normal_description,
                    urlResId = R.string.hagezi_dns_blocklists_normal_url,
                ),
                BlockListVariant(
                    titleResId = R.string.hagezi_dns_blocklists_pro,
                    descriptionResId = R.string.hagezi_dns_blocklists_pro_description,
                    urlResId = R.string.hagezi_dns_blocklists_pro_url,
                ),
                BlockListVariant(
                    titleResId = R.string.hagezi_dns_blocklists_pro_plus_plus,
                    descriptionResId = R.string.hagezi_dns_blocklists_pro_plus_plus_description,
                    urlResId = R.string.hagezi_dns_blocklists_pro_plus_plus_url,
                ),
                BlockListVariant(
                    titleResId = R.string.hagezi_dns_blocklists_ultimate,
                    descriptionResId = R.string.hagezi_dns_blocklists_ultimate_description,
                    urlResId = R.string.hagezi_dns_blocklists_ultimate_url,
                ),
            ),
        ),
        BlockListVariantProvider(
            titleResId = R.string.stevenblack_hosts,
            descriptionResId = R.string.stevenblack_hosts_description,
            sourceUrlResId = R.string.stevenblack_hosts_source_url,
            singleSelection = false,
            variants = listOf(
                BlockListVariant(
                    titleResId = R.string.stevenblack_hosts_adware_malware,
                    urlResId = R.string.stevenblack_hosts_adware_malware_url,
                ),
                BlockListVariant(
                    titleResId = R.string.stevenblack_hosts_gambling,
                    urlResId = R.string.stevenblack_hosts_gambling_url,
                ),
                BlockListVariant(
                    titleResId = R.string.stevenblack_hosts_nsfw,
                    urlResId = R.string.stevenblack_hosts_nsfw_url,
                ),
                BlockListVariant(
                    titleResId = R.string.stevenblack_hosts_social_media,
                    urlResId = R.string.stevenblack_hosts_social_media_url,
                ),
            ),
        ),
        BlockListVariantProvider(
            titleResId = R.string.oisd,
            descriptionResId = R.string.oisd_description,
            sourceUrlResId = R.string.oisd_source_url,
            singleSelection = true,
            variants = listOf(
                BlockListVariant(
                    titleResId = R.string.oisd_small,
                    descriptionResId = R.string.oisd_small_description,
                    urlResId = R.string.oisd_small_url,
                ),
                BlockListVariant(
                    titleResId = R.string.oisd_big,
                    descriptionResId = R.string.oisd_big_description,
                    urlResId = R.string.oisd_big_url,
                ),
            ),
        ),
    )
}
