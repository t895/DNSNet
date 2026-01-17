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

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

fun Project.configureKotlin() = configure<KotlinAndroidProjectExtension> {
    jvmToolchain(libs.findVersion("java").get().toString().toInt())
    compilerOptions.apply {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}
