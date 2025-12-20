/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.app.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import com.aallam.similarity.Cosine
import kotlinx.serialization.json.Json

abstract class PersistableViewModel(context: Context) : ViewModel() {
    abstract val tag: String
    protected val cosineSimilarity = Cosine()

    private val preferences: SharedPreferences = context.getSharedPreferences(PREFERENCES_STORE_FILE, Context.MODE_PRIVATE)

    internal inline fun <reified T> getInitialPersistedValue(key: String, defaultValue: T): T {
        val key = "$tag:$key"
        return if (preferences.contains(key)) {
            try {
                Json.decodeFromString(preferences.getString(key, "")!!)
            } catch (_: Exception) {
                defaultValue
            }
        } else {
            defaultValue
        }
    }

    internal inline fun <reified T> persistValue(key: String, value: T) {
        try {
            preferences.edit { putString("$tag:$key", Json.encodeToString(value)) }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val PREFERENCES_STORE_FILE = "PersistableViewModelPreferences"
    }
}
