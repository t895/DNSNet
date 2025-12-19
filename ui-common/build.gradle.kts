/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

plugins {
    alias(libs.plugins.dnsnet.android.library)
    alias(libs.plugins.dnsnet.kotlin.json)
    alias(libs.plugins.dnsnet.compose)
}

android {
    namespace = "dev.clombardo.dnsnet.ui.common"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.material3.adaptive.navigation.suite)

    implementation(libs.materialswitch)

    implementation(project(":log"))
    implementation(project(":resources"))
}
