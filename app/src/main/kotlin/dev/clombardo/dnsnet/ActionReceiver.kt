/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Contributions shall also be provided under any later versions of the
 * GPL.
 */

package dev.clombardo.dnsnet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.clombardo.dnsnet.vpn.AdVpnService

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        val action = intent?.action ?: return
        logd("Got broadcast - $intent")
        when (action) {
            ACTION_START -> AdVpnService.start(context)
            ACTION_STOP -> AdVpnService.stop(context)
            else -> logw("Got unknown action: $action")
        }
    }

    companion object {
        private const val ACTION_START = "${BuildConfig.APPLICATION_ID}.START"
        private const val ACTION_STOP = "${BuildConfig.APPLICATION_ID}.STOP"
    }
}
