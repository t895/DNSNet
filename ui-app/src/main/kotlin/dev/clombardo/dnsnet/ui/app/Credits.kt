/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.app

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.net.toUri
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.rememberLibraries
import dev.clombardo.dnsnet.ui.common.BasicTooltipButton
import dev.clombardo.dnsnet.ui.common.ContentSetting
import dev.clombardo.dnsnet.ui.common.ExpandableOptionsItem
import dev.clombardo.dnsnet.ui.common.FloatingTopActions
import dev.clombardo.dnsnet.ui.common.InsetScaffold
import dev.clombardo.dnsnet.ui.common.ScreenTitle
import dev.clombardo.dnsnet.ui.common.plus
import dev.clombardo.dnsnet.ui.common.rememberAtTop
import dev.clombardo.dnsnet.ui.common.clickable
import dev.clombardo.dnsnet.ui.common.theme.ListPadding
import dev.clombardo.dnsnet.ui.common.tryOpenUri

@Composable
fun LicenseListItem(
    modifier: Modifier = Modifier,
    title: String,
    details: String = "",
    licenseLink: String,
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    ContentSetting(
        modifier = modifier.clickable(
            enabled = true,
            clip = true,
            interactionSource = remember { MutableInteractionSource() },
            role = Role.Button,
            onClick = { uriHandler.tryOpenUri(context, licenseLink.toUri()) },
        ),
        title = title,
        details = details,
        maxTitleLines = 2,
        maxDetailLines = 2,
        endContent = {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = stringResource(R.string.open_license),
            )
        }
    )
}

@Composable
fun CreditListItem(
    library: Library,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExpandableOptionsItem(
        modifier = modifier,
        expanded = expanded,
        clip = true,
        onExpandClick = { expanded = !expanded },
        title = library.name,
        details = library.artifactId,
    ) {
        library.licenses.forEach {
            LicenseListItem(
                title = it.name,
                licenseLink = it.url ?: "",
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsScreen(
    modifier: Modifier = Modifier,
    onNavigateUp: () -> Unit,
) {
    val resources = LocalContext.current.resources
    val libs by rememberLibraries {
        resources.openRawResource(R.raw.aboutlibraries).use {
            it.bufferedReader().readText()
        }
    }
    val librariesList by remember {
        derivedStateOf {
            if (libs == null) {
                emptyList()
            } else {
                libs!!.libraries.distinctBy { it.artifactId }.distinctBy { it.name }
            }
        }
    }

    val state = rememberLazyListState()
    InsetScaffold(
        modifier = modifier,
        topBar = {
            val isAtTop by rememberAtTop(state)
            FloatingTopActions(
                elevated = !isAtTop,
                navigationIcon = {
                    BasicTooltipButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.navigate_up),
                        onClick = onNavigateUp,
                    )
                },
            )
        }
    ) { contentPadding ->
        LazyColumn(
            state = state,
            contentPadding = contentPadding + PaddingValues(horizontal = ListPadding),
        ) {
            item {
                ScreenTitle(text = stringResource(R.string.credits))
            }
            item {
                LicenseListItem(
                    title = stringResource(R.string.dns66_credit),
                    licenseLink = stringResource(R.string.dns66_license_link),
                )
            }

            item {
                LicenseListItem(
                    title = stringResource(R.string.adbuster_credit),
                    licenseLink = stringResource(R.string.adbuster_license_link),
                )
            }

            item {
                LicenseListItem(
                    title = stringResource(R.string.etherparse),
                    licenseLink = stringResource(R.string.etherparse_license_link),
                )
            }

            item {
                LicenseListItem(
                    title = stringResource(R.string.libc),
                    licenseLink = stringResource(R.string.libc_license_link),
                )
            }

            item {
                LicenseListItem(
                    title = stringResource(R.string.log),
                    licenseLink = stringResource(R.string.log_license_link),
                )
            }

            item {
                LicenseListItem(
                    title = stringResource(R.string.mio),
                    licenseLink = stringResource(R.string.mio_license_link),
                )
            }

            item {
                LicenseListItem(
                    title = stringResource(R.string.simple_dns),
                    licenseLink = stringResource(R.string.simple_dns_license_link),
                )
            }

            item {
                LicenseListItem(
                    title = stringResource(R.string.thiserror),
                    licenseLink = stringResource(R.string.thiserror_license_link),
                )
            }

            item {
                LicenseListItem(
                    title = stringResource(R.string.uniffi),
                    licenseLink = stringResource(R.string.uniffi_license_link),
                )
            }

            item {
                LicenseListItem(
                    title = stringResource(R.string.android_logger),
                    licenseLink = stringResource(R.string.android_logger_license_link),
                )
            }

            item {
                LicenseListItem(
                    title = stringResource(R.string.quiche),
                    licenseLink = stringResource(R.string.quiche_license_link),
                )
            }

            item {
                LicenseListItem(
                    title = stringResource(R.string.base64),
                    licenseLink = stringResource(R.string.base64_license_link),
                )
            }

            item {
                LicenseListItem(
                    title = stringResource(R.string.getrandom),
                    licenseLink = stringResource(R.string.getrandom_license_link),
                )
            }

            if (libs != null) {
                items(librariesList) {
                    CreditListItem(it)
                }
            }
        }
    }
}
