/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

@file:OptIn(ExperimentalSharedTransitionApi::class)

package dev.clombardo.dnsnet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.window.core.layout.WindowWidthSizeClass
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import dev.clombardo.dnsnet.R
import dev.clombardo.dnsnet.tryOpenUri
import dev.clombardo.dnsnet.ui.theme.DnsNetTheme

object Setup {
    const val KEY_ICON = "icon"
    const val KEY_TITLE = "title"
    const val KEY_BACKGROUND = "background"

    val padding
        @Composable
        get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)
}

@Composable
fun GreetingScreen(
    modifier: Modifier = Modifier,
    onGetStartedClick: () -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    sharedTransitionScope: SharedTransitionScope,
) {
    with(sharedTransitionScope) {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surface)
                .systemBarsPadding()
                .fillMaxSize()
                .sharedElement(
                    state = rememberSharedContentState(key = Setup.KEY_BACKGROUND),
                    animatedVisibilityScope = animatedVisibilityScope,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val sizeClass = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass
            if (sizeClass == WindowWidthSizeClass.COMPACT) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        modifier = Modifier
                            .sharedElement(
                                state = rememberSharedContentState(key = Setup.KEY_ICON),
                                animatedVisibilityScope = animatedVisibilityScope,
                            ),
                        painter = painterResource(R.drawable.icon_full),
                        contentDescription = stringResource(R.string.app_name),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = stringResource(R.string.welcome_to),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        modifier = Modifier
                            .sharedBounds(
                                sharedContentState = rememberSharedContentState(key = Setup.KEY_TITLE),
                                animatedVisibilityScope = animatedVisibilityScope,
                            ),
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.padding(vertical = 48.dp))
                    Button(onClick = onGetStartedClick) {
                        Text(
                            text = stringResource(R.string.get_started),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            modifier = Modifier
                                .size(160.dp)
                                .sharedElement(
                                    state = rememberSharedContentState(key = Setup.KEY_ICON),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                ),
                            painter = painterResource(R.drawable.icon_full),
                            contentDescription = stringResource(R.string.app_name),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.welcome_to),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            modifier = Modifier
                                .sharedBounds(
                                    sharedContentState = rememberSharedContentState(key = Setup.KEY_TITLE),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                ),
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.padding(horizontal = 48.dp))
                    Button(onClick = onGetStartedClick) {
                        Text(
                            text = stringResource(R.string.get_started),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun GreetingScreenPreview() {
    DnsNetTheme {
        SharedTransitionLayout {
            AnimatedContent(
                targetState = true,
            ) {
                it
                GreetingScreen(
                    onGetStartedClick = {},
                    animatedVisibilityScope = this@AnimatedContent,
                    sharedTransitionScope = this@SharedTransitionLayout,
                )
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun NoticeScreen(
    modifier: Modifier = Modifier,
    onContinueClick: () -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    sharedTransitionScope: SharedTransitionScope,
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .fillMaxSize(),
    ) {
        var vpnServiceAcknowledged by rememberSaveable { mutableStateOf(false) }
        var applicationAcknowledged by rememberSaveable { mutableStateOf(false) }
        LazyColumn(
            contentPadding = Setup.padding.asPaddingValues() + PaddingValues(top = 80.dp),
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item {
                    val context = LocalContext.current
                    var permissionRequestComplete by rememberSaveable {
                        mutableStateOf(
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                        )
                    }
                    var permissionRequestEnabled by rememberSaveable {
                        mutableStateOf(true)
                    }
                    val notificationPermissionState =
                        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS) {
                            if (it) {
                                permissionRequestComplete = true
                            } else {
                                permissionRequestEnabled = false
                            }
                        }
                    InformationListItem(
                        icon = Icons.Default.Notifications,
                        title = stringResource(R.string.notifications),
                        text = stringResource(R.string.notification_permission_description),
                        enabled = permissionRequestEnabled,
                        complete = permissionRequestComplete,
                        buttonText = stringResource(R.string.grant),
                        onButtonClick = { notificationPermissionState.launchPermissionRequest() },
                    )
                }
            }
            item {
                InformationListItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.notice),
                    text = stringResource(R.string.notice_vpn_service),
                    buttonText = stringResource(R.string.acknowledge),
                    complete = vpnServiceAcknowledged,
                    onButtonClick = { vpnServiceAcknowledged = true },
                    learnMoreLink = stringResource(R.string.notice_vpn_service_learn_more_link),
                )
            }
            item {
                InformationListItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.notice),
                    text = stringResource(R.string.notice_application),
                    buttonText = stringResource(R.string.acknowledge),
                    complete = applicationAcknowledged,
                    onButtonClick = { applicationAcknowledged = true },
                    learnMoreLink = stringResource(R.string.notice_application_learn_more_link),
                )
            }
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Button(
                        enabled = vpnServiceAcknowledged && applicationAcknowledged,
                        onClick = onContinueClick,
                    ) {
                        Text(text = stringResource(R.string.button_continue))
                    }
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .padding(Setup.padding.asPaddingValues())
            .fillMaxSize(),
        contentAlignment = Alignment.TopStart,
    ) {
        with(sharedTransitionScope) {
            OutlinedCard(
                modifier = Modifier
                    .padding(16.dp)
                    .sharedElement(
                        state = rememberSharedContentState(key = Setup.KEY_BACKGROUND),
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
            ) {
                Row(
                    modifier = Modifier.padding(
                        start = 4.dp,
                        end = 12.dp,
                        top = 4.dp,
                        bottom = 4.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        modifier = Modifier
                            .size(48.dp)
                            .sharedElement(
                                state = rememberSharedContentState(key = Setup.KEY_ICON),
                                animatedVisibilityScope = animatedVisibilityScope,
                            ),
                        painter = painterResource(R.drawable.icon_full),
                        contentDescription = stringResource(R.string.app_name),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.padding(horizontal = 1.dp))
                    Text(
                        modifier = Modifier
                            .sharedBounds(
                                sharedContentState = rememberSharedContentState(key = Setup.KEY_TITLE),
                                animatedVisibilityScope = animatedVisibilityScope,
                            ),
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun NoticeScreenPreview() {
    DnsNetTheme {
        SharedTransitionLayout {
            AnimatedContent(targetState = true) {
                it
                NoticeScreen(
                    onContinueClick = {},
                    animatedVisibilityScope = this@AnimatedContent,
                    sharedTransitionScope = this@SharedTransitionLayout,
                )
            }
        }
    }
}

@Composable
fun InformationListItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    text: String,
    enabled: Boolean = true,
    complete: Boolean,
    buttonText: String,
    onButtonClick: () -> Unit,
    learnMoreLink: String = "",
) {
    Column(
        modifier = modifier.padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                modifier = Modifier.size(32.dp),
                painter = rememberVectorPainter(icon),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = title,
            )
            Spacer(modifier = Modifier.padding(horizontal = 2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 16.sp,
            )
        }
        Text(
            modifier = Modifier
                .padding(start = 36.dp)
                .padding(vertical = 8.dp),
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopEnd,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (learnMoreLink.isNotEmpty()) {
                    val uriHandler = LocalUriHandler.current
                    val context = LocalContext.current
                    TextButton(onClick = {
                        uriHandler.tryOpenUri(
                            context,
                            learnMoreLink.toUri()
                        )
                    }) {
                        Text(text = stringResource(R.string.learn_more))
                    }
                }
                Box(contentAlignment = Alignment.Center) {
                    val buttonAlpha by animateFloatAsState(if (complete) 0f else 1f)
                    Text(
                        modifier = Modifier.graphicsLayer { alpha = 1f - buttonAlpha },
                        text = stringResource(R.string.complete),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(
                        modifier = Modifier
                            .sizeIn(minWidth = 128.dp, maxWidth = 192.dp)
                            .graphicsLayer { alpha = buttonAlpha },
                        onClick = onButtonClick,
                        enabled = enabled,
                    ) {
                        Text(text = buttonText)
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun InformationListItemPreview() {
    DnsNetTheme {
        var complete by remember { mutableStateOf(false) }
        InformationListItem(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            icon = Icons.Default.Info,
            title = stringResource(R.string.notice),
            text = stringResource(R.string.notification_permission_description),
            complete = complete,
            buttonText = stringResource(R.string.grant),
            onButtonClick = { complete = true },
            learnMoreLink = "link"
        )
    }
}
