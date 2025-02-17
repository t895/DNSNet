/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet

import android.util.Log

fun Any.className(): String = this::class.java.simpleName

fun Any.logd(message: String, error: Throwable? = null) {
    if (BuildConfig.DEBUG) {
        Log.d(this.className(), message, error)
    }
}

inline fun Any.logd(error: Throwable? = null, crossinline lazyMessage: () -> String) {
    if (BuildConfig.DEBUG) {
        Log.d(this.className(), lazyMessage(), error)
    }
}

fun Any.logv(message: String, error: Throwable? = null) =
    Log.v(this.className(), message, error)
inline fun Any.logv(error: Throwable? = null, crossinline lazyMessage: () -> String) =
    logv(lazyMessage(), error)

fun Any.logi(message: String, error: Throwable? = null) =
    Log.i(this.className(), message, error)
inline fun Any.logi(error: Throwable? = null, crossinline lazyMessage: () -> String) =
    logi(lazyMessage(), error)

fun Any.logw(message: String, error: Throwable? = null) =
    Log.w(this.className(), message, error)
inline fun Any.logw(error: Throwable? = null, crossinline lazyMessage: () -> String) =
    logw(lazyMessage(), error)

fun Any.loge(message: String, error: Throwable? = null) =
    Log.e(this.className(), message, error)
inline fun Any.loge(error: Throwable? = null, crossinline lazyMessage: () -> String) =
    loge(lazyMessage(), error)

fun Any.logwtf(message: String, error: Throwable? = null) =
    Log.wtf(this.className(), message, error)
inline fun Any.logwtf(error: Throwable? = null, crossinline lazyMessage: () -> String) =
    logwtf(lazyMessage(), error)
