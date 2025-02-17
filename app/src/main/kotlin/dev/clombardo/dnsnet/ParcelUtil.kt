/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet

import android.content.Intent
import android.os.Build
import android.os.Parcelable

@Suppress("DEPRECATION")
inline fun <reified T> Intent.getParcel(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        getParcelableExtra<Parcelable>(name) as T?
    }

@Suppress("DEPRECATION")
inline fun <reified T : java.io.Serializable> Intent.getSerializable(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getSerializableExtra(name, T::class.java)
    } else {
        getSerializableExtra(name) as T?
    }
