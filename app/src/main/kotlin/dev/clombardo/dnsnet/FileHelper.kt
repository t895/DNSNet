/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * Derived from DNS66:
 * Copyright (C) 2016 - 2019 Julian Andres Klode <jak@jak-linux.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import dev.clombardo.dnsnet.DnsNetApplication.Companion.applicationContext
import uniffi.net.AndroidFileHelper
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

/**
 * Utility object for working with files.
 */
object FileHelper : AndroidFileHelper {
    /**
     * Try open the file with [Context.openFileInput]
     */
    @Throws(IOException::class)
    fun openRead(filename: String?): InputStream? =
        try {
            applicationContext.openFileInput(filename)
        } catch (e: FileNotFoundException) {
            loge("Failed to open file", e)
            null
        }

    /**
     * Write to the given file in the private files dir, first renaming an old one to .bak
     *
     * @param filename A filename as for [Context.openFileOutput]
     * @return See [Context.openFileOutput]
     * @throws IOException See @{link {@link Context#openFileOutput(String, int)}}
     */
    @Throws(IOException::class)
    fun openWrite(filename: String): OutputStream {
        val out = applicationContext.getFileStreamPath(filename)

        // Create backup
        out.renameTo(applicationContext.getFileStreamPath("$filename.bak"))
        return applicationContext.openFileOutput(filename, Context.MODE_PRIVATE)
    }

    /**
     * Returns a file where the item should be downloaded to.
     *
     * @param item    A configuration item.
     * @return File or null, if that item is not downloadable.
     */
    fun getItemFile(item: HostFile): File? = getItemFile(item.data)

    fun getItemFile(path: String): File? =
        if (isDownloadable(path)) {
            try {
                File(
                    applicationContext.getExternalFilesDir(null),
                    URLEncoder.encode(path, "UTF-8"),
                )
            } catch (e: UnsupportedEncodingException) {
                logd("getItemFile: File failed to decode", e)
                null
            }
        } else {
            null
        }

    private fun isDownloadable(path: String): Boolean =
        path.startsWith("https://") || path.startsWith("http://")

    @Throws(FileNotFoundException::class)
    fun openItemFile(host: HostFile): InputStreamReader? {
        return if (host.data.startsWith("content://")) {
            try {
                InputStreamReader(applicationContext.contentResolver.openInputStream(Uri.parse(host.data)))
            } catch (e: SecurityException) {
                logd("openItemFile: Cannot open", e)
                throw FileNotFoundException(e.message)
            }
        } else {
            val itemFile = getItemFile(host) ?: return null
            if (host.isDownloadable()) {
                InputStreamReader(
                    SingleWriterMultipleReaderFile(itemFile).openRead()
                )
            } else {
                FileReader(itemFile)
            }
        }
    }

    fun closeOrWarn(fd: FileDescriptor?, message: String): FileDescriptor? {
        try {
            if (fd != null) {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            loge("closeOrWarn: $message", e)
        }

        // Always return null
        return null
    }

    fun <T : Closeable?> closeOrWarn(fd: T, message: String): FileDescriptor? {
        try {
            fd?.close()
        } catch (e: java.lang.Exception) {
            loge("closeOrWarn: $message", e)
        }

        // Always return null
        return null
    }

    override fun getHostFd(host: String, mode: String): Int? {
        var descriptor: Int? = null
        try {
            descriptor = if (host.startsWith("content://")) {
                applicationContext.contentResolver.openFileDescriptor(Uri.parse(host), mode)?.detachFd()
            } else {
                // This is not robust at all but it's all I need
                val modeInt = when (mode) {
                    "r" -> ParcelFileDescriptor.MODE_READ_ONLY
                    "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY
                    "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
                    else -> return null
                }
                val itemFile = getItemFile(host) ?: return null
                ParcelFileDescriptor.open(itemFile, modeInt).detachFd()
            }
        } catch (e: Exception) {
            loge("getHostFd: $host", e)
        }
        return descriptor
    }
}
