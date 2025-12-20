/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.common

import android.util.Log

fun Any.className(): String = this::class.java.simpleName

fun Any.logDebug(message: String, error: Throwable? = null) {
    if (BuildConfig.DEBUG) {
        Log.d(this.className(), message, error)
    }
}

inline fun Any.logDebug(error: Throwable? = null, crossinline lazyMessage: () -> String) {
    if (BuildConfig.DEBUG) {
        Log.d(this.className(), lazyMessage(), error)
    }
}

fun Any.logVerbose(message: String, error: Throwable? = null) =
    Log.v(this.className(), message, error)
inline fun Any.logVerbose(error: Throwable? = null, crossinline lazyMessage: () -> String) =
    logVerbose(lazyMessage(), error)

fun Any.logInfo(message: String, error: Throwable? = null) =
    Log.i(this.className(), message, error)
inline fun Any.logInfo(error: Throwable? = null, crossinline lazyMessage: () -> String) =
    logInfo(lazyMessage(), error)

fun Any.logWarning(message: String, error: Throwable? = null) =
    Log.w(this.className(), message, error)
inline fun Any.logWarning(error: Throwable? = null, crossinline lazyMessage: () -> String) =
    logWarning(lazyMessage(), error)

fun Any.logError(message: String, error: Throwable? = null) =
    Log.e(this.className(), message, error)
inline fun Any.logError(error: Throwable? = null, crossinline lazyMessage: () -> String) =
    logError(lazyMessage(), error)

fun Any.logwtf(message: String, error: Throwable? = null) =
    Log.wtf(this.className(), message, error)
inline fun Any.logwtf(error: Throwable? = null, crossinline lazyMessage: () -> String) =
    logwtf(lazyMessage(), error)
