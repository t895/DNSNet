/* Copyright (C) 2026 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

plugins {
    alias(libs.plugins.dnsnet.android.library)
    alias(libs.plugins.dnsnet.hilt)
}

android {
    namespace = "dev.clombardo.dnsnet.network"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
}
