package dev.clombardo.dnsnet.vpn

import dev.clombardo.dnsnet.DnsNetApplication.Companion.applicationContext
import dev.clombardo.dnsnet.FileHelper
import dev.clombardo.dnsnet.loge
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import uniffi.net.BlockLoggerCallback

@Serializable
data class LoggedConnection(
    val allowed: Boolean = true,
    var attempts: Long = 0,
    var lastAttemptTime: Long = 0,
) {
    fun attempt(now: Long) {
        attempts++
        lastAttemptTime = now
    }
}

@Serializable
data class BlockLogger(val connections: MutableMap<String, LoggedConnection> = HashMap()): BlockLoggerCallback {
    @Transient
    private var onConnection: ((name: String, connection: LoggedConnection) -> Unit)? = null

    fun setOnConnectionListener(listener: ((name: String, connection: LoggedConnection) -> Unit)?) {
        onConnection = listener
    }

    fun newConnection(name: String, allowed: Boolean) {
        val connection = connections[name]
        val now = System.currentTimeMillis()
        if (connection != null) {
            if (connection.allowed != allowed) {
                connections.remove(name)
                connections[name] = LoggedConnection(allowed, 1, now)
            } else {
                connection.attempt(now)
            }
        } else {
            connections[name] = LoggedConnection(allowed, 1, now)
        }
        onConnection?.invoke(name, connections[name]!!)
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun save(name: String = DEFAULT_LOG_FILENAME) {
        try {
            val outputStream = FileHelper.openWrite(name)
            json.encodeToStream(this, outputStream)
        } catch (e: Exception) {
            loge("Failed to write connection history", e)
        }
    }

    fun clear() {
        connections.clear()
        applicationContext.getFileStreamPath(DEFAULT_LOG_FILENAME).delete()
    }

    companion object {
        private const val DEFAULT_LOG_FILENAME = "connections.json"

        private val json by lazy {
            Json {
                ignoreUnknownKeys = true
            }
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun load(name: String = DEFAULT_LOG_FILENAME): BlockLogger {
            val inputStream =
                FileHelper.openRead(name) ?: return BlockLogger()
            return try {
                json.decodeFromStream<BlockLogger>(inputStream)
            } catch (e: Exception) {
                loge("Failed to load connection history", e)
                BlockLogger()
            }
        }
    }

    override fun log(connectionName: String, allowed: Boolean) {
        newConnection(
            name = connectionName,
            allowed = allowed,
        )
    }
}
