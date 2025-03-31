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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.clombardo.dnsnet.file.FileHelper
import dev.clombardo.dnsnet.log.logd
import dev.clombardo.dnsnet.log.loge
import dev.clombardo.dnsnet.log.logi
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class ConfigurationModule {
    @Provides
    @Singleton
    fun provideConfigurationManager(@ApplicationContext context: Context): ConfigurationManager {
        return ConfigurationManager(context)
    }
}

class ConfigurationManager(private val context: Context) {
    private val configLock = Object()
    private var configuration = Configuration.load(context)

    private val pendingSave = atomic(false)
    private var saving by atomic(false)

    fun replaceInstance(newConfigStream: InputStream) {
        synchronized(configLock) {
            val newConfig = Configuration.load(newConfigStream)
            configuration = newConfig
        }
        saveAsync()
    }

    fun resetInstance() {
        synchronized(configLock) {
            configuration = Configuration()
        }
        saveAsync()
    }

    fun edit(block: Configuration.() -> Unit) {
        synchronized(configLock) {
            block(configuration)
        }
        saveAsync()
    }

    fun <T> read(block: ImmutableConfiguration.() -> T): T =
        synchronized(configLock) {
            block(ImmutableConfiguration(configuration))
        }

    fun saveOut(writer: OutputStream): Result<Unit> =
        configuration.save(writer)

    private fun saveAsync() {
        if (pendingSave.getAndSet(true)) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            while (saving) {
                delay(100)
            }

            saving = true
            pendingSave.getAndSet(false)
            configuration.save(context)
            logd("Saved configuration")
            saving = false
        }
    }
}

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
    var useNetworkDnsServers: Boolean = false,
) {
    companion object {
        const val DEFAULT_CONFIG_FILENAME = "settings.json"

        private const val VERSION = 1

        /* Default tweak level */
        private const val MINOR_VERSION = 1

        private val json by lazy {
            Json {
                ignoreUnknownKeys = true
            }
        }

        @OptIn(ExperimentalSerializationApi::class)
        internal fun load(inputStream: InputStream): Configuration {
            val config = try {
                json.decodeFromStream<Configuration>(inputStream)
            } catch (e: Exception) {
                loge("Failed to decode config!", e)
                Configuration()
            }
            if (config.version > VERSION) {
                loge("Unhandled file format version - ${config.version}")
                return Configuration()
            }

            for (i in config.minorVersion + 1..MINOR_VERSION) {
                config.runMinorUpdate(i)
            }

            return config
        }

        internal fun load(context: Context): Configuration {
            val inputStream = FileHelper.openRead(context, DEFAULT_CONFIG_FILENAME)
            if (inputStream == null) {
                logd("Config file not found, creating new file")
                return Configuration()
            }

            return load(inputStream)
        }
    }

    fun runMinorUpdate(level: Int) {
        when (level) {
            1 -> {
                // This is always enabled after v0.2.3
                hosts.enabled = true
                logi("Updated to config v1.1 successfully")
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

@JvmInline
value class ImmutableConfiguration(private val config: Configuration) {
    val version get() = config.version
    val minorVersion get() = config.minorVersion
    val autoStart get() = config.autoStart
    val hosts get() = config.hosts.asImmutable()
    val dnsServers get() = config.dnsServers.asImmutable()
    val appList get() = config.appList.asImmutable()
    val showNotification get() = config.showNotification
    val nightMode get() = config.nightMode
    val watchDog get() = config.watchDog
    val ipV6Support get() = config.ipV6Support
    val blockLogging get() = config.blockLogging
    val useNetworkDnsServers get() = config.useNetworkDnsServers
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

    fun asImmutable(): ImmutableAppList = ImmutableAppList(this)
}

@JvmInline
value class ImmutableAppList(private val appList: AppList) {
    val showSystemApps get() = appList.showSystemApps
    val defaultMode get() = appList.defaultMode
    val onVpn get() = appList.onVpn.toList()
    val notOnVpn get() = appList.notOnVpn.toList()

    fun resolve(
        selfPackageName: String,
        pm: PackageManager,
        totalOnVpn: MutableSet<String>,
        totalNotOnVpn: MutableSet<String>,
    ) = appList.resolve(
        selfPackageName,
        pm,
        totalOnVpn,
        totalNotOnVpn,
    )
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

sealed interface Host : Parcelable {
    var title: String
    var data: String
    var state: HostState
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
}

@Parcelize
@Serializable
data class HostException(
    override var title: String = "",
    @SerialName("hostname") override var data: String = "",
    override var state: HostState = HostState.IGNORE,
) : Host

@Serializable
data class Hosts(
    var enabled: Boolean = true,
    var automaticRefresh: Boolean = false,
    var items: MutableList<HostFile> = mutableListOf(),
    var exceptions: MutableList<HostException> = mutableListOf(),
) {
    fun getAllHosts(): List<Host> = items + exceptions

    fun asImmutable(): ImmutableHosts = ImmutableHosts(this)
}

@JvmInline
value class ImmutableHosts(private val hosts: Hosts) {
    val enabled get() = hosts.enabled
    val automaticRefresh get() = hosts.automaticRefresh
    val items get() = hosts.items.toList()
    val exceptions get() = hosts.exceptions.toList()

    fun getAllHosts(): List<Host> = hosts.getAllHosts()
}

@Serializable
data class DnsServers(
    var enabled: Boolean = false,
    var items: MutableList<DnsServer> = defaultServers.toMutableList(),
) {
    fun asImmutable(): ImmutableDnsServers = ImmutableDnsServers(this)

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

@JvmInline
value class ImmutableDnsServers(private val dnsServers: DnsServers) {
    val enabled get() = dnsServers.enabled
    val items get() = dnsServers.items.toList()
}

// DO NOT change the order of these states. They correspond to UI functionality.
@Keep
enum class HostState {
    IGNORE, DENY, ALLOW;

    companion object {
        fun Int.toHostState(): HostState = entries.firstOrNull { it.ordinal == this } ?: IGNORE
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
): BlockListProvider

data class BlockListVariant(
    override val titleResId: Int,
    override val descriptionResId: Int = 0,
    override val urlResId: Int,
): BlockList

data class BlockListUrlProvider(
    override val titleResId: Int,
    override val descriptionResId: Int = 0,
    override val sourceUrlResId: Int,
    override val urlResId: Int,
): BlockListProvider, BlockList

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
                    titleResId = R.string.oisd_big,
                    descriptionResId = R.string.oisd_big_description,
                    urlResId = R.string.oisd_big_url,
                ),
                BlockListVariant(
                    titleResId = R.string.oisd_small,
                    descriptionResId = R.string.oisd_small_description,
                    urlResId = R.string.oisd_small_url,
                ),
            ),
        ),
    )
}
