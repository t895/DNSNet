/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.net.toUri
import dev.clombardo.dnsnet.R
import dev.clombardo.dnsnet.tryOpenUri
import dev.clombardo.dnsnet.ui.theme.ListPadding
import io.github.usefulness.licensee.Artifact
import io.github.usefulness.licensee.LicenseeForAndroid

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
        modifier = modifier.roundedClickable(
            enabled = true,
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
    credit: Artifact,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExpandableOptionsItem(
        modifier = modifier,
        expanded = expanded,
        onExpandClick = { expanded = !expanded },
        title = credit.name ?: "",
        details = credit.groupId,
    ) {
        credit.spdxLicenses.forEach {
            LicenseListItem(
                title = it.name,
                details = it.identifier,
                licenseLink = it.url,
            )
        }
        credit.unknownLicenses.forEach {
            LicenseListItem(
                title = it.name ?: "",
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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    InsetScaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                windowInsets = topAppBarInsets,
                title = {
                    Text(text = stringResource(R.string.credits))
                },
                navigationIcon = {
                    BasicTooltipIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.navigate_up),
                        onClick = onNavigateUp,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { contentPadding ->
        val artifacts = remember {
            LicenseeForAndroid.artifacts
                .distinctBy { it.groupId }
                .sortedBy { it.name }
        }
        LazyColumn(contentPadding = contentPadding + PaddingValues(horizontal = ListPadding)) {
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
                    title = stringResource(R.string.polling),
                    licenseLink = stringResource(R.string.polling_license_link),
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
                    title = stringResource(R.string.socket2),
                    licenseLink = stringResource(R.string.socket2_license_link),
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

            items(artifacts) {
                CreditListItem(it)
            }
        }
    }
}
