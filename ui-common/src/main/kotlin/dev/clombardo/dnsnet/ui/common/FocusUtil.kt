package dev.clombardo.dnsnet.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester

@Composable
fun rememberFocusRequester(): FocusRequester = remember { FocusRequester() }
