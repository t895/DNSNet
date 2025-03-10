/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.tile

import android.annotation.SuppressLint
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.clombardo.dnsnet.Intents
import dev.clombardo.dnsnet.vpn.AdVpnService
import dev.clombardo.dnsnet.vpn.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DnsNetTileService : TileService() {
    private lateinit var tileCoroutineScope: CoroutineScope

    override fun onStartListening() {
        super.onStartListening()
        tileCoroutineScope = CoroutineScope(Dispatchers.IO)
        tileCoroutineScope.launch {
            AdVpnService.status.collectLatest {
                update(it)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        tileCoroutineScope.cancel()
    }

    override fun onClick() {
        super.onClick()
        if (isSecure) {
            unlockAndRun(::toggleService)
        } else {
            toggleService()
        }
    }

    private fun update(status: VpnStatus) {
        qsTile?.apply {
            val statusString = applicationContext.getString(status.toTextId())
            contentDescription = statusString
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = statusString
            }

            state =
                if (status != VpnStatus.STOPPED) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                stateDescription = statusString
            }
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun toggleService() {
        val prepareIntent = VpnService.prepare(applicationContext)
        if (prepareIntent != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(Intents.getMainActivityPendingIntent())
            } else {
                startActivityAndCollapse(Intents.getMainActivityIntent())
            }
            return
        }

        if (AdVpnService.isRunning()) {
            AdVpnService.stop(applicationContext)
        } else {
            AdVpnService.start(applicationContext)
        }
    }
}
