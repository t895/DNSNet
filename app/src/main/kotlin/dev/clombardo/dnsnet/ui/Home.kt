/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui

import android.annotation.SuppressLint
import android.os.Parcelable
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.window.core.layout.WindowWidthSizeClass
import dev.clombardo.dnsnet.DnsNetApplication.Companion.applicationContext
import dev.clombardo.dnsnet.DnsServer
import dev.clombardo.dnsnet.Host
import dev.clombardo.dnsnet.HostException
import dev.clombardo.dnsnet.HostFile
import dev.clombardo.dnsnet.HostState
import dev.clombardo.dnsnet.Preferences
import dev.clombardo.dnsnet.R
import dev.clombardo.dnsnet.config
import dev.clombardo.dnsnet.db.RuleDatabaseUpdateWorker
import dev.clombardo.dnsnet.ui.navigation.LayoutType
import dev.clombardo.dnsnet.ui.navigation.NavigationScaffold
import dev.clombardo.dnsnet.ui.theme.Animation
import dev.clombardo.dnsnet.ui.theme.DefaultFabSize
import dev.clombardo.dnsnet.ui.theme.FabPadding
import dev.clombardo.dnsnet.ui.theme.ListPadding
import dev.clombardo.dnsnet.ui.theme.VpnFabSize
import dev.clombardo.dnsnet.viewmodel.HomeViewModel
import dev.clombardo.dnsnet.vpn.AdVpnService
import dev.clombardo.dnsnet.vpn.VpnStatus
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
enum class HomeDestinationIcon(val icon: ImageVector) {
    Start(Icons.Default.VpnKey),
    Hosts(Icons.AutoMirrored.Filled.DriveFileMove),
    Apps(Icons.Default.Android),
    DNS(Icons.Default.Dns);
}

@Parcelize
@Serializable
open class HomeDestination(
    val iconEnum: HomeDestinationIcon,
    @StringRes val labelResId: Int,
) : Parcelable

object HomeDestinations {
    val entries = listOf(Start, Hosts, Apps, DNS)

    @Parcelize
    @Serializable
    data object Start : HomeDestination(HomeDestinationIcon.Start, R.string.start_tab)

    @Parcelize
    @Serializable
    data object Hosts : HomeDestination(HomeDestinationIcon.Hosts, R.string.hosts_tab)

    @Parcelize
    @Serializable
    data object Apps : HomeDestination(HomeDestinationIcon.Apps, R.string.allowlist_tab)

    @Parcelize
    @Serializable
    data object DNS : HomeDestination(HomeDestinationIcon.DNS, R.string.dns_tab)
}

@Parcelize
@Serializable
sealed class TopLevelDestination : Parcelable {
    @Parcelize
    @Serializable
    data object About : TopLevelDestination()

    @Parcelize
    @Serializable
    data object Home : TopLevelDestination()

    @Parcelize
    @Serializable
    data object BlockLog : TopLevelDestination()

    @Parcelize
    @Serializable
    data object Credits : TopLevelDestination()

    @Parcelize
    @Serializable
    data object Greeting : TopLevelDestination()

    @Parcelize
    @Serializable
    data object Notice : TopLevelDestination()
}

object Home {
    val NavigationEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition by lazy {
        {
            scaleIn(
                initialScale = 0.75f,
                animationSpec = tween(
                    durationMillis = 400,
                    easing = Animation.EmphasizedDecelerateEasing
                ),
            ) + fadeIn(animationSpec = tween(400))
        }
    }
    val NavigationExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition by lazy {
        { fadeOut(animationSpec = tween(50)) }
    }

    private const val SIZE_FRACTION = 12

    private fun getOffsetForTopLevelEnter(fullSize: IntSize): IntOffset =
        IntOffset(fullSize.width / SIZE_FRACTION, 0)

    private fun getOffsetForTopLevelExit(fullSize: IntSize): IntOffset =
        IntOffset(-fullSize.width / SIZE_FRACTION, 0)

    private val TopLevelFadeEnterSpec by lazy { tween<Float>(400) }
    private val TopLevelFadeExitSpec by lazy { tween<Float>(100) }

    val TopLevelEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition by lazy {
        {
            slideIn(initialOffset = ::getOffsetForTopLevelEnter) +
                    fadeIn(animationSpec = TopLevelFadeEnterSpec)
        }
    }
    val TopLevelPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition by lazy {
        {
            slideIn(initialOffset = ::getOffsetForTopLevelExit) +
                    fadeIn(animationSpec = TopLevelFadeEnterSpec)
        }
    }
    val TopLevelExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition by lazy {
        {
            slideOut(targetOffset = ::getOffsetForTopLevelExit) +
                    fadeOut(animationSpec = TopLevelFadeExitSpec)
        }
    }
    val TopLevelPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition by lazy {
        {
            slideOut(targetOffset = ::getOffsetForTopLevelEnter) +
                    fadeOut(animationSpec = TopLevelFadeExitSpec)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@SuppressLint("RestrictedApi")
@Composable
fun App(
    modifier: Modifier = Modifier,
    vm: HomeViewModel = viewModel(),
    onRefreshHosts: () -> Unit,
    onLoadDefaults: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onShareLogcat: () -> Unit,
    onTryToggleService: () -> Unit,
    onStartWithoutHostsCheck: () -> Unit,
    onRestartService: () -> Unit,
    onUpdateRefreshWork: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
) {
    val showUpdateIncompleteDialog by vm.showUpdateIncompleteDialog.collectAsState()
    if (showUpdateIncompleteDialog) {
        val messageText = StringBuilder(stringResource(R.string.update_incomplete_description))
        val errorText = remember {
            if (vm.errors != null) {
                messageText.append("\n")
            }
            vm.errors?.forEach {
                messageText.append("$it\n")
            }
            messageText.toString()
        }
        BasicDialog(
            title = stringResource(R.string.update_incomplete),
            text = errorText,
            primaryButton = DialogButton(
                text = stringResource(android.R.string.ok),
                onClick = { vm.onDismissUpdateIncomplete() },
            ),
            onDismissRequest = { vm.onDismissUpdateIncomplete() },
        )
    }

    val showHostsFilesNotFoundDialog by vm.showHostsFilesNotFoundDialog.collectAsState()
    if (showHostsFilesNotFoundDialog) {
        BasicDialog(
            title = stringResource(R.string.missing_hosts_files_title),
            text = stringResource(R.string.missing_hosts_files_message),
            primaryButton = DialogButton(
                text = stringResource(R.string.button_yes),
                onClick = {
                    onStartWithoutHostsCheck()
                    vm.onDismissHostsFilesNotFound()
                },
            ),
            secondaryButton = DialogButton(
                text = stringResource(R.string.button_no),
                onClick = { vm.onDismissHostsFilesNotFound() },
            ),
            onDismissRequest = { vm.onDismissHostsFilesNotFound() },
        )
    }

    val showFilePermissionDeniedDialog by vm.showFilePermissionDeniedDialog.collectAsState()
    if (showFilePermissionDeniedDialog) {
        BasicDialog(
            title = stringResource(R.string.permission_denied),
            text = stringResource(R.string.persistable_uri_permission_failed),
            primaryButton = DialogButton(
                text = stringResource(android.R.string.ok),
                onClick = { vm.onDismissFilePermissionDenied() },
            ),
            onDismissRequest = { vm.onDismissFilePermissionDenied() },
        )
    }

    val showVpnConfigurationFailureDialog by vm.showVpnConfigurationFailureDialog.collectAsState()
    if (showVpnConfigurationFailureDialog) {
        BasicDialog(
            title = stringResource(R.string.could_not_start_vpn),
            text = stringResource(R.string.could_not_start_vpn_description),
            primaryButton = DialogButton(
                text = stringResource(android.R.string.ok),
                onClick = { vm.onDismissVpnConfigurationFailure() },
            ),
            onDismissRequest = { vm.onDismissVpnConfigurationFailure() },
        )
    }

    val showDisablePrivateDnsDialog by vm.showDisablePrivateDnsDialog.collectAsState()
    if (showDisablePrivateDnsDialog) {
        BasicDialog(
            title = stringResource(R.string.private_dns_error),
            text = stringResource(R.string.private_dns_error_description),
            primaryButton = DialogButton(
                text = stringResource(R.string.open_settings),
                onClick = onOpenNetworkSettings,
            ),
            secondaryButton = DialogButton(
                text = stringResource(R.string.close),
                onClick = { vm.onDismissPrivateDnsEnabledWarning() },
            ),
            tertiaryButton = DialogButton(
                text = stringResource(R.string.try_again),
                onClick = {
                    vm.onDismissPrivateDnsEnabledWarning()
                    onTryToggleService()
                },
            ),
            onDismissRequest = {},
        )
    }

    val showResetSettingsWarningDialog by vm.showResetSettingsWarningDialog.collectAsState()
    if (showResetSettingsWarningDialog) {
        BasicDialog(
            title = stringResource(R.string.warning),
            text = stringResource(R.string.reset_settings_warning_description),
            primaryButton = DialogButton(
                text = stringResource(R.string.reset),
                onClick = {
                    onLoadDefaults()
                    vm.onDismissResetSettingsDialog()
                    onRestartService()
                },
            ),
            secondaryButton = DialogButton(
                text = stringResource(R.string.button_cancel),
                onClick = { vm.onDismissResetSettingsDialog() },
            ),
            onDismissRequest = { vm.onDismissResetSettingsDialog() },
        )
    }

    SharedTransitionLayout {
        val navController = rememberNavController()
        NavHost(
            modifier = modifier.background(MaterialTheme.colorScheme.surface),
            navController = navController,
            startDestination = TopLevelDestination.Home,
            enterTransition = Home.TopLevelEnter,
            exitTransition = Home.TopLevelExit,
            popEnterTransition = Home.TopLevelPopEnter,
            popExitTransition = Home.TopLevelPopExit,
        ) {
            composable<TopLevelDestination.Greeting> {
                vm.hideStatusBarShade()
                GreetingScreen(
                    onGetStartedClick = {
                        navController.popNavigate(TopLevelDestination.Notice)
                    },
                    animatedVisibilityScope = this@composable,
                    sharedTransitionScope = this@SharedTransitionLayout,
                )
            }
            composable<TopLevelDestination.Notice> {
                vm.showStatusBarShade()
                NoticeScreen(
                    onContinueClick = {
                        Preferences.SetupComplete = true
                        navController.popNavigate(TopLevelDestination.Home)
                    },
                    animatedVisibilityScope = this@composable,
                    sharedTransitionScope = this@SharedTransitionLayout,
                )
            }
            composable<TopLevelDestination.Home> {
                if (!Preferences.SetupComplete && !vm.setupShown) {
                    vm.setupShown = true
                    navController.navigate(TopLevelDestination.Greeting)
                }

                if (!navController.containsRoute<TopLevelDestination.Greeting>() &&
                    !navController.containsRoute<TopLevelDestination.Notice>()
                ) {
                    vm.showStatusBarShade()
                    val status by AdVpnService.status.collectAsState()
                    HomeScreen(
                        vm = vm,
                        topLevelNavController = navController,
                        status = status,
                        onRefreshHosts = onRefreshHosts,
                        onImport = onImport,
                        onExport = onExport,
                        onShareLogcat = onShareLogcat,
                        onTryToggleService = onTryToggleService,
                        onRestartService = onRestartService,
                        onUpdateRefreshWork = onUpdateRefreshWork,
                    )
                }
            }
            composable<HostFile> { backstackEntry ->
                vm.hideStatusBarShade()
                val host = backstackEntry.toRoute<HostFile>()
                EditHostDestination(
                    host = host,
                    vm = vm,
                    onPopBackStack = { navController.tryPopBackstack(backstackEntry.id) },
                    onRestartService = onRestartService,
                )
            }
            composable<HostException> { backstackEntry ->
                vm.hideStatusBarShade()
                val host = backstackEntry.toRoute<HostException>()
                EditHostDestination(
                    host = host,
                    vm = vm,
                    onPopBackStack = { navController.tryPopBackstack(backstackEntry.id) },
                    onRestartService = onRestartService,
                )
            }
            composable<DnsServer> { backstackEntry ->
                vm.hideStatusBarShade()
                val server = backstackEntry.toRoute<DnsServer>()

                val showDeleteDnsServerWarningDialog by
                vm.showDeleteDnsServerWarningDialog.collectAsState()
                if (showDeleteDnsServerWarningDialog) {
                    BasicDialog(
                        title = stringResource(R.string.warning),
                        text = stringResource(
                            R.string.permanently_delete_warning_description,
                            server.title
                        ),
                        primaryButton = DialogButton(
                            text = stringResource(R.string.action_delete),
                            onClick = {
                                vm.removeDnsServer(server)
                                vm.onDismissDeleteDnsServerWarning()
                                navController.tryPopBackstack(backstackEntry.id)
                                onRestartService()
                            },
                        ),
                        secondaryButton = DialogButton(
                            text = stringResource(android.R.string.cancel),
                            onClick = { vm.onDismissDeleteDnsServerWarning() },
                        ),
                        onDismissRequest = { vm.onDismissDeleteDnsServerWarning() },
                    )
                }

                EditDnsScreen(
                    server = server,
                    onNavigateUp = { navController.tryPopBackstack(backstackEntry.id) },
                    onSave = { savedServer ->
                        if (server.title.isEmpty()) {
                            vm.addDnsServer(savedServer)
                        } else {
                            vm.replaceDnsServer(server, savedServer)
                        }
                        navController.tryPopBackstack(backstackEntry.id)
                        onRestartService()
                    },
                    onDelete = if (server.title.isEmpty()) {
                        null
                    } else {
                        { vm.onDeleteDnsServerWarning() }
                    },
                )
            }
            composable<TopLevelDestination.About> {
                vm.hideStatusBarShade()
                AboutScreen(
                    onNavigateUp = { navController.tryPopBackstack(it.id) },
                    onOpenCredits = { navController.navigate(TopLevelDestination.Credits) },
                )
            }
            composable<TopLevelDestination.BlockLog> {
                vm.hideStatusBarShade()
                BlockLogScreen(
                    onNavigateUp = { navController.tryPopBackstack(it.id) },
                    listViewModel = viewModel(),
                    loggedConnections = vm.connectionsLog,
                    onCreateException = {
                        navController.navigate(
                            HostException(
                                title = "",
                                data = it.hostname,
                                state = if (it.allowed) {
                                    HostState.DENY
                                } else {
                                    HostState.ALLOW
                                },
                            )
                        )
                    },
                )
            }
            composable<TopLevelDestination.Credits> {
                vm.hideStatusBarShade()
                CreditsScreen { navController.tryPopBackstack(it.id) }
            }
        }
    }
}

@Composable
fun EditHostDestination(
    host: Host,
    vm: HomeViewModel,
    onPopBackStack: () -> Unit,
    onRestartService: () -> Unit,
) {
    val showDeleteHostWarningDialog by vm.showDeleteHostWarningDialog.collectAsState()
    if (showDeleteHostWarningDialog) {
        BasicDialog(
            title = stringResource(R.string.warning),
            text = stringResource(
                R.string.permanently_delete_warning_description,
                host.title,
            ),
            primaryButton = DialogButton(
                text = stringResource(R.string.action_delete),
                onClick = {
                    vm.removeHost(host)
                    vm.onDismissDeleteHostWarning()
                    onPopBackStack()
                    onRestartService()
                },
            ),
            secondaryButton = DialogButton(
                text = stringResource(android.R.string.cancel),
                onClick = { vm.onDismissDeleteHostWarning() },
            ),
            onDismissRequest = { vm.onDismissDeleteHostWarning() },
        )
    }

    EditHostScreen(
        host = host,
        onNavigateUp = onPopBackStack,
        onSave = { hostToSave ->
            if (host.title.isEmpty()) {
                vm.addHost(hostToSave)
                if (host is HostException) {
                    vm.removeBlockLogEntry(host.data)
                }
            } else {
                vm.replaceHost(host, hostToSave)
            }
            onPopBackStack()
            onRestartService()
        },
        onDelete = if (host.title.isEmpty()) {
            null
        } else {
            { vm.onDeleteHostWarning() }
        },
        onUriPermissionAcquireFailed = if (host is HostFile) {
            { vm.onFilePermissionDenied() }
        } else {
            null
        },
    )
}

@Preview
@Composable
fun AppPreview() {
    App(
        onRefreshHosts = {},
        onLoadDefaults = {},
        onImport = {},
        onExport = {},
        onShareLogcat = {},
        onTryToggleService = {},
        onStartWithoutHostsCheck = {},
        onRestartService = {},
        onUpdateRefreshWork = {},
        onOpenNetworkSettings = {},
    )
}

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    vm: HomeViewModel,
    topLevelNavController: NavHostController,
    status: VpnStatus,
    onRefreshHosts: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onShareLogcat: () -> Unit,
    onTryToggleService: () -> Unit,
    onRestartService: () -> Unit,
    onUpdateRefreshWork: () -> Unit,
) {
    val navController = rememberNavController()
    var currentDestination: HomeDestination by rememberSaveable {
        mutableStateOf(HomeDestinations.Start)
    }

    val setDestination = { newHomeDestination: HomeDestination ->
        if (currentDestination != newHomeDestination) {
            currentDestination = newHomeDestination
            navController.popNavigate(newHomeDestination)
        }
    }

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    NavigationScaffold(
        modifier = modifier,
        layoutType = if (windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.COMPACT) {
            LayoutType.NavigationBar
        } else {
            LayoutType.NavigationRail
        },
        navigationItems = {
            HomeDestinations.entries.forEach {
                item(
                    modifier = Modifier.testTag("homeNavigation:${applicationContext.getString(it.labelResId)}"),
                    selected = it == currentDestination,
                    onClick = { setDestination(it) },
                    icon = it.iconEnum.icon,
                    textId = it.labelResId,
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                modifier = Modifier.padding(16.dp),
                visible = currentDestination == HomeDestinations.Hosts ||
                        currentDestination == HomeDestinations.DNS,
                enter = NavigationScaffold.FabEnter,
                exit = NavigationScaffold.FabExit,
            ) {
                val add = stringResource(R.string.add)
                if (currentDestination == HomeDestinations.Hosts) {
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    ExpandableFloatingActionButton(
                        expanded = expanded,
                        onClick = { expanded = !expanded },
                        onDismissRequest = { expanded = false },
                        buttonContent = {
                            val rotation by animateFloatAsState(
                                targetValue = if (expanded) 45f else 0f,
                            )
                            Icon(
                                modifier = Modifier.rotate(rotation),
                                imageVector = Icons.Default.Add,
                                contentDescription = add
                            )
                        }
                    ) {
                        item(
                            icon = Icons.AutoMirrored.Filled.DriveFileMove,
                            textId = R.string.add_hosts_file,
                            onClick = {
                                expanded = false
                                topLevelNavController.navigate(HostFile())
                            }
                        )
                        item(
                            icon = Icons.Default.Shield,
                            textId = R.string.add_host,
                            onClick = {
                                expanded = false
                                topLevelNavController.navigate(HostException())
                            }
                        )
                    }
                } else {
                    FloatingActionButton(
                        onClick = {
                            if (currentDestination == HomeDestinations.Hosts) {
                                topLevelNavController.navigate(HostFile())
                            } else if (currentDestination == HomeDestinations.DNS) {
                                topLevelNavController.navigate(DnsServer())
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = add,
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        // List state must be hoisted outside of the NavHost or it will be lost on recomposition
        val startListState = rememberLazyGridState()
        val hostsListState = rememberLazyListState()
        val appListState = rememberLazyListState()
        val dnsListState = rememberLazyListState()
        NavHost(
            navController = navController,
            startDestination = HomeDestinations.Start,
            enterTransition = Home.NavigationEnterTransition,
            exitTransition = Home.NavigationExitTransition,
            popEnterTransition = Home.NavigationEnterTransition,
            popExitTransition = Home.NavigationExitTransition,
        ) {
            composable<HomeDestinations.Start> {
                vm.showStatusBarShade()
                var resumeOnStartup by remember { mutableStateOf(config.autoStart) }
                var ipv6Support by remember { mutableStateOf(config.ipV6Support) }
                var blockLog by remember { mutableStateOf(config.blockLogging) }

                val showDisableBlockLogWarningDialog by vm.showDisableBlockLogWarningDialog.collectAsState()
                if (showDisableBlockLogWarningDialog) {
                    BasicDialog(
                        title = stringResource(R.string.warning),
                        text = stringResource(R.string.disable_block_log_warning_description),
                        primaryButton = DialogButton(
                            text = stringResource(R.string.disable),
                            onClick = {
                                vm.onClearBlockLog()
                                config.blockLogging = false
                                blockLog = false
                                config.save()
                                onRestartService()
                                vm.onDismissDisableBlockLogWarning()
                            },
                        ),
                        secondaryButton = DialogButton(
                            text = stringResource(R.string.close),
                            onClick = { vm.onDismissDisableBlockLogWarning() },
                        ),
                        onDismissRequest = { vm.onDismissDisableBlockLogWarning() },
                    )
                }

                val isWritingLogcat by vm.isWritingLogcat.collectAsState()
                StartScreen(
                    contentPadding = contentPadding + PaddingValues(ListPadding) +
                            PaddingValues(bottom = VpnFabSize + FabPadding),
                    listState = startListState,
                    resumeOnStartup = resumeOnStartup,
                    onResumeOnStartupClick = {
                        config.autoStart = !config.autoStart
                        resumeOnStartup = config.autoStart
                        config.save()
                    },
                    ipv6Support = ipv6Support,
                    onIpv6SupportClick = {
                        config.ipV6Support = !config.ipV6Support
                        ipv6Support = config.ipV6Support
                        config.save()
                        onRestartService()
                    },
                    blockLog = blockLog,
                    onToggleBlockLog = {
                        if (blockLog) {
                            vm.onDisableBlockLogWarning()
                        } else {
                            config.blockLogging = !config.blockLogging
                            blockLog = config.blockLogging
                            config.save()
                            onRestartService()
                        }
                    },
                    onOpenBlockLog = {
                        topLevelNavController.navigate(TopLevelDestination.BlockLog)
                    },
                    onImport = onImport,
                    onExport = onExport,
                    isWritingLogcat = isWritingLogcat,
                    onShareLogcat = onShareLogcat,
                    onResetSettings = { vm.onResetSettingsWarning() },
                    onOpenAbout = { topLevelNavController.navigate(TopLevelDestination.About) },
                    status = status,
                    onChangeVpnStatusClick = onTryToggleService,
                )
            }
            composable<HomeDestinations.Hosts> {
                vm.showStatusBarShade()
                var refreshDaily by remember { mutableStateOf(config.hosts.automaticRefresh) }
                val isRefreshingHosts by RuleDatabaseUpdateWorker.isRefreshing.collectAsState()
                HostsScreen(
                    contentPadding = contentPadding + PaddingValues(ListPadding) +
                            PaddingValues(bottom = DefaultFabSize + FabPadding),
                    listState = hostsListState,
                    refreshDaily = refreshDaily,
                    onRefreshDailyClick = {
                        config.hosts.automaticRefresh = !config.hosts.automaticRefresh
                        refreshDaily = config.hosts.automaticRefresh
                        config.save()
                        onUpdateRefreshWork()
                    },
                    hosts = vm.hosts,
                    onHostClick = { host ->
                        topLevelNavController.navigate(host)
                    },
                    onHostStateChanged = { host ->
                        vm.cycleHost(host)
                        onRestartService()
                    },
                    isRefreshingHosts = isRefreshingHosts,
                    onRefreshHosts = onRefreshHosts,
                )
            }

            composable<HomeDestinations.Apps> {
                vm.showStatusBarShade()
                val isRefreshing by vm.appListRefreshing.collectAsState()
                var allowlistDefault by remember { mutableStateOf(config.appList.defaultMode) }
                AppsScreen(
                    contentPadding = contentPadding + PaddingValues(ListPadding),
                    listState = appListState,
                    listViewModel = viewModel(),
                    isRefreshing = isRefreshing,
                    onRefresh = { vm.populateAppList() },
                    bypassSelection = allowlistDefault,
                    onBypassSelection = { selection ->
                        config.appList.defaultMode = selection
                        allowlistDefault = selection
                        config.save()
                        onRestartService()
                        vm.populateAppList()
                    },
                    apps = vm.appList,
                    onAppClick = { app, enabled ->
                        vm.onToggleApp(app, enabled)
                        onRestartService()
                    },
                )
            }
            composable<HomeDestinations.DNS> {
                vm.showStatusBarShade()
                var customDnsServers by remember { mutableStateOf(config.dnsServers.enabled) }
                DnsScreen(
                    contentPadding = contentPadding + PaddingValues(ListPadding) +
                            PaddingValues(bottom = DefaultFabSize + FabPadding),
                    listState = dnsListState,
                    servers = vm.dnsServers,
                    customDnsServers = customDnsServers,
                    onCustomDnsServersClick = {
                        config.dnsServers.enabled = !config.dnsServers.enabled
                        customDnsServers = config.dnsServers.enabled
                        config.save()
                        onRestartService()
                    },
                    onItemClick = { item ->
                        topLevelNavController.navigate(item)
                    },
                    onItemCheckClicked = { item ->
                        vm.toggleDnsServer(item)
                        if (customDnsServers) {
                            onRestartService()
                        }
                    },
                )
            }
        }
    }
}
