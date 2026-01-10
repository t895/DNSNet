/* Copyright (C) 2026 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.prepareGet
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readRemaining
import kotlinx.io.asSink
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkRepository @Inject constructor(
    private val httpClient: HttpClient,
) {
    suspend fun downloadBodyToFile(url: String, outputStream: FileOutputStream): Response {
        val sink = outputStream.asSink()
        var responseCode = Response()
        httpClient.prepareGet(url).execute { response ->
            responseCode = Response(response.status.value, response.status.description)
            val channel: ByteReadChannel = response.body()
            sink.use {
                while (!channel.exhausted()) {
                    val chunk = channel.readRemaining(max = 4096)
                    chunk.transferTo(sink)
                }
            }
        }
        return responseCode
    }
}

data class Response(
    val code: Int = -1,
    val description: String = "",
)
