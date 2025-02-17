/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * Derived from DNS66:
 * Copyright (C) 2017 Julian Andres Klode <jak@jak-linux.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Helper object containing IDs of notification channels and code to create them.
 */
object NotificationChannels {
    const val GROUP_SERVICE = "dev.clombardo.dnsnet.notifications.service"
    const val SERVICE_RUNNING = "dev.clombardo.dnsnet.notifications.service.running"
    const val SERVICE_PAUSED = "dev.clombardo.dnsnet.notifications.service.paused"
    const val GROUP_UPDATE = "dev.clombardo.dnsnet.notifications.update"
    const val UPDATE_STATUS = "dev.clombardo.dnsnet.notifications.update.status"

    fun onCreate(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        notificationManager.createNotificationChannelGroup(
            NotificationChannelGroup(
                GROUP_SERVICE,
                context.getString(R.string.notifications_group_service)
            )
        )
        notificationManager.createNotificationChannelGroup(
            NotificationChannelGroup(
                GROUP_UPDATE,
                context.getString(R.string.notifications_group_updates)
            )
        )

        val runningChannel = NotificationChannel(
            SERVICE_RUNNING,
            context.getString(R.string.notifications_running),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notifications_running_desc)
            group = GROUP_SERVICE
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(runningChannel)

        val pausedChannel = NotificationChannel(
            SERVICE_PAUSED,
            context.getString(R.string.notifications_paused),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notifications_paused_desc)
            group = GROUP_SERVICE
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(pausedChannel)

        val updateChannel = NotificationChannel(
            UPDATE_STATUS,
            context.getString(R.string.notifications_update),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notifications_update_desc)
            group = GROUP_UPDATE
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(updateChannel)
    }
}
