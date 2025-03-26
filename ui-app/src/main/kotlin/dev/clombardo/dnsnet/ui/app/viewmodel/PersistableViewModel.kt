/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.app.viewmodel

import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import com.aallam.similarity.Cosine
import dev.clombardo.dnsnet.settings.Preferences
import kotlinx.serialization.json.Json

abstract class PersistableViewModel : ViewModel() {
    abstract val preferences: Preferences
    abstract val tag: String
    protected val cosineSimilarity = Cosine()

    internal inline fun <reified T> getInitialPersistedValue(key: String, defaultValue: T): T {
        val key = "$tag:$key"
        return if (preferences.sharedPreferences.contains(key)) {
            try {
                Json.decodeFromString(preferences.sharedPreferences.getString(key, "")!!)
            } catch (_: Exception) {
                defaultValue
            }
        } else {
            defaultValue
        }
    }

    internal inline fun <reified T> persistValue(key: String, value: T) {
        try {
            preferences.sharedPreferences.edit { putString("$tag:$key", Json.encodeToString(value)) }
        } catch (_: Exception) {
        }
    }
}
