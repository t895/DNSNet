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
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeEffectScope
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.clombardo.dnsnet.db.RuleDatabaseUpdateWorker
import dev.clombardo.dnsnet.ui.App
import dev.clombardo.dnsnet.ui.theme.Animation
import dev.clombardo.dnsnet.ui.theme.DnsNetTheme
import dev.clombardo.dnsnet.viewmodel.HomeViewModel
import dev.clombardo.dnsnet.vpn.AdVpnService
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val vm: HomeViewModel by viewModels()

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setContent {
            enableEdgeToEdge()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }

            DnsNetTheme {
                val importLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
                        it ?: return@rememberLauncherForActivityResult
                        try {
                            config = Configuration.load(contentResolver.openInputStream(it)!!)
                        } catch (e: Exception) {
                            logd("Cannot read file", e)
                            Toast.makeText(
                                this,
                                "Cannot read file: ${e.message}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        config.save()
                        vm.onReloadSettings()
                        AdVpnService.restart(this)
                        recreate()
                    }

                val exportLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
                        uri ?: return@rememberLauncherForActivityResult
                        try {
                            contentResolver.openOutputStream(uri).use {
                                config.save(it!!)
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
                            logd("onActivityResult: Starting service")
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

                    App(
                        modifier = Modifier.hazeSource(hazeState),
                        vm = vm,
                        onRefreshHosts = ::refresh,
                        onLoadDefaults = {
                            config = Configuration()
                            config.save()
                            vm.onReloadSettings()
                            recreate()
                        },
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onExport = { exportLauncher.launch("dnsnet.json") },
                        onShareLogcat = { logcatLauncher.launch("dnsnet-log.txt") },
                        onTryToggleService = { tryToggleService(true, vpnLauncher) },
                        onStartWithoutHostsCheck = { tryToggleService(false, vpnLauncher) },
                        onRestartService = { AdVpnService.restart(this@MainActivity) },
                        onUpdateRefreshWork = ::updateRefreshWork,
                        onOpenNetworkSettings = ::openNetworkSettings,
                    )

                    val localDensity = LocalDensity.current
                    val systemBarShadeHeight =
                        WindowInsets.systemBars.getTop(localDensity) / localDensity.density
                    val showStatusBarShade by vm.showStatusBarShade.collectAsState()
                    AnimatedVisibility(
                        visible = showStatusBarShade,
                        enter = Animation.ShowStatusBarShade,
                        exit = Animation.HideStatusBarShade,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(systemBarShadeHeight.dp)
                                .hazeEffect(state = hazeState,
                                    style = HazeDefaults.style(
                                        backgroundColor = MaterialTheme.colorScheme.surface,
                                        blurRadius = 1.dp,
                                    ),
                                    block = fun HazeEffectScope.() {
                                        mask = Brush.verticalGradient(
                                            0f to Color.White,
                                            1f to Color.Transparent,
                                        )
                                    }),
                        )
                    }
                }
            }
        }

        if (!areHostsFilesExistent() && savedInstanceState == null) {
            refresh()
        }

        updateRefreshWork()
    }

    override fun onNewIntent(intent: Intent) {
        if (intent.getBooleanExtra("UPDATE", false)) {
            refresh()
        }

        vm.onCheckForUpdateErrors()

        super.onNewIntent(intent)
    }

    private fun refresh() {
        val workRequest = OneTimeWorkRequestBuilder<RuleDatabaseUpdateWorker>()
            .build()
        WorkManager.getInstance(this).enqueue(workRequest)
    }

    private fun tryToggleService(
        hostsCheck: Boolean,
        launcher: ManagedActivityResultLauncher<Intent, ActivityResult>,
    ) {
        if (AdVpnService.isRunning()) {
            logi("Attempting to disconnect")
            AdVpnService.stop(this)
        } else {
            if (isPrivateDnsEnabled()) {
                vm.onPrivateDnsEnabledWarning()
                return
            }
            if (!areHostsFilesExistent() && hostsCheck) {
                vm.onHostsFilesNotFound()
                return
            }
            tryStartService(launcher)
        }
    }

    /**
     * Check if all configured hosts files exist.
     *
     * @return true if all host files exist or no host files were configured.
     */
    private fun areHostsFilesExistent(): Boolean {
        if (!config.hosts.enabled) {
            return true
        }

        for (item in config.hosts.items) {
            if (item.state != HostState.IGNORE) {
                try {
                    val reader = FileHelper.openItemFile(item) ?: return false
                    reader.close()
                } catch (e: IOException) {
                    logi("areHostFilesExistent: Failed to open file {$item}", e)
                    return false
                }
            }
        }
        return true
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
        logi("Attempting to connect")
        val intent = prepare(DnsNetApplication.applicationContext)
        if (intent != null) {
            launcher.launch(intent)
        } else {
            AdVpnService.start(this)
        }
    }

    private fun updateRefreshWork() {
        val workManager = WorkManager.getInstance(this)
        if (config.hosts.automaticRefresh) {
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
        vm.onCheckForUpdateErrors()
    }
}
