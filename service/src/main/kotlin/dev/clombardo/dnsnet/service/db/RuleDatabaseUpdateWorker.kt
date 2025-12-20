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

package dev.clombardo.dnsnet.service.db

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.clombardo.dnsnet.common.logDebug
import dev.clombardo.dnsnet.common.logInfo
import dev.clombardo.dnsnet.common.logVerbose
import dev.clombardo.dnsnet.common.NotificationChannels
import dev.clombardo.dnsnet.resources.R
import dev.clombardo.dnsnet.service.vpn.DnsNetVpnService
import dev.clombardo.dnsnet.settings.ConfigurationManager
import dev.clombardo.dnsnet.settings.Filter
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@HiltWorker
class RuleDatabaseUpdateWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val configuration: ConfigurationManager,
) : CoroutineWorker(context, params) {
    companion object {
        private const val UPDATE_NOTIFICATION_ID = 42

        const val PERIODIC_TAG = "RuleDatabaseUpdatePeriodicWorker"

        private val _lastErrors = MutableStateFlow(emptyList<String>())
        val lastErrors = _lastErrors.asStateFlow()

        fun clearErrors(context: Context) {
            _lastErrors.value = emptyList()
            context.getSystemService<NotificationManager>()?.cancel(UPDATE_NOTIFICATION_ID)
        }

        private const val DATABASE_UPDATE_TIMEOUT = 3600000L

        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing = _isRefreshing.asStateFlow()

        fun runNow(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<RuleDatabaseUpdateWorker>()
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }

    private val pending = ArrayList<String>()
    private val done = ArrayList<String>()

    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationBuilder: NotificationCompat.Builder

    init {
        logDebug("Begin")
        setupNotificationBuilder()
        logDebug("Setup")
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        logDebug("doWork: Begin")
        _isRefreshing.value = true
        val start = System.currentTimeMillis()
        val jobs = mutableListOf<Deferred<Unit>>()
        configuration.edit {
            filters.files.forEach {
                val update = RuleDatabaseItemUpdate(context, this@RuleDatabaseUpdateWorker, it)
                if (update.shouldDownload()) {
                    val job = async(context = coroutineContext) { update.run() }
                    job.start()
                    jobs.add(job)
                }
            }
        }

        releaseGarbagePermissions()

        try {
            withTimeout(DATABASE_UPDATE_TIMEOUT) {
                jobs.awaitAll()
            }
        } catch (_: TimeoutCancellationException) {
        }
        val end = System.currentTimeMillis()
        logDebug("doWork: end after ${end - start} milliseconds")

        DnsNetVpnService.reloadDatabase(context)

        postExecute()

        Result.success()
    }

    private fun setupNotificationBuilder() {
        notificationManager = context.getSystemService<NotificationManager>()!!
        notificationBuilder =
            NotificationCompat.Builder(context, NotificationChannels.UPDATE_STATUS)
                .setContentTitle(context.getString(R.string.updating_filter_files))
                .setSmallIcon(R.drawable.ic_refresh)
                .setProgress(configuration.read { this.filters.files.size }, 0, false)
    }

    /**
     * Releases all persisted URI permissions that are no longer referenced
     */
    private fun releaseGarbagePermissions() {
        val contentResolver = context.contentResolver
        for (permission in contentResolver.persistedUriPermissions) {
            if (isGarbage(permission.uri)) {
                logInfo("releaseGarbagePermissions: Releasing permission for ${permission.uri}")
                contentResolver.releasePersistableUriPermission(
                    permission.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } else {
                logVerbose("releaseGarbagePermissions: Keeping permission for ${permission.uri}")
            }
        }
    }

    /**
     * Returns whether URI is no longer referenced in the configuration
     *
     * @param uri URI to check
     */
    private fun isGarbage(uri: Uri): Boolean {
        for (item in configuration.read { this.filters.files }) {
            if (item.data.toUri() == uri) {
                return false
            }
        }
        return true
    }

    /**
     * Sets progress message.
     */
    @Synchronized
    private fun updateProgressNotification() {
        val builder = StringBuilder()
        for (p in pending) {
            if (builder.isNotEmpty()) {
                builder.append("\n")
            }
            builder.append(p)
        }

        notificationBuilder.setProgress(pending.size + done.size, done.size, false)
            .setStyle(NotificationCompat.BigTextStyle().bigText(builder.toString()))
            .setContentText(context.getString(R.string.updating_n_filter_files, pending.size))
        notificationManager.notify(UPDATE_NOTIFICATION_ID, notificationBuilder.build())
    }

    /**
     * Clears the notifications or updates it for viewing errors.
     */
    @Synchronized
    private fun postExecute() {
        logDebug("postExecute: Sending notification")
        if (_lastErrors.value.isEmpty()) {
            notificationManager.cancel(UPDATE_NOTIFICATION_ID)
        } else {
            val intent =
                context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            notificationBuilder
                .setProgress(0, 0, false)
                .setContentText(context.getString(R.string.could_not_update_all_filter_files))
                .setSmallIcon(R.drawable.ic_warning)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
            notificationManager.notify(UPDATE_NOTIFICATION_ID, notificationBuilder.build())
        }
        _isRefreshing.value = false
    }

    /**
     * Adds an error message related to the item to the log.
     *
     * @param item    The item
     * @param message Message
     */
    @Synchronized
    fun addError(item: Filter, message: String) {
        logDebug("error: ${item.title}:$message")
        _lastErrors.value += "${item.title}\n$message"
    }

    @Synchronized
    fun addDone(item: Filter) {
        logDebug("done: ${item.title}")
        pending.remove(item.title)
        done.add(item.title)
        updateProgressNotification()
    }

    /**
     * Adds an item to the notification
     *
     * @param item The item currently being processed.
     */
    @Synchronized
    fun addBegin(item: Filter) {
        pending.add(item.title)
        updateProgressNotification()
    }

    @Synchronized
    fun pendingCount(): Int = pending.size
}
