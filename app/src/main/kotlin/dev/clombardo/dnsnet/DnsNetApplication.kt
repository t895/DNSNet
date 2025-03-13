/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import dev.clombardo.dnsnet.ui.image.AppImageFetcher
import dev.clombardo.dnsnet.ui.image.AppImageKeyer
import uniffi.net.rustInit
import java.io.File

var config = Configuration.load()

class DnsNetApplication : Application() {
    companion object {
        private lateinit var application: Application
        val applicationContext: Context get() = application.applicationContext
    }

    override fun onCreate() {
        super.onCreate()
        application = this

        rustInit(debug = BuildConfig.DEBUG)

        NotificationChannels.onCreate(this)

        SingletonImageLoader.setSafe {
            ImageLoader.Builder(applicationContext)
                .components {
                    add(AppImageKeyer())
                    add(AppImageFetcher.Factory())
                }
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(applicationContext)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(applicationContext.cacheDir.resolve("image_cache"))
                        .maxSizePercent(0.02)
                        .build()
                }
                .build()
        }

        // Prevent existing users (pre-1.1.9) from seeing the setup screen
        if (File(applicationContext.filesDir, Configuration.DEFAULT_CONFIG_FILENAME).exists() ||
            Preferences.NotificationPermissionActedUpon
        ) {
            Preferences.SetupComplete = true
        }
    }
}
