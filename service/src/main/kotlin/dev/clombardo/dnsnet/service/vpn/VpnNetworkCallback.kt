/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Contributions shall also be provided under any later versions of the
 * GPL.
 */

package dev.clombardo.dnsnet.service.vpn

import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import dev.clombardo.dnsnet.log.logDebug
import dev.clombardo.dnsnet.service.NetworkState

class VpnNetworkCallback(
    private val networkState: NetworkState,
    private val onDefaultNetworkChanged: (NetworkDetails?) -> Unit
) : NetworkCallback() {
    override fun onCapabilitiesChanged(
        network: Network,
        networkCapabilities: NetworkCapabilities
    ) {
        super.onCapabilitiesChanged(network, networkCapabilities)
        logDebug("onCapabilitiesChanged")
        val networkId = network.toString()
        val networkDetails = networkState.getConnectedNetwork(networkId)
        if (networkDetails == null) {
            val newNetwork = NetworkDetails(
                networkId = networkId.toInt(),
                transports = networkCapabilities.getTransportTypes(),
            )
            onDefaultNetworkChanged(newNetwork)
        } else {
            onDefaultNetworkChanged(
                networkDetails.copy(
                    networkId = networkId.toInt(),
                    transports = networkCapabilities.getTransportTypes(),
                )
            )
        }
    }

    override fun onLost(network: Network) {
        super.onLost(network)
        logDebug("onLost")
        val networkString = network.toString()
        val lostNetwork = networkState.getConnectedNetwork(networkString)
        if (lostNetwork != null) {
            val defaultNetwork = networkState.getDefaultNetwork()
            if (defaultNetwork != null && lostNetwork.networkId == defaultNetwork.networkId) {
                onDefaultNetworkChanged(null)
            }
            networkState.removeNetwork(lostNetwork)
        }
        logDebug(networkState.toString())
    }
}
