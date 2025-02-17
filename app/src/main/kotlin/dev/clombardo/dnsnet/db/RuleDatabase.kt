/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * Derived from DNS66:
 * Copyright (C) 2016 - 2017 Julian Andres Klode <jak@jak-linux.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.db

import androidx.collection.MutableIntSet
import androidx.collection.intSetOf
import dev.clombardo.dnsnet.FileHelper
import dev.clombardo.dnsnet.Host
import dev.clombardo.dnsnet.HostException
import dev.clombardo.dnsnet.HostFile
import dev.clombardo.dnsnet.HostState
import dev.clombardo.dnsnet.config
import dev.clombardo.dnsnet.logd
import dev.clombardo.dnsnet.loge
import dev.clombardo.dnsnet.logi
import kotlinx.atomicfu.atomic
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.IOException
import java.io.Reader

/**
 * Represents hosts that are blocked.
 * <p>
 * This is a very basic set of hosts. But it supports lock-free
 * readers with writers active at the same time, only the writers
 * having to take a lock.
 */
class RuleDatabase {
    companion object {
        private const val IPV4_LOOPBACK = "127.0.0.1"
        private const val IPV6_LOOPBACK = "::1"
        private const val NO_ROUTE = "0.0.0.0"

        fun parseLine(line: String): String? {
            if (line.isEmpty() || line.isBlank()) {
                return null
            }

            var endOfLine = line.indexOf('#')

            if (endOfLine == -1) {
                endOfLine = line.length
            }

            // The line is empty
            if (endOfLine <= 0) {
                return null
            }

            // Find beginning of host field
            var startOfHost = 0

            val ipv4LoopbackIndex = line.lastIndexOf(IPV4_LOOPBACK)
            if (ipv4LoopbackIndex != -1) {
                startOfHost += (ipv4LoopbackIndex + IPV4_LOOPBACK.length)
            }
            if (startOfHost == 0) {
                val ipv6LoopbackIndex = line.lastIndexOf(IPV6_LOOPBACK)
                if (ipv6LoopbackIndex != -1) {
                    startOfHost += (ipv6LoopbackIndex + IPV6_LOOPBACK.length)
                }
            }
            if (startOfHost == 0) {
                val noRouteIndex = line.lastIndexOf(NO_ROUTE)
                if (noRouteIndex != -1) {
                    startOfHost += (noRouteIndex + NO_ROUTE.length)
                }
            }

            if (startOfHost >= endOfLine) {
                return null
            }

            while (startOfHost < endOfLine && Character.isWhitespace(line[startOfHost])) {
                startOfHost++
            }
            while (startOfHost > endOfLine && Character.isWhitespace(line[endOfLine - 1])) {
                endOfLine--
            }

            val host = line.substring(startOfHost, endOfLine).lowercase()
            if (host.isEmpty() || host.any { Character.isWhitespace(it) }) {
                return null
            }

            return host
        }
    }

    private var blockedHosts by atomic(intSetOf())

    /**
     * Checks if a host is blocked.
     *
     * @param host A hostname
     * @return true if the host is blocked, false otherwise.
     */
    fun isBlocked(host: String): Boolean = blockedHosts.contains(host.hashCode())

    /**
     * Check if any hosts are blocked
     *
     * @return true if any hosts are blocked, false otherwise.
     */
    fun isEmpty(): Boolean = blockedHosts.isEmpty()

    /**
     * Load the hosts according to the configuration
     *
     * @throws InterruptedException Thrown if the thread was interrupted, so we don't waste time
     *                              reading more host files than needed.
     */
    @Synchronized
    @Throws(InterruptedException::class)
    fun initialize() {
        logi("Loading block list")

        val sortedHostItems = config.hosts.items
            .mapNotNull {
                if (it.state != HostState.IGNORE) {
                    it
                } else {
                    null
                }
            }
            .sortedBy { it.state.ordinal }

        val newHosts = MutableIntSet(sortedHostItems.size + config.hosts.exceptions.size)
        for (item in sortedHostItems) {
            if (Thread.interrupted()) {
                throw InterruptedException("Interrupted")
            }
            loadItem(newHosts, item)
        }

        for (exception in config.hosts.exceptions) {
            if (Thread.interrupted()) {
                throw InterruptedException("Interrupted")
            }
            addHostException(newHosts, exception)
        }

        blockedHosts = newHosts
        Runtime.getRuntime().gc()
    }

    /**
     * Loads an item. An item can be backed by a file or contain a value in the location field.
     *
     * @param item    The item to load.
     * @throws InterruptedException If the thread was interrupted.
     */
    @Throws(InterruptedException::class)
    private fun loadItem(set: MutableIntSet, item: HostFile) {
        if (item.state == HostState.IGNORE) {
            return
        }

        val reader = try {
            FileHelper.openItemFile(item)
        } catch (e: FileNotFoundException) {
            logd("loadItem: File not found: ${item.data}", e)
            return
        }

        if (reader == null) {
            addHost(set, item, item.data)
        } else {
            loadReader(set, item, reader)
        }
    }

    /**
     * Add a single host for an item.
     *
     * @param item The item the host belongs to
     * @param host The host
     */
    private fun addHost(set: MutableIntSet, item: Host, host: String) {
        // Single address to block
        if (item.state == HostState.ALLOW) {
            set.remove(host.hashCode())
        } else if (item.state == HostState.DENY) {
            set.add(host.hashCode())
        }
    }

    private fun addHostException(set: MutableIntSet, exception: HostException) {
        when (exception.state) {
            HostState.ALLOW -> set.remove(exception.data.hashCode())
            HostState.DENY -> set.add(exception.data.hashCode())
            else -> return
        }
    }

    /**
     * Load a single file
     *
     * @param item   The configuration item referencing the file
     * @param reader A reader to read lines from
     * @throws InterruptedException If thread was interrupted
     */
    @Throws(InterruptedException::class)
    fun loadReader(set: MutableIntSet, item: Host, reader: Reader): Boolean {
        var count = 0
        try {
            logd("loadBlockedHosts: Reading: ${item.data}")
            BufferedReader(reader).use {
                var line = it.readLine()
                while (line != null) {
                    if (Thread.interrupted()) {
                        throw InterruptedException("Interrupted")
                    }

                    val host = parseLine(line)
                    if (host != null) {
                        count++
                        addHost(set, item, host)
                    }

                    line = it.readLine()
                }
            }

            logd("loadBlockedHosts: Loaded $count hosts from ${item.data}")
            return true
        } catch (e: IOException) {
            loge(
                "loadBlockedHosts: Error while reading ${item.data} after $count items",
                e
            )
            return false
        }
    }
}
