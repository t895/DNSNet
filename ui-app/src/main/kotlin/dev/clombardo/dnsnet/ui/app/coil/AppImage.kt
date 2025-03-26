/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.app.coil

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import dev.clombardo.dnsnet.ui.app.model.AppData

class AppImageKeyer : Keyer<AppData> {
    override fun key(data: AppData, options: Options): String? = data.info.packageName
}

class AppImageFetcher(private val appData: AppData) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val icon = appData.loadIcon()
        return ImageFetchResult(
            image = icon.asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<AppData> {
        override fun create(data: AppData, options: Options, imageLoader: ImageLoader): Fetcher =
            AppImageFetcher(data)
    }
}
