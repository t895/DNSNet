/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.vpn

import android.net.NetworkCapabilities
import android.os.Build
import android.os.ext.SdkExtensions

private val TRANSPORT_NAMES: Array<String> = arrayOf(
    "CELLULAR",
    "WIFI",
    "BLUETOOTH",
    "ETHERNET",
    "VPN",
    "WIFI_AWARE",
    "LOWPAN",
    "TEST",
    "USB",
    "THREAD",
    "SATELLITE",
)

data class NetworkDetails(
    var networkId: Int,
    var transports: IntArray?,
) {
    override fun toString(): String {
        val builder = StringBuilder()
        builder.append("NetworkDetails { networkId: $networkId, ")
        if (transports != null) {
            builder.append("transports: ")
            transports!!.forEach {
                builder.append("${TRANSPORT_NAMES[it]}, ")
            }
        }
        builder.append("}")
        return builder.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NetworkDetails

        if (networkId != other.networkId) return false
        if (transports != null) {
            if (other.transports == null) return false
            if (!transports.contentEquals(other.transports)) return false
        } else if (other.transports != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = networkId.hashCode()
        result = 31 * result + (transports?.contentHashCode() ?: 0)
        return result
    }
}

fun NetworkCapabilities.getTransportTypes(): IntArray {
    val types = mutableListOf<Int>()
    if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
        types.add(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
    if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        types.add(NetworkCapabilities.TRANSPORT_WIFI)
    }
    if (hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
        types.add(NetworkCapabilities.TRANSPORT_BLUETOOTH)
    }
    if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
        types.add(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
    if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
        types.add(NetworkCapabilities.TRANSPORT_VPN)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) {
            types.add(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        if (hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN)) {
            types.add(NetworkCapabilities.TRANSPORT_LOWPAN)
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (hasTransport(NetworkCapabilities.TRANSPORT_USB)) {
            types.add(NetworkCapabilities.TRANSPORT_USB)
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 7) {
        if (hasTransport(NetworkCapabilities.TRANSPORT_THREAD)) {
            types.add(NetworkCapabilities.TRANSPORT_THREAD)
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 12) {
        if (hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE)) {
            types.add(NetworkCapabilities.TRANSPORT_SATELLITE)
        }
    }
    return types.toIntArray()
}
