/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlin.reflect.KProperty
import androidx.core.content.edit
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class PreferencesModule {
    @Provides
    @Singleton
    fun providePreferences(@ApplicationContext context: Context): Preferences {
        return Preferences(PreferenceManager.getDefaultSharedPreferences(context))
    }
}

class Preferences(val sharedPreferences: SharedPreferences) {
    /**
     * Old preference that is no longer used to see if the user interacted with the notification
     * permission dialog. Now it's just used to make sure that we don't show existing users the
     * setup screen.
     */
    var NotificationPermissionActedUpon by BooleanPreference(
        preferences = sharedPreferences,
        key = "NotificationPermissionDenied",
        defaultValue = false,
    )

    /**
     * Tracks whether the VPN is running and is meant to tell the service if it was running when
     * the device was last on. On the next device boot, this is checked and if it is true and
     * the user enabled "Resume on system start-up," the service is started.
     */
    var VpnIsActive by BooleanPreference(
        preferences = sharedPreferences,
        key = "isActive",
        defaultValue = false,
    )

    var SetupComplete by BooleanPreference(
        preferences = sharedPreferences,
        key = "setupComplete",
        defaultValue = false,
    )
}

interface Preference<T> {
    val preferences: SharedPreferences
    val key: String
    val defaultValue: T

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T)
}

class BooleanPreference(
    override val preferences: SharedPreferences,
    override val key: String,
    override val defaultValue: Boolean,
) : Preference<Boolean> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean {
        return preferences.getBoolean(key, defaultValue)
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
        preferences.edit { putBoolean(key, value) }
    }
}
