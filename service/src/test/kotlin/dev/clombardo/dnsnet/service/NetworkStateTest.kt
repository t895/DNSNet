/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.service

import android.net.NetworkCapabilities
import dev.clombardo.dnsnet.service.vpn.NetworkDetails
import dev.clombardo.dnsnet.service.vpn.VpnStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStateTest {
    private val plainCellularNetwork = NetworkDetails(
        networkId = 1,
        transports = arrayOf(NetworkCapabilities.TRANSPORT_CELLULAR).toIntArray()
    )

    private val vpnCellularNetwork = NetworkDetails(
        networkId = 2,
        transports = arrayOf(
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_VPN
        ).toIntArray()
    )

    @Test
    fun networkState_switching_test() {
        val networkState = NetworkState()

        // No networks to one cellular network
        assertFalse(networkState.shouldReconnect(plainCellularNetwork, VpnStatus.RUNNING))
        networkState.setDefaultNetwork(plainCellularNetwork)

        // Cellular network to VPN cellular network
        assertFalse(networkState.shouldReconnect(vpnCellularNetwork, VpnStatus.RUNNING))
        networkState.setDefaultNetwork(vpnCellularNetwork)

        // VPN cellular network to cellular network
        networkState.removeNetwork(plainCellularNetwork)
        assertTrue(networkState.shouldReconnect(plainCellularNetwork, VpnStatus.RUNNING))
    }
}
