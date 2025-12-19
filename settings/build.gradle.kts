/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

plugins {
    alias(libs.plugins.dnsnet.android.library)
    alias(libs.plugins.dnsnet.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.dnsnet.kotlin.json)
    alias(libs.plugins.dnsnet.atomicfu)
    alias(libs.plugins.dnsnet.hilt)
}

android {
    namespace = "dev.clombardo.dnsnet.settings"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(libs.androidx.preference.ktx)

    implementation(project(":log"))
    implementation(project(":file"))
    implementation(project(":resources"))
}
