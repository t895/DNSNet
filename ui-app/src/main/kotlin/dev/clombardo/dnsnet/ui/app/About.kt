/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.clombardo.dnsnet.ui.common.BasicTooltipButton
import dev.clombardo.dnsnet.ui.common.FloatingTopActions
import dev.clombardo.dnsnet.ui.common.InsetScaffold
import dev.clombardo.dnsnet.ui.common.TooltipIconButton
import dev.clombardo.dnsnet.ui.common.rememberAtTop
import dev.clombardo.dnsnet.ui.common.theme.DnsNetTheme
import dev.clombardo.dnsnet.ui.common.theme.ListPadding
import dev.clombardo.dnsnet.ui.common.tryOpenUri

@Composable
fun AboutText(text: String = "") {
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = text,
        textAlign = TextAlign.Center,
    )
}

@Composable
fun About(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    onOpenCredits: () -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Image(
                painter = painterResource(id = R.drawable.icon_full),
                contentDescription = stringResource(R.string.app_name),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            )
        }
        item {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors()
                    .copy(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(ListPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AboutText(text = stringResource(id = R.string.app_shortdesc))
                    AboutText(
                        text = stringResource(
                            id = R.string.app_version_info,
                            BuildConfig.VERSION_NAME,
                        )
                    )
                    AboutText(text = stringResource(id = R.string.info_app_copyright))
                    AboutText(text = stringResource(id = R.string.dns66_credit))
                    AboutText(text = stringResource(id = R.string.info_app_license))

                    val uriHandler = LocalUriHandler.current
                    val context = LocalContext.current
                    val websiteUri = stringResource(id = R.string.website).toUri()
                    val faqUri = stringResource(id = R.string.faq_link).toUri()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TooltipIconButton(
                            colors = IconButtonDefaults.filledIconButtonColors(),
                            painter = rememberVectorPainter(Icons.Default.Code),
                            contentDescription = stringResource(R.string.view_source_code),
                            onClick = { uriHandler.tryOpenUri(context, websiteUri) },
                        )
                        TooltipIconButton(
                            colors = IconButtonDefaults.filledIconButtonColors(),
                            painter = rememberVectorPainter(Icons.AutoMirrored.Filled.Help),
                            contentDescription = stringResource(R.string.help),
                            onClick = { uriHandler.tryOpenUri(context, faqUri) },
                        )
                        TooltipIconButton(
                            colors = IconButtonDefaults.filledIconButtonColors(),
                            painter = rememberVectorPainter(Icons.Default.Description),
                            contentDescription = stringResource(R.string.credits),
                            onClick = onOpenCredits,
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun AboutPreview() {
    DnsNetTheme {
        About(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            onOpenCredits = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    modifier: Modifier = Modifier,
    onNavigateUp: () -> Unit,
    onOpenCredits: () -> Unit,
) {
    val listState = rememberLazyListState()
    InsetScaffold(
        modifier = modifier,
        topBar = {
            val isAtTop by rememberAtTop(listState)
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
        },
    ) { contentPadding ->
        About(
            modifier = Modifier.padding(horizontal = 16.dp),
            state = listState,
            contentPadding = contentPadding,
            onOpenCredits = onOpenCredits,
        )
    }
}

@Preview
@Composable
private fun AboutScreenPreview() {
    DnsNetTheme {
        AboutScreen(
            onNavigateUp = {},
            onOpenCredits = {},
        )
    }
}
