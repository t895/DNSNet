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
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Filter1
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.clombardo.dnsnet.settings.DnsServer
import dev.clombardo.dnsnet.settings.DnsServerType
import dev.clombardo.dnsnet.settings.Filter
import dev.clombardo.dnsnet.settings.FilterFile
import dev.clombardo.dnsnet.settings.FilterState
import dev.clombardo.dnsnet.settings.SingleFilter
import dev.clombardo.dnsnet.ui.app.viewmodel.HomeViewModel
import dev.clombardo.dnsnet.ui.common.BasicDialog
import dev.clombardo.dnsnet.ui.common.DialogButton
import dev.clombardo.dnsnet.ui.common.FabState
import dev.clombardo.dnsnet.ui.common.isSmallScreen
import dev.clombardo.dnsnet.ui.common.navigation.LayoutType
import dev.clombardo.dnsnet.ui.common.navigation.NavigationScaffold
import dev.clombardo.dnsnet.ui.common.plus
import dev.clombardo.dnsnet.ui.common.theme.DefaultFabSize
import dev.clombardo.dnsnet.ui.common.theme.FabPadding
import dev.clombardo.dnsnet.ui.common.theme.ListPadding
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
enum class HomeDestinationIcon(val icon: ImageVector) {
    Start(Icons.Default.VpnKey),
    Filters(Icons.Default.FilterAlt),
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
    val entries = listOf(Start, Filters, Apps, DNS)

    @Parcelize
    @Serializable
    data object Start : HomeDestination(HomeDestinationIcon.Start, R.string.start_tab)

    @Parcelize
    @Serializable
    data object Filters : HomeDestination(HomeDestinationIcon.Filters, R.string.filters_tab)

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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object Home {
    val NavigationEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
        @Composable get() {
            val defaultEffectSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            return { fadeIn(animationSpec = defaultEffectSpec) }
        }
    val NavigationExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition
        @Composable get() {
            val fastEffectSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            return { fadeOut(animationSpec = fastEffectSpec) }
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

    const val TEST_TAG_IGNORE_MISSING_FILTERS_BUTTON = "ignore_missing_filters_button"
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalComposeUiApi::class)
@SuppressLint("RestrictedApi")
@Composable
fun App(
    modifier: Modifier = Modifier,
    vm: HomeViewModel = viewModel(),
    state: FabState,
    isDatabaseRefreshing: Boolean,
    onRefreshFilters: () -> Unit,
    onSetupComplete: suspend () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onShareLogcat: () -> Unit,
    onTryToggleService: () -> Unit,
    onStartWithoutFiltersCheck: () -> Unit,
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

    val showFilterFilesNotFoundDialog by vm.showFilterFilesNotFoundDialog.collectAsState()
    if (showFilterFilesNotFoundDialog) {
        BasicDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = stringResource(R.string.missing_filter_files_title),
            text = stringResource(R.string.missing_filter_files_message),
            primaryButton = DialogButton(
                modifier = Modifier.testTag(Home.TEST_TAG_IGNORE_MISSING_FILTERS_BUTTON),
                text = stringResource(R.string.button_yes),
                onClick = {
                    onStartWithoutFiltersCheck()
                    vm.onDismissFilterFilesNotFound()
                },
            ),
            secondaryButton = DialogButton(
                text = stringResource(R.string.button_no),
                onClick = { vm.onDismissFilterFilesNotFound() },
            ),
            onDismissRequest = { vm.onDismissFilterFilesNotFound() },
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
        val loadDefaultsCoroutineScope = rememberCoroutineScope()
        BasicDialog(
            title = stringResource(R.string.warning),
            text = stringResource(R.string.reset_settings_warning_description),
            primaryButton = DialogButton(
                text = stringResource(R.string.reset),
                onClick = {
                    loadDefaultsCoroutineScope.launch {
                        vm.settings.loadDefaultUserConfiguration {
                            vm.onDismissResetSettingsDialog()
                            onReloadVpn()
                        }
                    }
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
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest),
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
                val updateCoroutineScope = rememberCoroutineScope()
                PresetsScreen(
                    canGoBack = route.canGoBack,
                    onNavigateUp = { navController.tryPopBackstack(backstackEntry.id) },
                    onComplete = {
                        vm.addBlockLists(it)
                        onRefreshFilters()
                        if (route.canGoBack) {
                            navController.tryPopBackstack(backstackEntry.id)
                        } else {
                            vm.preferences.SetupComplete = true
                            navController.popNavigate(TopLevelDestination.Home)
                            updateCoroutineScope.launch {
                                onSetupComplete()
                            }
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
                        onRefreshFilters = onRefreshFilters,
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
            composable<FilterFile> { backstackEntry ->
                val filter = backstackEntry.toRoute<FilterFile>()
                EditFilterDestination(
                    filter = filter,
                    vm = vm,
                    onPopBackStack = { navController.tryPopBackstack(backstackEntry.id) },
                    onReloadVpn = onReloadVpn,
                    onReloadDatabase = onReloadDatabase,
                )
            }
            composable<SingleFilter> { backstackEntry ->
                val filter = backstackEntry.toRoute<SingleFilter>()
                EditFilterDestination(
                    filter = filter,
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
                            SingleFilter(
                                title = "",
                                data = it.hostname,
                                state = if (it.allowed) {
                                    FilterState.DENY
                                } else {
                                    FilterState.ALLOW
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
fun EditFilterDestination(
    filter: Filter,
    vm: HomeViewModel,
    onPopBackStack: () -> Unit,
    onReloadVpn: () -> Unit,
    onReloadDatabase: () -> Unit,
) {
    val showDeleteFilterWarningDialog by vm.showDeleteFilterWarningDialog.collectAsState()
    if (showDeleteFilterWarningDialog) {
        BasicDialog(
            title = stringResource(R.string.warning),
            text = stringResource(
                R.string.permanently_delete_warning_description,
                filter.title,
            ),
            primaryButton = DialogButton(
                text = stringResource(R.string.action_delete),
                onClick = {
                    vm.removeFilter(filter)
                    vm.onDismissDeleteFilterWarning()
                    onPopBackStack()
                    onReloadVpn()
                },
            ),
            secondaryButton = DialogButton(
                text = stringResource(android.R.string.cancel),
                onClick = { vm.onDismissDeleteFilterWarning() },
            ),
            onDismissRequest = { vm.onDismissDeleteFilterWarning() },
        )
    }

    EditFilterScreen(
        filter = filter,
        onNavigateUp = onPopBackStack,
        onSave = { filterToSave ->
            if (filter.title.isEmpty()) {
                vm.addFilter(filterToSave)
                if (filter is SingleFilter) {
                    vm.removeBlockLogEntry(filter.data)
                }
            } else {
                vm.replaceFilter(filter, filterToSave)
            }
            onPopBackStack()
            onReloadDatabase()
        },
        onDelete = if (filter.title.isEmpty()) {
            null
        } else {
            { vm.onDeleteFilterWarning() }
        },
        onUriPermissionAcquireFailed = if (filter is FilterFile) {
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
        onRefreshFilters = {},
        onSetupComplete = {},
        onImport = {},
        onExport = {},
        onShareLogcat = {},
        onTryToggleService = {},
        onStartWithoutFiltersCheck = {},
        onReloadVpn = {},
        onReloadDatabase = {},
        onUpdateRefreshWork = {},
        onOpenNetworkSettings = {},
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    vm: HomeViewModel,
    topLevelNavController: NavHostController,
    state: FabState,
    isDatabaseRefreshing: Boolean,
    onRefreshFilters: () -> Unit,
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

    val context = LocalContext.current
    NavigationScaffold(
        modifier = modifier,
        layoutType = if (isSmallScreen()) {
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
                visible = currentDestination == HomeDestinations.Filters ||
                        currentDestination == HomeDestinations.DNS,
                enter = NavigationScaffold.FabEnter,
                exit = NavigationScaffold.FabExit,
            ) {
                var expanded by rememberSaveable { mutableStateOf(false) }
                if (currentDestination == HomeDestinations.DNS) {
                    expanded = false
                }
                FloatingActionButtonMenu(
                    expanded = expanded,
                    button = {
                        ToggleFloatingActionButton(
                            checked = expanded,
                            onCheckedChange = {
                                if (currentDestination == HomeDestinations.Filters) {
                                    expanded = it
                                } else if (currentDestination == HomeDestinations.DNS) {
                                    when (vm.settings.dnsServers.type.get()) {
                                        DnsServerType.Standard -> topLevelNavController.navigate(
                                            DnsServer()
                                        )

                                        DnsServerType.DoH3 -> {
                                            topLevelNavController.navigate(DnsServer(type = DnsServerType.DoH3))
                                        }
                                    }
                                }
                            },
                            contentAlignment = Alignment.BottomEnd,
                        ) {
                            val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
                            val onPrimary = MaterialTheme.colorScheme.onPrimary
                            val animatedColor = remember(checkedProgress) {
                                lerp(onPrimaryContainer, onPrimary, checkedProgress)
                            }
                            val animatedRotation by animateFloatAsState(
                                targetValue = if (currentDestination == HomeDestinations.DNS || !expanded) {
                                    0f
                                } else {
                                    135f
                                },
                                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                            )
                            Icon(
                                modifier = Modifier
                                    .graphicsLayer {
                                        rotationZ = animatedRotation
                                    },
                                painter = rememberVectorPainter(Icons.Default.Add),
                                contentDescription = stringResource(R.string.add),
                                tint = animatedColor,
                            )
                        }
                    }
                ) {
                    FloatingActionButtonMenuItem(
                        text = {
                            Text(stringResource(R.string.add_filter))
                        },
                        icon = {
                            Icon(
                                painter = rememberVectorPainter(Icons.Default.Filter1),
                                contentDescription = null,
                            )
                        },
                        onClick = { topLevelNavController.navigate(SingleFilter()) }
                    )
                    FloatingActionButtonMenuItem(
                        text = {
                            Text(stringResource(R.string.add_filter_file))
                        },
                        icon = {
                            Icon(
                                painter = rememberVectorPainter(Icons.AutoMirrored.Default.InsertDriveFile),
                                contentDescription = null,
                            )
                        },
                        onClick = { topLevelNavController.navigate(FilterFile()) }
                    )
                    FloatingActionButtonMenuItem(
                        text = {
                            Text(stringResource(R.string.presets))
                        },
                        icon = {
                            Icon(
                                painter = rememberVectorPainter(Icons.Default.Bolt),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            topLevelNavController.navigate(
                                TopLevelDestination.Presets(canGoBack = true)
                            )
                        }
                    )
                }
            }
        },
    ) { contentPadding ->
        // List state must be hoisted outside of the NavHost or it will be lost on recomposition
        val startListState = rememberLazyListState()
        val filterListState = rememberLazyListState()
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
                val resumeOnStartup by vm.settings.autoStart.collectAsState()
                val blockLog by vm.settings.blockLogging.collectAsState()

                val showDisableBlockLogWarningDialog by vm.showDisableBlockLogWarningDialog.collectAsState()
                if (showDisableBlockLogWarningDialog) {
                    BasicDialog(
                        title = stringResource(R.string.warning),
                        text = stringResource(R.string.disable_block_log_warning_description),
                        primaryButton = DialogButton(
                            text = stringResource(R.string.disable),
                            onClick = {
                                vm.onDisableBlockLog()
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
                    contentPadding = contentPadding,
                    listState = startListState,
                    resumeOnStartup = resumeOnStartup,
                    onResumeOnStartupClick = {
                        vm.settings.autoStart.set(!resumeOnStartup)
                    },
                    blockLog = blockLog,
                    onToggleBlockLog = {
                        if (blockLog) {
                            vm.onDisableBlockLogWarning()
                        } else {
                            vm.settings.blockLogging.set(true)
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
            composable<HomeDestinations.Filters> {
                val refreshDaily by vm.settings.filters.automaticRefresh.collectAsState()
                val filterFiles = vm.settings.filters.files.asList()
                val singleFilters = vm.settings.filters.singles.asList()
                FiltersScreen(
                    contentPadding = contentPadding,
                    listState = filterListState,
                    refreshDaily = refreshDaily,
                    onRefreshDailyClick = {
                        vm.settings.filters.automaticRefresh.set(!refreshDaily)
                        onUpdateRefreshWork()
                    },
                    filterFiles = filterFiles,
                    singleFilters = singleFilters,
                    onFilterClick = { filter ->
                        topLevelNavController.navigate(filter)
                    },
                    onFilterStateChanged = { filter ->
                        vm.cycleFilter(filter)
                        onReloadDatabase()
                    },
                    isRefreshingFilters = isDatabaseRefreshing,
                    onRefreshFilters = onRefreshFilters,
                    onOpenPresets = {
                        topLevelNavController.navigate(
                            TopLevelDestination.Presets(canGoBack = true)
                        )
                    },
                )
            }

            composable<HomeDestinations.Apps> {
                val isRefreshing by vm.appListRefreshing.collectAsState()
                val allowlistDefault by vm.settings.appList.defaultMode.collectAsState()
                val appList by vm.appList.collectAsState()
                AppsScreen(
                    contentPadding = contentPadding + PaddingValues(ListPadding),
                    listState = appListState,
                    listViewModel = hiltViewModel(),
                    isRefreshing = isRefreshing,
                    onRefresh = { vm.populateAppList() },
                    bypassSelection = allowlistDefault,
                    onBypassSelection = { selection ->
                        vm.settings.appList.defaultMode.set(selection)
                        onReloadVpn()
                        vm.populateAppList()
                    },
                    apps = appList,
                    onAppClick = { app, enabled ->
                        vm.onToggleApp(app, enabled)
                        onReloadVpn()
                    },
                )
            }

            composable<HomeDestinations.DNS> {
                val customDnsServers by vm.settings.dnsServers.enabled.collectAsState()
                val dnsServers = vm.settings.dnsServers.items.asList()
                val type by vm.settings.dnsServers.type.collectAsState()
                val useNetworkDnsServers by vm.settings.useNetworkDnsServers.collectAsState()
                DnsScreen(
                    contentPadding = contentPadding + PaddingValues(ListPadding) +
                            PaddingValues(bottom = DefaultFabSize + FabPadding),
                    listState = dnsListState,
                    servers = dnsServers,
                    customDnsServers = customDnsServers,
                    onCustomDnsServersClick = {
                        vm.settings.dnsServers.enabled.set(!customDnsServers)
                        onReloadVpn()
                    },
                    useNetworkDnsServers = useNetworkDnsServers,
                    onUseNetworkDnsServersClick = {
                        vm.settings.useNetworkDnsServers.set(!useNetworkDnsServers)
                        onReloadVpn()
                    },
                    doh3Support = type == DnsServerType.DoH3,
                    onDoh3SupportClick = {
                        vm.settings.dnsServers.type.set(
                            when (type) {
                                DnsServerType.Standard -> DnsServerType.DoH3
                                DnsServerType.DoH3 -> DnsServerType.Standard
                            }
                        )
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
