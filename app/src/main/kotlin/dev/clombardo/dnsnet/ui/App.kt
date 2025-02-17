/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * Derived from DNS66:
 * Copyright (C) 2016-2019 Julian Andres Klode <jak@jak-linux.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.lang.ref.WeakReference

data class App(
    val info: ApplicationInfo,
    val label: String,
    var enabled: Boolean,
    val isSystem: Boolean,
) {
    private var weakIcon: WeakReference<Drawable>? = null

    fun loadIcon(pm: PackageManager): Drawable? {
        var icon = weakIcon?.get()
        if (icon == null) {
            icon = info.loadIcon(pm)
            weakIcon = WeakReference(icon)
        }
        return icon
    }
}
