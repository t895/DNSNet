/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.service.db

import android.content.Context
import dev.clombardo.dnsnet.common.logInfo
import dev.clombardo.dnsnet.common.logWarning
import dev.clombardo.dnsnet.service.NativeFileHelperWrapper
import dev.clombardo.dnsnet.service.toNative
import dev.clombardo.dnsnet.settings.ConfigurationManager
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import uniffi.net_bindings.RuleDatabaseBinding
import uniffi.net_bindings.RuleDatabaseControllerBinding
import uniffi.net_bindings.RuleDatabaseErrorBinding

class RuleDatabaseManager(
    private val context: Context,
    private val configuration: ConfigurationManager,
) {
    private val reloadLock = Semaphore(1)
    private val pendingReloadLock = Semaphore(1)
    private var destroyed by atomic(false)

    private val ruleDatabaseController = RuleDatabaseControllerBinding()
    val ruleDatabase = RuleDatabaseBinding(ruleDatabaseController)

    private suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            ruleDatabase.initialize(
                fileHelper = NativeFileHelperWrapper(context),
                filterFiles = configuration.read { this.filters.files.map { it.toNative() } },
                singleFilters = configuration.read { filters.singleFilters.map { it.toNative() } },
            )
        } catch (e: RuleDatabaseErrorBinding) {
            when (e) {
                is RuleDatabaseErrorBinding.Interrupted -> logInfo("Interrupted", e)
                else -> throw IllegalStateException("Failed to initialize rule database", e)
            }
        }
    }

    fun reload() {
        logInfo("Reloading")
        if (!pendingReloadLock.tryAcquire()) {
            logInfo("Reload already pending")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            reloadLock.acquire()
            pendingReloadLock.release()

            if (destroyed) {
                logWarning("Tried to initialize destroyed database")
                reloadLock.release()
                return@launch
            }

            if (ruleDatabaseController.isInitialized()) {
                ruleDatabase.waitOnInit()
            }

            logInfo("Initializing after wait")
            try {
                initialize()
            } catch (e: Exception) {
                throw e
            } finally {
                reloadLock.release()
            }
        }
    }

    fun waitOnInit() = ruleDatabase.waitOnInit()

    fun setShouldStop(shouldStop: Boolean) = ruleDatabaseController.setShouldStop(shouldStop)

    fun destroy() {
        destroyed = true
        waitOnInit()
        ruleDatabase.destroy()
        ruleDatabaseController.destroy()
    }
}
