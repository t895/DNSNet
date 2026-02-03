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

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.internal.extensions.core.extra
import org.gradle.kotlin.dsl.provideDelegate

internal fun Project.configureAndroid(
    commonExtension: CommonExtension,
) = with(commonExtension) {
    compileSdk {
        version = release(libs.findVersion("compileSdk").get().toString().toInt())
    }

    defaultConfig.apply {
        minSdk {
            version = release(libs.findVersion("minSdk").get().toString().toInt())
        }

        val versionName: String by rootProject.extra
        buildConfigField(
            type = "String",
            name = "VERSION_NAME",
            value = "\"$versionName\"",
        )
    }

    buildFeatures.apply {
        buildConfig = true
        resValues = true
    }
}
