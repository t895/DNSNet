/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet

import androidx.preference.PreferenceManager
import dev.clombardo.dnsnet.DnsNetApplication.Companion.applicationContext
import kotlin.reflect.KProperty

private val preferences by lazy {
    PreferenceManager.getDefaultSharedPreferences(applicationContext)
}

object Preferences {
    /**
     * Old preference that is no longer used to see if the user interacted with the notification
     * permission dialog. Now it's just used to make sure that we don't show existing users the
     * setup screen.
     */
    var NotificationPermissionActedUpon by BooleanPreference("NotificationPermissionDenied", false)

    /**
     * Tracks whether the VPN is running and is meant to tell the service if it was running when
     * the device was last on. On the next device boot, this is checked and if it is true and
     * the user enabled "Resume on system start-up," the service is started.
     */
    var VpnIsActive by BooleanPreference("isActive", false)

    var SetupComplete by BooleanPreference("setupComplete", false)
}

private interface Preference<T> {
    val key: String
    val defaultValue: T

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T)
}

class BooleanPreference(
    override val key: String,
    override val defaultValue: Boolean,
) : Preference<Boolean> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean {
        return preferences.getBoolean(key, defaultValue)
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }
}
