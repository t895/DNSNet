/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.cash.licensee)
    alias(libs.plugins.usefulness.licensee)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.clombardo.dnsnet.ui.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        create("benchmark")
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    debugImplementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3.adaptive.navigation.suite)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.accompanist.permissions)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.string.similarity.kotlin)

    implementation(libs.coil.compose)

    implementation(libs.atomicfu)

    implementation(libs.hilt)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(project(":ui-common"))
    implementation(project(":settings"))
    implementation(project(":log"))
    implementation(project(":blocklogger"))
}

licensee {
    allow("Apache-2.0")
    allowUrl("https://opensource.org/licenses/mit")
    allowUrl("https://github.com/usefulness/licensee-for-android/blob/master/LICENSE") // MIT
    allowUrl("https://github.com/aallam/string-similarity-kotlin/blob/main/LICENSE") // MIT
}

licenseeForAndroid {
    enableKotlinCodeGeneration = true
}
