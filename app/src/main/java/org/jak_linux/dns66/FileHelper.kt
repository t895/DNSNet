package org.jak_linux.dns66

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import android.widget.Toast
import org.jak_linux.dns66.Dns66Application.Companion.applicationContext
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

/**
 * Utility object for working with files.
 */
object FileHelper {
    private const val TAG = "FileHelper"

    /**
     * Try open the file with [Context.openFileInput], falling back to a file of
     * the same name in the assets.
     */
    @Throws(IOException::class)
    fun openRead(filename: String?): InputStream =
        try {
            applicationContext.openFileInput(filename)
        } catch (e: FileNotFoundException) {
            applicationContext.assets.open(filename!!)
        }

    /**
     * Write to the given file in the private files dir, first renaming an old one to .bak
     *
     * @param filename A filename as for [Context.openFileOutput]
     * @return See [Context.openFileOutput]
     * @throws IOException See @{link {@link Context#openFileOutput(String, int)}}
     */
    @Throws(IOException::class)
    fun openWrite(filename: String): OutputStream? {
        val out = applicationContext.getFileStreamPath(filename)

        // Create backup
        out.renameTo(applicationContext.getFileStreamPath("$filename.bak"))
        return applicationContext.openFileOutput(filename, Context.MODE_PRIVATE)
    }

    @Throws(IOException::class)
    private fun readConfigFile(name: String, defaultsOnly: Boolean): Configuration {
        val stream: InputStream = if (defaultsOnly) {
            applicationContext.assets.open(name)
        } else {
            openRead(name)
        }

        return Configuration.read(InputStreamReader(stream))
    }

    fun loadCurrentSettings(): Configuration =
        try {
            readConfigFile("settings.json", false)
        } catch (e: Exception) {
            Toast.makeText(
                applicationContext,
                applicationContext.getString(R.string.cannot_read_config, e.localizedMessage),
                Toast.LENGTH_LONG
            ).show()
            loadPreviousSettings(applicationContext)
        }

    fun loadPreviousSettings(context: Context): Configuration =
        try {
            readConfigFile("settings.json.bak", false)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.cannot_restore_previous_config, e.localizedMessage),
                Toast.LENGTH_LONG
            ).show()
            loadDefaultSettings()
        }

    fun loadDefaultSettings(): Configuration = readConfigFile("settings.json", true)

    fun writeSettings(config: Configuration) {
        Log.d(TAG, "writeSettings: Writing the settings file")
        try {
            OutputStreamWriter(openWrite("settings.json")).use {
                config.write(it)
            }
        } catch (e: IOException) {
            Toast.makeText(
                applicationContext,
                applicationContext.getString(R.string.cannot_write_config, e.localizedMessage),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Returns a file where the item should be downloaded to.
     *
     * @param item    A configuration item.
     * @return File or null, if that item is not downloadable.
     */
    fun getItemFile(item: Configuration.HostItem): File? =
        if (item.isDownloadable()) {
            try {
                File(
                    applicationContext.getExternalFilesDir(null),
                    URLEncoder.encode(item.location, "UTF-8"),
                )
            } catch (e: UnsupportedEncodingException) {
                e.printStackTrace()
                null
            }
        } else {
            null
        }

    @Throws(FileNotFoundException::class)
    fun openItemFile(item: Configuration.HostItem): InputStreamReader? {
        return if (item.location.startsWith("content://")) {
            try {
                InputStreamReader(applicationContext.contentResolver.openInputStream(Uri.parse(item.location)))
            } catch (e: SecurityException) {
                Log.d("FileHelper", "openItemFile: Cannot open", e)
                throw FileNotFoundException(e.message)
            }
        } else {
            getItemFile(item) ?: return null
            if (item.isDownloadable()) {
                InputStreamReader(
                    SingleWriterMultipleReaderFile(getItemFile(item)!!).openRead()
                )
            } else {
                FileReader(getItemFile(item))
            }
        }
    }

    /**
     * Wrapper around [Os.poll] that automatically restarts on EINTR
     * While post-Lollipop devices handle that themselves, we need to do this for Lollipop.
     *
     * @param fds     Descriptors and events to wait on
     * @param timeout Timeout. Should be -1 for infinite, as we do not lower the timeout when
     *                retrying due to an interrupt
     * @return The number of fds that have events
     * @throws ErrnoException See [Os.poll]
     */
    @Throws(ErrnoException::class, InterruptedException::class)
    fun poll(fds: Array<StructPollfd?>, timeout: Int): Int {
        while (true) {
            if (Thread.interrupted()) {
                throw InterruptedException()
            }

            return try {
                Os.poll(fds, timeout)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) {
                    continue
                }
                throw e
            }
        }
    }

    fun closeOrWarn(fd: FileDescriptor?, tag: String?, message: String): FileDescriptor? {
        try {
            if (fd != null) {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            Log.e(tag, "closeOrWarn: $message", e)
        }

        // Always return null
        return null
    }

    fun <T : Closeable?> closeOrWarn(fd: T, tag: String?, message: String): FileDescriptor? {
        try {
            fd?.close()
        } catch (e: java.lang.Exception) {
            Log.e(tag, "closeOrWarn: $message", e)
        }

        // Always return null
        return null
    }
}
