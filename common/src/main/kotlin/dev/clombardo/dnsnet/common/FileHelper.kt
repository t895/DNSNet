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

package dev.clombardo.dnsnet.common

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import androidx.core.net.toUri

/**
 * Utility object for working with files.
 */
object FileHelper {
    /**
     * Try open the file with [Context.openFileInput]
     */
    @Throws(IOException::class)
    fun openRead(context: Context, filename: String?): InputStream? =
        try {
            context.openFileInput(filename)
        } catch (e: FileNotFoundException) {
            logError("Failed to open file", e)
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
    fun openWrite(context: Context, filename: String): OutputStream {
        val out = context.getFileStreamPath(filename)

        // Create backup
        out.renameTo(context.getFileStreamPath("$filename.bak"))
        return context.openFileOutput(filename, Context.MODE_PRIVATE)
    }

    /**
     * Returns a file where remote data should be downloaded to.
     *
     * @param url A URL that points to a downloadable file.
     * @return File or null, if that item is not downloadable.
     */
    fun getLocalFileForRemoteUrl(context: Context, url: String): File? =
        if (isDownloadable(url)) {
            try {
                File(
                    context.getExternalFilesDir(null),
                    URLEncoder.encode(url, "UTF-8"),
                )
            } catch (e: UnsupportedEncodingException) {
                logDebug("getItemFile: File failed to decode", e)
                null
            }
        } else {
            null
        }

    private fun isDownloadable(path: String): Boolean =
        path.startsWith("https://") || path.startsWith("http://")

    @Throws(FileNotFoundException::class)
    fun openPath(context: Context, path: String): InputStreamReader? {
        return if (path.startsWith("content://")) {
            try {
                InputStreamReader(context.contentResolver.openInputStream(path.toUri()))
            } catch (e: SecurityException) {
                null
            }
        } else {
            val file = getLocalFileForRemoteUrl(context, path) ?: return null
            InputStreamReader(
                SingleWriterMultipleReaderFile(file).openRead()
            )
        }
    }

    fun closeOrWarn(fd: FileDescriptor?, message: String): FileDescriptor? {
        try {
            if (fd != null) {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            logError("closeOrWarn: $message", e)
        }

        // Always return null
        return null
    }

    fun <T : Closeable?> closeOrWarn(fd: T, message: String): FileDescriptor? {
        try {
            fd?.close()
        } catch (e: java.lang.Exception) {
            logError("closeOrWarn: $message", e)
        }

        // Always return null
        return null
    }

    /**
     * Returns a file descriptor for the given path. This is detached so you must close it yourself.
     *
     * @param path A content:// URI, a https:// http:// URL, or a local path
     * @return A read-only file descriptor or null if it cannot be opened.
     */
    fun getDetachedFd(
        context: Context,
        path: String,
        mode: Int = ParcelFileDescriptor.MODE_READ_ONLY
    ): Int? {
        val modeString = when (mode) {
            ParcelFileDescriptor.MODE_WRITE_ONLY -> "w"
            ParcelFileDescriptor.MODE_READ_WRITE -> "rw"
            else -> "r"
        }

        return try {
            if (path.startsWith("content://")) {
                context.contentResolver.openFileDescriptor(path.toUri(), modeString)!!.detachFd()
            } else if (isDownloadable(path)) {
                val itemFile = getLocalFileForRemoteUrl(context, path) ?: return null
                ParcelFileDescriptor.open(itemFile, mode).detachFd()
            } else {
                val file = File(path)
                file.createNewFile()
                ParcelFileDescriptor.open(file, mode).detachFd()
            }
        } catch (e: Exception) {
            logError("getDetachedReadOnlyFd: $path", e)
            null
        }
    }
}
