/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.app.model

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.lang.ref.WeakReference

data class AppData(
    val packageManager: PackageManager,
    val info: ApplicationInfo,
    val label: String,
    var enabled: Boolean,
    val isSystem: Boolean,
) {
    private var weakIcon: WeakReference<Drawable>? = null

    fun loadIcon(): Drawable {
        var icon = weakIcon?.get()
        if (icon == null) {
            icon = info.loadIcon(packageManager)
            weakIcon = WeakReference(icon)
        }
        return icon!!
    }
}
