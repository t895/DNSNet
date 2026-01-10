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

import android.content.Context
import androidx.core.net.toUri
import dev.clombardo.dnsnet.common.FileHelper
import dev.clombardo.dnsnet.common.SingleWriterMultipleReaderFile
import dev.clombardo.dnsnet.common.logDebug
import dev.clombardo.dnsnet.network.NetworkRepository
import dev.clombardo.dnsnet.network.Response
import dev.clombardo.dnsnet.resources.R
import dev.clombardo.dnsnet.settings.Filter
import dev.clombardo.dnsnet.settings.FilterFile
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.net.MalformedURLException
import java.net.URL

/**
 * Updates a single item.
 */
class RuleDatabaseItemUpdate private constructor(
    private val context: Context,
    private val networkRepository: NetworkRepository,
    private val filterFile: FilterFile,
    private val file: SingleWriterMultipleReaderFile,
    private val onBegin: (filter: Filter) -> Unit,
    private val onError: (filter: Filter, message: String) -> Unit,
    private val onDone: (filter: Filter) -> Unit,
) {
    companion object {
        fun new(
            context: Context,
            networkRepository: NetworkRepository,
            filterFile: FilterFile,
            onBegin: (filter: Filter) -> Unit,
            onError: (filter: Filter, message: String) -> Unit,
            onDone: (filter: Filter) -> Unit,
        ): RuleDatabaseItemUpdate? {
            if (filterFile.data.startsWith("content://")) {
                try {
                    FileHelper.testContentUriReadPermissions(context, filterFile.data.toUri())
                } catch (e: SecurityException) {
                    logDebug("run: Error taking permission", e)
                    onError(filterFile, context.getString(R.string.permission_denied))
                } catch (e: FileNotFoundException) {
                    logDebug("run: File not found", e)
                    onError(filterFile, context.getString(R.string.file_not_found))
                } catch (e: IOException) {
                    onError(
                        filterFile,
                        context.getString(R.string.unknown_error_s, e.localizedMessage)
                    )
                }
                return null
            }

            val file = FileHelper.getLocalFileForRemoteUrl(context, filterFile.data) ?: return null

            try {
                URL(filterFile.data)
            } catch (_: MalformedURLException) {
                onError(filterFile, context.getString(R.string.invalid_url_s, filterFile.data))
                return null
            }

            return RuleDatabaseItemUpdate(
                context = context,
                networkRepository = networkRepository,
                filterFile = filterFile,
                file = SingleWriterMultipleReaderFile(file),
                onBegin = onBegin,
                onError = onError,
                onDone = onDone,
            )
        }
    }

    /**
     * Runs the item download, and marks it as done when finished
     */
    suspend fun run() {
        onBegin(filterFile)

        val outputStream: OutputStream
        try {
            outputStream = file.startWrite()
        } catch (e: Exception) {
            onError(filterFile, context.getString(R.string.unknown_error_s, e.localizedMessage ?: ""))
            return
        }

        val response = networkRepository.downloadBodyToFile(filterFile.data, outputStream)
        file.finishWrite(outputStream)

        if (!checkForResponseErrors(response)) {
            return
        }

        onDone(filterFile)
    }

    /**
     * Runs [onError] for any failed response codes.
     *
     * @return true if there was no problem.
     */
    fun checkForResponseErrors(response: Response): Boolean {
        logDebug("Server responded with ${response.code} for ${filterFile.data}")
        if ((200..299).contains(response.code)) {
            return true
        }

        when (response.code) {
            404 -> onError(filterFile, context.getString(R.string.file_not_found))
            408 -> onError(filterFile, context.getString(R.string.requested_timed_out))
            else -> {
                onError(
                    filterFile,
                    context.resources.getString(
                        R.string.filter_update_error_item,
                        response.code,
                        response.description,
                    )
                )
            }
        }
        return false
    }
}
