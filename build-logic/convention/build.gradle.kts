/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Contributions shall also be provided under any later versions of the
 * GPL.
 */

plugins {
    `kotlin-dsl`
}

group = "dev.clombardo.dnsnet.buildlogic"

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
}

dependencies {
    compileOnly(libs.android.gradle.api)
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplicationConventionPlugin") {
            id = "dnsnet.android.application"
            implementationClass = "dev.clombardo.dnsnet.convention.AndroidApplicationConventionPlugin"
        }
        register("androidLibraryConventionPlugin") {
            id = "dnsnet.android.library"
            implementationClass = "dev.clombardo.dnsnet.convention.AndroidLibraryConventionPlugin"
        }
        register("hiltConventionPlugin") {
            id = "dnsnet.hilt"
            implementationClass = "dev.clombardo.dnsnet.convention.HiltConventionPlugin"
        }
        register("kotlinJsonConventionPlugin") {
            id = "dnsnet.kotlinx.serialization.json"
            implementationClass = "dev.clombardo.dnsnet.convention.KotlinJsonConventionPlugin"
        }
        register("composeConventionPlugin") {
            id = "dnsnet.compose"
            implementationClass = "dev.clombardo.dnsnet.convention.ComposeConventionPlugin"
        }
        register("atomicfuConventionPlugin") {
            id = "dnsnet.atomicfu"
            implementationClass = "dev.clombardo.dnsnet.convention.AtomicfuConventionPlugin"
        }
    }
}
