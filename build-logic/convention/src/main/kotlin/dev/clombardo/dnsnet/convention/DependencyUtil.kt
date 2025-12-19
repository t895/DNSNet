/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Contributions shall also be provided under any later versions of the
 * GPL.
 */

package dev.clombardo.dnsnet.convention

import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.DependencyHandlerScope

fun <T : Any> DependencyHandlerScope.implementation(provider: Provider<T>) =
    "implementation"(provider)

fun <T : Any> DependencyHandlerScope.debugImplementation(provider: Provider<T>) =
    "debugImplementation"(provider)

fun <T : Any> DependencyHandlerScope.androidTestImplementation(provider: Provider<T>) =
    "androidTestImplementation"(provider)

fun <T : Any> DependencyHandlerScope.ksp(provider: Provider<T>) =
    "ksp"(provider)
