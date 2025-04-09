/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.app

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Bolt
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.window.core.layout.WindowWidthSizeClass
import dev.clombardo.dnsnet.settings.DnsServer
import dev.clombardo.dnsnet.settings.Host
import dev.clombardo.dnsnet.settings.HostException
import dev.clombardo.dnsnet.settings.HostFile
import dev.clombardo.dnsnet.settings.HostState
import dev.clombardo.dnsnet.ui.app.viewmodel.HomeViewModel
import dev.clombardo.dnsnet.ui.common.BasicDialog
import dev.clombardo.dnsnet.ui.common.DialogButton
import dev.clombardo.dnsnet.ui.common.ExpandableFloatingActionButton
import dev.clombardo.dnsnet.ui.common.FabState
import dev.clombardo.dnsnet.ui.common.navigation.LayoutType
import dev.clombardo.dnsnet.ui.common.navigation.NavigationScaffold
import dev.clombardo.dnsnet.ui.common.plus
import dev.clombardo.dnsnet.ui.common.theme.Animation
import dev.clombardo.dnsnet.ui.common.theme.DefaultFabSize
import dev.clombardo.dnsnet.ui.common.theme.FabPadding
import dev.clombardo.dnsnet.ui.common.theme.ListPadding
import dev.clombardo.dnsnet.ui.common.theme.VpnFabSize
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

    @Parcelize
    @Serializable
    data class Presets(val canGoBack: Boolean) : TopLevelDestination()
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

    const val TEST_TAG_IGNORE_MISSING_HOSTS_BUTTON = "ignore_missing_hosts_button"
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalComposeUiApi::class)
@SuppressLint("RestrictedApi")
@Composable
fun App(
    modifier: Modifier = Modifier,
    vm: HomeViewModel = viewModel(),
    state: FabState,
    isDatabaseRefreshing: Boolean,
    onRefreshHosts: () -> Unit,
    onLoadDefaults: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onShareLogcat: () -> Unit,
    onTryToggleService: () -> Unit,
    onStartWithoutHostsCheck: () -> Unit,
    onReloadVpn: () -> Unit,
    onReloadDatabase: () -> Unit,
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
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = stringResource(R.string.missing_hosts_files_title),
            text = stringResource(R.string.missing_hosts_files_message),
            primaryButton = DialogButton(
                modifier = Modifier.testTag(Home.TEST_TAG_IGNORE_MISSING_HOSTS_BUTTON),
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
                    onReloadVpn()
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
                GreetingScreen(
                    onGetStartedClick = {
                        navController.popNavigate(TopLevelDestination.Notice)
                    },
                    animatedVisibilityScope = this@composable,
                    sharedTransitionScope = this@SharedTransitionLayout,
                )
            }
            composable<TopLevelDestination.Notice> {
                NoticeScreen(
                    onContinueClick = {
                        navController.popNavigate(TopLevelDestination.Presets(canGoBack = false))
                    },
                    animatedVisibilityScope = this@composable,
                    sharedTransitionScope = this@SharedTransitionLayout,
                )
            }
            composable<TopLevelDestination.Presets> { backstackEntry ->
                val route = backstackEntry.toRoute<TopLevelDestination.Presets>()
                PresetsScreen(
                    canGoBack = route.canGoBack,
                    onNavigateUp = { navController.tryPopBackstack(backstackEntry.id) },
                    onComplete = {
                        vm.addBlockLists(it)
                        onRefreshHosts()
                        if (route.canGoBack) {
                            navController.tryPopBackstack(backstackEntry.id)
                        } else {
                            vm.preferences.SetupComplete = true
                            navController.popNavigate(TopLevelDestination.Home)
                        }
                    },
                )
            }
            composable<TopLevelDestination.Home> {
                if (!vm.preferences.SetupComplete && !vm.setupShown) {
                    vm.setupShown = true
                    navController.navigate(TopLevelDestination.Greeting)
                }

                if (!navController.containsRoute<TopLevelDestination.Greeting>() &&
                    !navController.containsRoute<TopLevelDestination.Notice>()
                ) {
                    HomeScreen(
                        vm = vm,
                        topLevelNavController = navController,
                        state = state,
                        isDatabaseRefreshing = isDatabaseRefreshing,
                        onRefreshHosts = onRefreshHosts,
                        onImport = onImport,
                        onExport = onExport,
                        onShareLogcat = onShareLogcat,
                        onTryToggleService = onTryToggleService,
                        onReloadVpn = onReloadVpn,
                        onReloadDatabase = onReloadDatabase,
                        onUpdateRefreshWork = onUpdateRefreshWork,
                    )
                }
            }
            composable<HostFile> { backstackEntry ->
                val host = backstackEntry.toRoute<HostFile>()
                EditHostDestination(
                    host = host,
                    vm = vm,
                    onPopBackStack = { navController.tryPopBackstack(backstackEntry.id) },
                    onReloadVpn = onReloadVpn,
                    onReloadDatabase = onReloadDatabase,
                )
            }
            composable<HostException> { backstackEntry ->
                val host = backstackEntry.toRoute<HostException>()
                EditHostDestination(
                    host = host,
                    vm = vm,
                    onPopBackStack = { navController.tryPopBackstack(backstackEntry.id) },
                    onReloadVpn = onReloadVpn,
                    onReloadDatabase = onReloadDatabase,
                )
            }
            composable<DnsServer> { backstackEntry ->
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
                                onReloadVpn()
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
                        onReloadVpn()
                    },
                    onDelete = if (server.title.isEmpty()) {
                        null
                    } else {
                        { vm.onDeleteDnsServerWarning() }
                    },
                )
            }
            composable<TopLevelDestination.About> {
                AboutScreen(
                    onNavigateUp = { navController.tryPopBackstack(it.id) },
                    onOpenCredits = { navController.navigate(TopLevelDestination.Credits) },
                )
            }
            composable<TopLevelDestination.BlockLog> {
                BlockLogScreen(
                    onNavigateUp = { navController.tryPopBackstack(it.id) },
                    listViewModel = hiltViewModel(),
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
    onReloadVpn: () -> Unit,
    onReloadDatabase: () -> Unit,
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
                    onReloadVpn()
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
            onReloadDatabase()
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
        state = FabState.Inactive,
        isDatabaseRefreshing = false,
        onRefreshHosts = {},
        onLoadDefaults = {},
        onImport = {},
        onExport = {},
        onShareLogcat = {},
        onTryToggleService = {},
        onStartWithoutHostsCheck = {},
        onReloadVpn = {},
        onReloadDatabase = {},
        onUpdateRefreshWork = {},
        onOpenNetworkSettings = {},
    )
}

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    vm: HomeViewModel,
    topLevelNavController: NavHostController,
    state: FabState,
    isDatabaseRefreshing: Boolean,
    onRefreshHosts: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onShareLogcat: () -> Unit,
    onTryToggleService: () -> Unit,
    onReloadVpn: () -> Unit,
    onReloadDatabase: () -> Unit,
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

    if (!vm.hasCompletedEmptyConfigMigration()) {
        topLevelNavController.popNavigate(TopLevelDestination.Presets(canGoBack = false))
    }

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val context = LocalContext.current
    NavigationScaffold(
        modifier = modifier,
        layoutType = if (windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.COMPACT) {
            LayoutType.NavigationBar
        } else {
            LayoutType.NavigationRail
        },
        navigationItems = {
            HomeDestinations.entries.forEach {
                val label = context.getString(it.labelResId)
                item(
                    modifier = Modifier.testTag("homeNavigation:$label"),
                    selected = it == currentDestination,
                    onClick = { setDestination(it) },
                    icon = it.iconEnum.icon,
                    text = label,
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
                            icon = Icons.Default.Bolt,
                            text = context.getString(R.string.presets),
                            onClick = {
                                expanded = false
                                topLevelNavController.navigate(
                                    TopLevelDestination.Presets(canGoBack = true)
                                )
                            }
                        )
                        item(
                            icon = Icons.AutoMirrored.Filled.DriveFileMove,
                            text = context.getString(R.string.add_hosts_file),
                            onClick = {
                                expanded = false
                                topLevelNavController.navigate(HostFile())
                            }
                        )
                        item(
                            icon = Icons.Default.Shield,
                            text = context.getString(R.string.add_host),
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
        val startListState = rememberLazyListState()
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
                var resumeOnStartupToggle by remember {
                    mutableStateOf(vm.configuration.read { autoStart })
                }
                var blockLogToggle by remember {
                    mutableStateOf(vm.configuration.read { blockLogging })
                }

                val showDisableBlockLogWarningDialog by vm.showDisableBlockLogWarningDialog.collectAsState()
                if (showDisableBlockLogWarningDialog) {
                    BasicDialog(
                        title = stringResource(R.string.warning),
                        text = stringResource(R.string.disable_block_log_warning_description),
                        primaryButton = DialogButton(
                            text = stringResource(R.string.disable),
                            onClick = {
                                vm.onClearBlockLog()
                                vm.configuration.edit {
                                    blockLogging = false
                                }
                                blockLogToggle = false
                                onReloadVpn()
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
                    resumeOnStartup = resumeOnStartupToggle,
                    onResumeOnStartupClick = {
                        vm.configuration.edit {
                            autoStart = !autoStart
                            resumeOnStartupToggle = autoStart
                        }
                    },
                    blockLog = blockLogToggle,
                    onToggleBlockLog = {
                        if (blockLogToggle) {
                            vm.onDisableBlockLogWarning()
                        } else {
                            vm.configuration.edit {
                                blockLogging = !blockLogging
                                blockLogToggle = blockLogging
                            }
                            onReloadVpn()
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
                    state = state,
                    onChangeVpnStatusClick = onTryToggleService,
                )
            }
            composable<HomeDestinations.Hosts> {
                var refreshDaily by remember {
                    mutableStateOf(vm.configuration.read { hosts.automaticRefresh })
                }
                HostsScreen(
                    contentPadding = contentPadding + PaddingValues(ListPadding) +
                            PaddingValues(bottom = DefaultFabSize + FabPadding),
                    listState = hostsListState,
                    refreshDaily = refreshDaily,
                    onRefreshDailyClick = {
                        vm.configuration.edit {
                            hosts.automaticRefresh = !hosts.automaticRefresh
                            refreshDaily = hosts.automaticRefresh
                        }
                        onUpdateRefreshWork()
                    },
                    hosts = vm.hosts,
                    onHostClick = { host ->
                        topLevelNavController.navigate(host)
                    },
                    onHostStateChanged = { host ->
                        vm.cycleHost(host)
                        onReloadDatabase()
                    },
                    isRefreshingHosts = isDatabaseRefreshing,
                    onRefreshHosts = onRefreshHosts,
                    onOpenPresets = {
                        topLevelNavController.navigate(
                            TopLevelDestination.Presets(canGoBack = true)
                        )
                    },
                )
            }

            composable<HomeDestinations.Apps> {
                val isRefreshing by vm.appListRefreshing.collectAsState()
                var allowlistDefault by remember {
                    mutableStateOf(vm.configuration.read { appList.defaultMode })
                }
                AppsScreen(
                    contentPadding = contentPadding + PaddingValues(ListPadding),
                    listState = appListState,
                    listViewModel = hiltViewModel(),
                    isRefreshing = isRefreshing,
                    onRefresh = { vm.populateAppList() },
                    bypassSelection = allowlistDefault,
                    onBypassSelection = { selection ->
                        vm.configuration.edit {
                            appList.defaultMode = selection
                        }
                        allowlistDefault = selection
                        onReloadVpn()
                        vm.populateAppList()
                    },
                    apps = vm.appList,
                    onAppClick = { app, enabled ->
                        vm.onToggleApp(app, enabled)
                        onReloadVpn()
                    },
                )
            }
            composable<HomeDestinations.DNS> {
                var customDnsServers by remember {
                    mutableStateOf(vm.configuration.read { dnsServers.enabled })
                }
                var ipV6SupportToggle by remember {
                    mutableStateOf(vm.configuration.read { ipV6Support })
                }
                var useNetworkDnsServers by remember {
                    mutableStateOf(vm.configuration.read { useNetworkDnsServers })
                }
                DnsScreen(
                    contentPadding = contentPadding + PaddingValues(ListPadding) +
                            PaddingValues(bottom = DefaultFabSize + FabPadding),
                    listState = dnsListState,
                    servers = vm.dnsServers,
                    customDnsServers = customDnsServers,
                    onCustomDnsServersClick = {
                        vm.configuration.edit {
                            dnsServers.enabled = !dnsServers.enabled
                            customDnsServers = dnsServers.enabled
                        }
                        onReloadVpn()
                    },
                    ipv6Support = ipV6SupportToggle,
                    onIpv6SupportClick = {
                        vm.configuration.edit {
                            ipV6Support = !ipV6Support
                            ipV6SupportToggle = ipV6Support
                        }
                        onReloadVpn()
                    },
                    useNetworkDnsServers = useNetworkDnsServers,
                    onUseNetworkDnsServersClick = {
                        vm.configuration.edit {
                            this.useNetworkDnsServers = !useNetworkDnsServers
                            useNetworkDnsServers = this.useNetworkDnsServers
                        }
                        onReloadVpn()
                    },
                    onItemClick = { item ->
                        topLevelNavController.navigate(item)
                    },
                    onItemCheckClicked = { item ->
                        vm.toggleDnsServer(item)
                        if (customDnsServers) {
                            onReloadVpn()
                        }
                    },
                )
            }
        }
    }
}
