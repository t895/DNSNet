/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.clombardo.dnsnet.R
import dev.clombardo.dnsnet.ui.plus
import dev.clombardo.dnsnet.ui.theme.Animation
import dev.clombardo.dnsnet.ui.theme.DnsNetTheme

enum class LayoutType {
    NavigationBar,
    NavigationRail,
}

object NavigationScaffoldDefaults {
    val windowInsets: WindowInsets
        @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)
}

object NavigationScaffold {
    val FabEnter by lazy { scaleIn(animationSpec = tween(easing = Animation.EmphasizedDecelerateEasing)) }
    val FabExit by lazy { scaleOut(animationSpec = tween(easing = Animation.EmphasizedAccelerateEasing)) }
}

@Composable
fun NavigationScaffold(
    modifier: Modifier = Modifier,
    layoutType: LayoutType,
    windowInsets: WindowInsets = NavigationScaffoldDefaults.windowInsets,
    navigationItems: NavigationScope.() -> Unit,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (contentPadding: PaddingValues) -> Unit,
) {
    val paddingValues = windowInsets.asPaddingValues()
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(modifier = modifier) {
            when (layoutType) {
                LayoutType.NavigationBar -> {
                    val navigationBarPadding =
                        paddingValues + PaddingValues(bottom = NavigationBar.height)
                    Box(modifier = Modifier.fillMaxSize()) {
                        content(navigationBarPadding)
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        NavigationBar(
                            windowInsets = windowInsets,
                            content = navigationItems,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(navigationBarPadding),
                            contentAlignment = Alignment.BottomEnd,
                        ) {
                            floatingActionButton()
                        }
                    }
                }

                LayoutType.NavigationRail -> {
                    val navigationBarPadding =
                        paddingValues + PaddingValues(start = NavigationRail.width)
                    Box(modifier = Modifier.fillMaxSize()) {
                        content(navigationBarPadding)
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        NavigationRail(
                            windowInsets = windowInsets,
                            content = navigationItems,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(navigationBarPadding),
                            contentAlignment = Alignment.BottomEnd,
                        ) {
                            floatingActionButton()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationScaffoldPreview(layoutType: LayoutType) {
    DnsNetTheme {
        var selectedIndex by remember { mutableIntStateOf(0) }
        NavigationScaffold(
            modifier = Modifier.background(color = MaterialTheme.colorScheme.surface),
            layoutType = layoutType,
            navigationItems = {
                item(
                    selected = selectedIndex == 0,
                    icon = Icons.Default.VpnKey,
                    text = "Start",
                    onClick = { selectedIndex = 0 },
                )
                item(
                    selected = selectedIndex == 1,
                    icon = Icons.Default.Dns,
                    text = "DNS",
                    onClick = { selectedIndex = 1 },
                )
                item(
                    selected = selectedIndex == 2,
                    icon = Icons.Default.Android,
                    text = "Apps",
                    onClick = { selectedIndex = 2 },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.add),
                    )
                }
            },
        ) { contentPadding ->
            Text(
                modifier = Modifier.padding(contentPadding),
                text = "some screen",
            )
        }
    }
}

@Preview
@Composable
private fun NavigationScaffoldBarPreview() {
    NavigationScaffoldPreview(LayoutType.NavigationBar)
}

@Preview
@Composable
private fun NavigationScaffoldRailPreview() {
    NavigationScaffoldPreview(LayoutType.NavigationRail)
}
