/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet

import android.app.PendingIntent
import android.content.Intent
import dev.clombardo.dnsnet.DnsNetApplication.Companion.applicationContext
import dev.clombardo.dnsnet.vpn.AdVpnService
import dev.clombardo.dnsnet.vpn.Command

object Intents {
    fun getStartVpnIntent(): Intent = Intent(applicationContext, AdVpnService::class.java)
        .putExtra(AdVpnService.COMMAND_TAG, Command.START.ordinal)
        .putExtra(
            AdVpnService.NOTIFICATION_INTENT_TAG,
            getMainActivityPendingIntent()
        )

    fun getStopVpnIntent(): Intent = Intent(applicationContext, AdVpnService::class.java)
        .putExtra(AdVpnService.COMMAND_TAG, Command.STOP.ordinal)

    fun getRestartVpnIntent(): Intent = Intent(applicationContext, AdVpnService::class.java)
        .putExtra(AdVpnService.COMMAND_TAG, Command.RESTART.ordinal)

    fun getMainActivityPendingIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        0,
        Intent(applicationContext, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE
    )

    fun getMainActivityIntent(): Intent = Intent(applicationContext, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
