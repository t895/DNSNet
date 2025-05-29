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

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService.prepare
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.clombardo.dnsnet.log.logDebug
import dev.clombardo.dnsnet.log.logInfo
import dev.clombardo.dnsnet.service.FilterUtil
import dev.clombardo.dnsnet.service.db.RuleDatabaseUpdateWorker
import dev.clombardo.dnsnet.service.vpn.AdVpnService
import dev.clombardo.dnsnet.ui.app.App
import dev.clombardo.dnsnet.ui.app.viewmodel.HomeViewModel
import dev.clombardo.dnsnet.ui.common.theme.DnsNetTheme
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val vm: HomeViewModel by viewModels()

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            DnsNetTheme {
                val importLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
                        it ?: return@rememberLauncherForActivityResult
                        try {
                            vm.configuration.replaceInstance(contentResolver.openInputStream(it)!!)
                        } catch (e: Exception) {
                            logDebug("Cannot read file", e)
                            Toast.makeText(
                                this,
                                "Cannot read file: ${e.message}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        vm.onReloadSettings()
                        AdVpnService.reconnect(this)
                        recreate()
                    }

                val exportLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
                        uri ?: return@rememberLauncherForActivityResult
                        try {
                            contentResolver.openOutputStream(uri).use {
                                vm.configuration.saveOut(it!!)
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                this,
                                "Cannot write file: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                val vpnLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                        if (it.resultCode == Activity.RESULT_CANCELED) {
                            vm.onVpnConfigurationFailure()
                        } else if (it.resultCode == Activity.RESULT_OK) {
                            logDebug("onActivityResult: Starting service")
                            AdVpnService.start(this)
                        }
                    }

                val logcatLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
                        it ?: return@rememberLauncherForActivityResult
                        vm.onWriteLogcat(it)
                    }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true },
                ) {
                    val hazeState = remember { HazeState() }

                    val status by AdVpnService.status.collectAsState()
                    val isDatabaseRefreshing by RuleDatabaseUpdateWorker.isRefreshing.collectAsState()
                    App(
                        modifier = Modifier.hazeSource(hazeState),
                        vm = vm,
                        state = status.toFabState(),
                        isDatabaseRefreshing = isDatabaseRefreshing,
                        onRefreshFilters = { RuleDatabaseUpdateWorker.runNow(this@MainActivity) },
                        onLoadDefaults = {
                            vm.configuration.resetInstance()
                            vm.onReloadSettings()
                            recreate()
                        },
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onExport = { exportLauncher.launch("dnsnet.json") },
                        onShareLogcat = { logcatLauncher.launch("dnsnet-log.txt") },
                        onTryToggleService = { tryToggleService(true, vpnLauncher) },
                        onStartWithoutFiltersCheck = { tryToggleService(false, vpnLauncher) },
                        onReloadVpn = { AdVpnService.reconnect(this@MainActivity) },
                        onReloadDatabase = { AdVpnService.reloadDatabase(this@MainActivity) },
                        onUpdateRefreshWork = ::updateRefreshWork,
                        onOpenNetworkSettings = ::openNetworkSettings,
                    )

                    val localDensity = LocalDensity.current
                    val systemBarShadeHeight =
                        WindowInsets.systemBars.getTop(localDensity) / localDensity.density
                    val surfaceColor = MaterialTheme.colorScheme.surface
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(systemBarShadeHeight.dp)
                            .hazeEffect(
                                state = hazeState,
                                style = HazeDefaults.style(
                                    backgroundColor = surfaceColor,
                                    blurRadius = 4.dp,
                                ),
                                block = {
                                    progressive = HazeProgressive.verticalGradient(
                                        startY = Float.POSITIVE_INFINITY,
                                        endY = 0f,
                                        easing = LinearEasing,
                                        preferPerformance = true,
                                    )
                                }),
                    )
                }
            }
        }

        updateRefreshWork()
    }

    override fun onNewIntent(intent: Intent) {
        if (intent.getBooleanExtra("UPDATE", false)) {
            RuleDatabaseUpdateWorker.runNow(this)
        }

        vm.onCheckForUpdateErrors(RuleDatabaseUpdateWorker.lastErrors)
        RuleDatabaseUpdateWorker.lastErrors = null

        super.onNewIntent(intent)
    }

    private fun tryToggleService(
        hostsCheck: Boolean,
        launcher: ManagedActivityResultLauncher<Intent, ActivityResult>,
    ) {
        if (AdVpnService.isActive()) {
            logInfo("Attempting to disconnect")
            AdVpnService.stop(this)
        } else {
            if (isPrivateDnsEnabled()) {
                vm.onPrivateDnsEnabledWarning()
                return
            }
            if (!FilterUtil.areFilterFilesExistent(this, vm.configuration) && hostsCheck) {
                vm.onFilterFilesNotFound()
                return
            }
            tryStartService(launcher)
        }
    }

    private fun isPrivateDnsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false
        }

        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return false
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return false
        return linkProperties.isPrivateDnsActive || linkProperties.privateDnsServerName != null
    }

    private fun openNetworkSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(
                this,
                getString(R.string.failed_to_open_network_settings),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Starts the AdVpnService. If the user has not allowed this
     * VPN to run before, it will show a dialog and then call
     * onActivityResult with either [Activity.RESULT_CANCELED]
     * or [Activity.RESULT_OK] for deny/allow respectively.
     */
    private fun tryStartService(launcher: ManagedActivityResultLauncher<Intent, ActivityResult>) {
        logInfo("Attempting to connect")
        val intent = prepare(this)
        if (intent != null) {
            launcher.launch(intent)
        } else {
            AdVpnService.start(this)
        }
    }

    private fun updateRefreshWork() {
        val workManager = WorkManager.getInstance(this)
        if (vm.configuration.read { filters.automaticRefresh }) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresDeviceIdle(true)
                .setRequiresStorageNotLow(true)
                .build()

            val work = PeriodicWorkRequestBuilder<RuleDatabaseUpdateWorker>(1, TimeUnit.DAYS)
                .addTag(RuleDatabaseUpdateWorker.PERIODIC_TAG)
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                RuleDatabaseUpdateWorker.PERIODIC_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                work,
            )
        } else {
            workManager.cancelAllWorkByTag(RuleDatabaseUpdateWorker.PERIODIC_TAG)
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onCheckForUpdateErrors(RuleDatabaseUpdateWorker.lastErrors)
        RuleDatabaseUpdateWorker.lastErrors = null
    }

    companion object {
        fun getPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        fun getIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
