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

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * A file that multiple readers can safely read from and a single
 * writer thread can safely write too, without any synchronisation.
 * <p>
 * Implements the same API as AtomicFile, but avoids modifications
 * in openRead(), so it is safe to open files for reading while
 * writing, without losing the writes.
 * <p>
 * It uses two files: The specified one, and a work file with a suffix. On
 * failure, the work file is deleted; on success, it rename()ed to the specified
 * one, causing it to replace that atomically.
 */
class SingleWriterMultipleReaderFile(file: File) {
    val activeFile = file.absoluteFile
    val workFile = File(activeFile.absolutePath + ".dnsnet-new")

    /**
     * Opens the known-good file for reading.
     * @return A {@link FileInputStream} to read from
     * @throws FileNotFoundException See {@link FileInputStream}
     */
    @Throws(FileNotFoundException::class)
    fun openRead(): InputStream = FileInputStream(activeFile)

    /**
     * Starts a write.
     * @return A writable stream.
     * @throws IOException If the work file cannot be replaced or opened for writing.
     */
    @Throws(IOException::class)
    fun startWrite(): FileOutputStream {
        if (workFile.exists() && !workFile.delete()) {
            throw IOException("Cannot delete working file")
        }
        return FileOutputStream(workFile)
    }

    /**
     * Atomically replaces the active file with the work file, and closes the stream.
     * @param stream
     * @throws IOException
     */
    @Throws(IOException::class)
    fun finishWrite(stream: FileOutputStream) {
        try {
            stream.close()
        } catch (e: IOException) {
            failWrite(stream)
            throw e
        }
        if (!workFile.renameTo(activeFile)) {
            failWrite(stream)
            throw IOException("Cannot commit transaction")
        }
    }

    /**
     * Atomically replaces the active file with the work file, and closes the stream.
     * @param stream
     * @throws IOException
     */
    @Throws(IOException::class)
    fun failWrite(stream: FileOutputStream) {
        FileHelper.closeOrWarn(stream, "Cannot close working file")
        if (!workFile.delete()) {
            throw IOException("Cannot delete working file")
        }
    }
}
