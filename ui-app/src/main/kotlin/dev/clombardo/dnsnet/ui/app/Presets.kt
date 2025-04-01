/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.clombardo.dnsnet.settings.BlockList
import dev.clombardo.dnsnet.settings.BlockListDefaults
import dev.clombardo.dnsnet.settings.BlockListUrlProvider
import dev.clombardo.dnsnet.settings.BlockListVariantProvider
import dev.clombardo.dnsnet.ui.common.BasicTooltipButton
import dev.clombardo.dnsnet.ui.common.CheckboxListItem
import dev.clombardo.dnsnet.ui.common.ExpandableOptionsItem
import dev.clombardo.dnsnet.ui.common.FloatingTopActions
import dev.clombardo.dnsnet.ui.common.InsetScaffold
import dev.clombardo.dnsnet.ui.common.ListSettingsContainer
import dev.clombardo.dnsnet.ui.common.RadioListItem
import dev.clombardo.dnsnet.ui.common.ScreenTitle
import dev.clombardo.dnsnet.ui.common.navigation.NavigationScaffold
import dev.clombardo.dnsnet.ui.common.plus
import dev.clombardo.dnsnet.ui.common.rememberAtTop
import dev.clombardo.dnsnet.ui.common.rememberMutableStateListOf
import dev.clombardo.dnsnet.ui.common.theme.ListPadding
import dev.clombardo.dnsnet.ui.common.tryOpenUri

@Composable
fun PresetsScreen(
    modifier: Modifier = Modifier,
    canGoBack: Boolean = false,
    onNavigateUp: () -> Unit = {},
    onComplete: (List<BlockList>) -> Unit,
) {
    val state = rememberLazyListState()
    val selectedProviders = rememberMutableStateListOf<Pair<Int, Int>>()
    InsetScaffold(
        modifier = modifier,
        topBar = {
            if (canGoBack) {
                val isAtTop by rememberAtTop(state)
                FloatingTopActions(
                    elevated = !isAtTop,
                    navigationIcon = {
                        BasicTooltipButton(
                            icon = Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up),
                            onClick = onNavigateUp,
                        )
                    }
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            AnimatedVisibility(
                visible = selectedProviders.isNotEmpty(),
                enter = NavigationScaffold.FabEnter,
                exit = NavigationScaffold.FabExit,
            ) {
                FloatingActionButton(
                    onClick = {
                        val urls = selectedProviders.map {
                            val provider = BlockListDefaults.providers[it.first]
                            when (provider) {
                                is BlockListVariantProvider -> {
                                    provider.variants[it.second]
                                }

                                is BlockListUrlProvider -> {
                                    provider
                                }
                            }
                        }
                        if (urls.isNotEmpty()) {
                            onComplete(urls)
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.confirm),
                    )
                }
            }
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.padding(horizontal = ListPadding),
            state = state,
            contentPadding = contentPadding + PaddingValues(bottom = ListPadding + 64.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                if (!canGoBack) {
                    Spacer(Modifier.padding(top = 24.dp))
                }
                ScreenTitle(text = stringResource(R.string.presets))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.presets_description),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )

                    if (!canGoBack) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.secondary
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                )
                                Spacer(Modifier.padding(horizontal = 4.dp))
                                Text(
                                    text = stringResource(R.string.select_an_option_to_continue),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
            }

            BlockListDefaults.providers.forEachIndexed { providerIndex, provider ->
                item {
                    val uriHandler = LocalUriHandler.current
                    val context = LocalContext.current
                    ListSettingsContainer(circleClip = false) {
                        when (provider) {
                            is BlockListVariantProvider -> {
                                item {
                                    var expanded by rememberSaveable { mutableStateOf(false) }
                                    ExpandableOptionsItem(
                                        expanded = expanded,
                                        onExpandClick = { expanded = !expanded },
                                        title = stringResource(provider.titleResId),
                                        details = if (provider.descriptionResId != 0) {
                                            stringResource(provider.descriptionResId)
                                        } else {
                                            ""
                                        }
                                    ) {
                                        val sourceUrl =
                                            stringResource(provider.sourceUrlResId).toUri()
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Button(
                                                colors = ButtonDefaults.textButtonColors(),
                                                onClick = {
                                                    uriHandler.tryOpenUri(
                                                        context,
                                                        sourceUrl
                                                    )
                                                },
                                            ) {
                                                Text(text = stringResource(R.string.view_source))
                                                Spacer(Modifier.padding(horizontal = 4.dp))
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Default.OpenInNew,
                                                    contentDescription = null,
                                                )
                                            }
                                        }

                                        provider.variants.forEachIndexed { variantIndex, variant ->
                                            if (provider.singleSelection) {
                                                RadioListItem(
                                                    title = stringResource(variant.titleResId),
                                                    details = if (variant.descriptionResId != 0) {
                                                        stringResource(variant.descriptionResId)
                                                    } else {
                                                        ""
                                                    },
                                                    clip = true,
                                                    checked = selectedProviders.contains(
                                                        Pair(
                                                            providerIndex,
                                                            variantIndex
                                                        )
                                                    ),
                                                    onCheckedChange = {
                                                        if (it) {
                                                            selectedProviders.removeIf {
                                                                it.first == providerIndex
                                                            }
                                                            selectedProviders.add(
                                                                Pair(
                                                                    providerIndex,
                                                                    variantIndex
                                                                )
                                                            )
                                                        } else {
                                                            selectedProviders.removeIf {
                                                                it.first == providerIndex
                                                            }
                                                        }
                                                    },
                                                )
                                            } else {
                                                CheckboxListItem(
                                                    title = stringResource(variant.titleResId),
                                                    details = if (variant.descriptionResId != 0) {
                                                        stringResource(variant.descriptionResId)
                                                    } else {
                                                        ""
                                                    },
                                                    clip = true,
                                                    checked = selectedProviders.contains(
                                                        Pair(
                                                            providerIndex,
                                                            variantIndex
                                                        )
                                                    ),
                                                    onCheckedChange = {
                                                        if (it) {
                                                            selectedProviders.add(
                                                                Pair(
                                                                    providerIndex,
                                                                    variantIndex
                                                                )
                                                            )
                                                        } else {
                                                            val urlToRemove =
                                                                Pair(providerIndex, variantIndex)
                                                            selectedProviders.removeIf {
                                                                it == urlToRemove
                                                            }
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            is BlockListUrlProvider -> {
                                item {
                                    CheckboxListItem(
                                        title = stringResource(provider.titleResId),
                                        details = if (provider.descriptionResId != 0) {
                                            stringResource(provider.descriptionResId)
                                        } else {
                                            ""
                                        },
                                        clip = true,
                                        checked = selectedProviders.contains(
                                            Pair(providerIndex, 0)
                                        ),
                                        onCheckedChange = {
                                            if (it) {
                                                selectedProviders.add(Pair(providerIndex, 0))
                                            } else {
                                                val urlToRemove = Pair(providerIndex, 0)
                                                selectedProviders.removeIf { it == urlToRemove }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun PresetsScreenPreview() {
    PresetsScreen(
        onComplete = {},
    )
}

@Preview
@Composable
private fun PresetsScreenNoBackPreview() {
    PresetsScreen(
        canGoBack = false,
        onComplete = {},
    )
}
