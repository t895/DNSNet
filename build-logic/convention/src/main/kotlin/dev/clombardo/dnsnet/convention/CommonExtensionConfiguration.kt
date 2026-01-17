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

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.findByType

internal fun Project.configureCommon(action: CommonExtension.() -> Unit) {
    val extension =
        extensions.findByType<ApplicationExtension>() ?: extensions.findByType<LibraryExtension>()
        ?: throw IllegalStateException("Module does not contain library nor application")
    action(extension)
}
