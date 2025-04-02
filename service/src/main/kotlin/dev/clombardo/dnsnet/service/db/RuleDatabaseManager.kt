/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.service.db

import android.content.Context
import dev.clombardo.dnsnet.log.logi
import dev.clombardo.dnsnet.service.NativeFileHelperWrapper
import dev.clombardo.dnsnet.service.toNative
import dev.clombardo.dnsnet.settings.ConfigurationManager
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.net.RuleDatabase
import uniffi.net.RuleDatabaseController
import uniffi.net.RuleDatabaseException

class RuleDatabaseManager(
    private val context: Context,
    private val configuration: ConfigurationManager,
) {
    private val reloadPending = atomic(false)
    private val ruleDatabaseController = RuleDatabaseController()
    val ruleDatabase = RuleDatabase(ruleDatabaseController)

    private suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            ruleDatabase.initialize(
                androidFileHelper = NativeFileHelperWrapper(context),
                hostItems = configuration.read { hosts.items.map { it.toNative() } },
                hostExceptions = configuration.read { hosts.exceptions.map { it.toNative() } },
            )
        } catch (e: RuleDatabaseException) {
            when (e) {
                is RuleDatabaseException.Interrupted -> logi("Interrupted", e)
                else -> throw IllegalStateException("Failed to initialize rule database", e)
            }
        }
    }

    fun reload() {
        logi("Reloading")
        if (reloadPending.getAndSet(true)) {
            logi("Reload already pending")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            if (ruleDatabaseController.isInitialized()) {
                ruleDatabase.waitOnInit()
            }
            reloadPending.getAndSet(false)
            logi("Initializing after wait")
            initialize()
        }
    }

    fun waitOnInit() = ruleDatabase.waitOnInit()

    fun setShouldStop(shouldStop: Boolean) = ruleDatabaseController.setShouldStop(shouldStop)

    fun destroy() {
        waitOnInit()
        ruleDatabase.destroy()
        ruleDatabaseController.destroy()
    }
}
