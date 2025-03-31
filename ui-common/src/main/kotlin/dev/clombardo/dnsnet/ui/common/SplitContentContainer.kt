/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.dnsnet.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collection.MutableVector
import androidx.compose.runtime.collection.mutableVectorOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.clombardo.dnsnet.ui.common.theme.DnsNetTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object SplitContentContainer {
    private val innerCorner = 4.dp
    private val outerCorner = 16.dp

    private val fullRoundCorner = 40.dp

    val fullRoundCornerShape = AbsoluteRoundedCornerShape(
        topLeft = fullRoundCorner,
        topRight = fullRoundCorner,
        bottomLeft = fullRoundCorner,
        bottomRight = fullRoundCorner,
    )

    val startRowContainerShape = AbsoluteRoundedCornerShape(
        topLeft = outerCorner,
        topRight = innerCorner,
        bottomLeft = outerCorner,
        bottomRight = innerCorner,
    )

    val middleRowContainerShape = AbsoluteRoundedCornerShape(
        topLeft = innerCorner,
        topRight = innerCorner,
        bottomLeft = innerCorner,
        bottomRight = innerCorner,
    )

    val endRowContainerShape = AbsoluteRoundedCornerShape(
        topLeft = innerCorner,
        topRight = outerCorner,
        bottomLeft = innerCorner,
        bottomRight = outerCorner,
    )

    val startColumnContainerShape = AbsoluteRoundedCornerShape(
        topLeft = outerCorner,
        topRight = outerCorner,
        bottomLeft = innerCorner,
        bottomRight = innerCorner,
    )

    val middleColumnContainerShape = AbsoluteRoundedCornerShape(
        topLeft = innerCorner,
        topRight = innerCorner,
        bottomLeft = innerCorner,
        bottomRight = innerCorner,
    )

    val endColumnContainerShape = AbsoluteRoundedCornerShape(
        topLeft = innerCorner,
        topRight = innerCorner,
        bottomLeft = outerCorner,
        bottomRight = outerCorner,
    )
}

@Composable
private fun Container(
    modifier: Modifier = Modifier,
    surfaceColor: Color,
    contentColor: Color,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier = modifier
                .drawBehind {
                    drawRect(surfaceColor)
                },
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
fun SplitContentRowContainer(
    modifier: Modifier = Modifier,
    elevated: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    content: SplitContentContainerScope.() -> Unit,
) {
    val latestContent = rememberUpdatedState(content)
    val scope by remember { derivedStateOf { SplitContentContainerScopeImpl().apply(latestContent.value) } }

    val surfaceColor by animateColorAsState(
        targetValue = if (elevated) {
            color
        } else {
            color.copy(alpha = 0f)
        }
    )
    val contentColor by animateColorAsState(
        targetValue = if (elevated) {
            MaterialTheme.colorScheme.contentColorFor(color)
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        scope.itemList.forEachIndexed { index, content ->
            val containerShape = when (index) {
                0 -> if (scope.itemList.size == 1) {
                    SplitContentContainer.fullRoundCornerShape
                } else {
                    SplitContentContainer.startRowContainerShape
                }

                scope.itemList.lastIndex -> SplitContentContainer.endRowContainerShape
                else -> SplitContentContainer.middleRowContainerShape
            }
            Container(
                modifier = Modifier
                    .clip(containerShape)
                    .wrapContentSize(),
                surfaceColor = surfaceColor,
                contentColor = contentColor,
                content = content,
            )
        }
    }
}

@Preview
@Composable
private fun SplitContentRowContainerPreview() {
    DnsNetTheme {
        Column(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val scope = rememberCoroutineScope()
            var elevated by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                scope.launch {
                    while (true) {
                        elevated = !elevated
                        delay(1000)
                    }
                }
            }

            SplitContentRowContainer(elevated = elevated) {
                item {
                    Icon(
                        modifier = Modifier.fullSizeClickable(onClick = {}),
                        imageVector = Icons.AutoMirrored.Default.ArrowBack,
                        contentDescription = null,
                    )
                }
            }

            SplitContentRowContainer(elevated = elevated) {
                item {
                    Icon(
                        modifier = Modifier.fullSizeClickable(onClick = {}),
                        imageVector = Icons.AutoMirrored.Default.ArrowBack,
                        contentDescription = null,
                    )
                }
                item {
                    Icon(
                        modifier = Modifier.fullSizeClickable(onClick = {}),
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                    )
                }
            }

            SplitContentRowContainer(elevated = elevated) {
                item {
                    Icon(
                        modifier = Modifier.fullSizeClickable(onClick = {}),
                        imageVector = Icons.AutoMirrored.Default.ArrowBack,
                        contentDescription = null,
                    )
                }
                item {
                    Icon(
                        modifier = Modifier.fullSizeClickable(onClick = {}),
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                    )
                }
                item {
                    Icon(
                        modifier = Modifier.fullSizeClickable(onClick = {}),
                        imageVector = Icons.Default.VpnKey,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}

@Composable
fun SplitContentColumnContainer(
    modifier: Modifier = Modifier,
    elevated: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    content: SplitContentContainerScope.() -> Unit,
) {
    val latestContent = rememberUpdatedState(content)
    val scope by remember { derivedStateOf { SplitContentContainerScopeImpl().apply(latestContent.value) } }

    val surfaceColor by animateColorAsState(
        targetValue = if (elevated) {
            color
        } else {
            color.copy(alpha = 0f)
        }
    )
    val contentColor by animateColorAsState(
        targetValue = if (elevated) {
            MaterialTheme.colorScheme.contentColorFor(color)
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        scope.itemList.forEachIndexed { index, content ->
            val containerShape = when (index) {
                0 -> if (scope.itemList.size == 1) {
                    SplitContentContainer.fullRoundCornerShape
                } else {
                    SplitContentContainer.startColumnContainerShape
                }

                scope.itemList.lastIndex -> SplitContentContainer.endColumnContainerShape
                else -> SplitContentContainer.middleColumnContainerShape
            }
            Container(
                modifier = Modifier
                    .clip(containerShape)
                    .wrapContentSize(),
                surfaceColor = surfaceColor,
                contentColor = contentColor,
                content = content,
            )
        }
    }
}

@Preview
@Composable
private fun SplitContentColumnContainerPreview() {
    DnsNetTheme {
        Column(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val scope = rememberCoroutineScope()
            var elevated by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                scope.launch {
                    while (true) {
                        elevated = !elevated
                        delay(1000)
                    }
                }
            }

            SplitContentColumnContainer(elevated = elevated) {
                item {
                    Icon(
                        modifier = Modifier.fullSizeClickable(onClick = {}),
                        imageVector = Icons.AutoMirrored.Default.ArrowBack,
                        contentDescription = null,
                    )
                }
            }

            SplitContentColumnContainer(elevated = elevated) {
                item {
                    Icon(
                        modifier = Modifier.fullSizeClickable(onClick = {}),
                        imageVector = Icons.AutoMirrored.Default.ArrowBack,
                        contentDescription = null,
                    )
                }
                item {
                    Icon(
                        modifier = Modifier.fullSizeClickable(onClick = {}),
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                    )
                }
            }

            SplitContentColumnContainer(elevated = elevated) {
                item {
                    Icon(
                        modifier = Modifier.fullSizeClickable(onClick = {}),
                        imageVector = Icons.AutoMirrored.Default.ArrowBack,
                        contentDescription = null,
                    )
                }
                item {
                    Icon(
                        modifier = Modifier.fullSizeClickable(onClick = {}),
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                    )
                }
                item {
                    Icon(
                        modifier = Modifier.fullSizeClickable(onClick = {}),
                        imageVector = Icons.Default.VpnKey,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}

sealed interface SplitContentContainerScope {
    val itemList: MutableVector<@Composable BoxScope.() -> Unit>

    fun item(content: @Composable BoxScope.() -> Unit)
}

class SplitContentContainerScopeImpl : SplitContentContainerScope {
    override val itemList: MutableVector<@Composable BoxScope.() -> Unit> = mutableVectorOf()

    override fun item(content: @Composable BoxScope.() -> Unit) {
        itemList.add(content)
    }
}
