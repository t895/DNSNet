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

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

abstract class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        apply(plugin = "org.jetbrains.kotlin.plugin.compose")
        configureCommon {
            buildFeatures {
                compose = true
            }

            dependencies {
                val bom = platform(libs.findLibrary("compose-bom").get())
                implementation(bom)
                debugImplementation(bom)
                androidTestImplementation(bom)
                implementation(libs.findLibrary("androidx-material3").get())
                implementation(libs.findLibrary("androidx-ui-tooling-preview").get())
                debugImplementation(libs.findLibrary("androidx-ui-tooling").get())
            }
        }
    }
}
