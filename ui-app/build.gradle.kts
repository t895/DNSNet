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
    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.aboutLibrariesAndroid)
    alias(libs.plugins.dnsnet.hilt)
}

android {
    namespace = "dev.clombardo.dnsnet.ui.app"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.accompanist.permissions)

    implementation(libs.string.similarity.kotlin)

    implementation(libs.coil.compose)

    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.core)

    implementation(project(":ui-common"))
    implementation(project(":settings"))
    implementation(project(":common"))
    implementation(project(":blocklogger"))
}

aboutLibraries {
    export {
        outputFile = File("src/main/res/raw/aboutlibraries.json")
    }
}
