/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp
import dev.clombardo.dnsnet.common.NotificationChannels
import dev.clombardo.dnsnet.settings.Preferences
import dev.clombardo.dnsnet.ui.app.coil.AppImageFetcher
import dev.clombardo.dnsnet.ui.app.coil.AppImageKeyer
import uniffi.netAndroid.rustInit
import javax.inject.Inject

@HiltAndroidApp
class DnsNetApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var preferences: Preferences

    override fun onCreate() {
        super.onCreate()

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
        if (preferences.NotificationPermissionActedUpon) {
            preferences.SetupComplete = true
        }
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
