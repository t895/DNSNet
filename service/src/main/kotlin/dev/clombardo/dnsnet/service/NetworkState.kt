/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Contributions shall also be provided under any later versions of the
 * GPL.
 */

package dev.clombardo.dnsnet.service

import android.net.Network
import android.net.NetworkCapabilities
import dev.clombardo.dnsnet.service.vpn.NetworkDetails
import dev.clombardo.dnsnet.service.vpn.VpnStatus

data class NetworkState(
    private var defaultNetwork: NetworkDetails? = null,
    private val connectedNetworks: MutableMap<String, NetworkDetails> = mutableMapOf(),
) {
    private val networkLock = Object()

    fun removeNetwork(networkDetails: NetworkDetails) {
        synchronized(networkLock) {
            connectedNetworks.remove(networkDetails.networkId.toString())
            if (defaultNetwork == networkDetails) {
                defaultNetwork = null
            }
        }
    }

    fun getDefaultNetwork(): NetworkDetails? {
        return synchronized(networkLock) { defaultNetwork?.copy() }
    }

    fun setDefaultNetwork(networkDetails: NetworkDetails) {
        synchronized(networkLock) {
            defaultNetwork = networkDetails
            connectedNetworks[networkDetails.networkId.toString()] = networkDetails
        }
    }

    fun dropDefaultNetwork() {
        synchronized(networkLock) {
            if (defaultNetwork != null) {
                connectedNetworks.remove(defaultNetwork!!.networkId.toString())
                defaultNetwork = null
            }
        }
    }

    fun getConnectedNetwork(networkId: String): NetworkDetails? {
        return synchronized(networkLock) {
            connectedNetworks[networkId]?.copy()
        }
    }

    fun reset() {
        synchronized(networkLock) {
            defaultNetwork = null
            connectedNetworks.clear()
        }
    }

    /**
     * Both the transports and network id used by a given [Network] and its [NetworkCapabilities]
     * object can be different for the same network, so we need a specialized method to see the
     * changes we care about.
     * Specifically, we need to know when our default network has lost the
     * [NetworkCapabilities.TRANSPORT_VPN] transport or one of the other transports have changed.
     * However, we also want to ignore times that the default network goes from having the same
     * transports as the previous network but now includes [NetworkCapabilities.TRANSPORT_VPN].
     * This is because during VPN startup, the default network will receive an update to include the
     * new constant. We can't just rely on checking for the same network id since that too will
     * sometimes change for the same (effective) network.
     */
    fun shouldReconnect(newNetwork: NetworkDetails, currentStatus: VpnStatus): Boolean {
        if (currentStatus == VpnStatus.WAITING_FOR_NETWORK) {
            return true
        }

        synchronized(networkLock) {
            val oldNetwork = defaultNetwork
            if (oldNetwork == null && connectedNetworks.isEmpty()) {
                return false
            } else if (oldNetwork == null) {
                return true
            }

            if (oldNetwork.transports == null && newNetwork.transports == null) {
                return false
            }
            if (oldNetwork.transports != null && newNetwork.transports == null) {
                return true
            }
            if (oldNetwork.transports == null && newNetwork.transports != null) {
                return true
            }

            val oldTransports = oldNetwork.transports!!.toMutableList()
            val newTransports = newNetwork.transports!!.toMutableList()
            val oldNetworkHasVpn = oldTransports.remove(NetworkCapabilities.TRANSPORT_VPN)
            val newNetworkHasVpn = newTransports.remove(NetworkCapabilities.TRANSPORT_VPN)
            if (oldNetworkHasVpn && !newNetworkHasVpn) {
                return true
            }
            if (!oldNetworkHasVpn && newNetworkHasVpn) {
                return false
            }
            return !oldNetwork.transports.contentEquals(newNetwork.transports)
        }
    }

    override fun toString(): String {
        return synchronized(networkLock) {
            """
                Default network - ${defaultNetwork.toString()}
                Connected networks - $connectedNetworks
            """.trimIndent()
        }
    }
}
