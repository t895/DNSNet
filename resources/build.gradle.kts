/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import dev.clombardo.dnsnet.convention.androidLibrary

plugins {
    alias(libs.plugins.dnsnet.android.library)
}

androidLibrary {
    namespace = "dev.clombardo.dnsnet.resources"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    lint {
        disable.apply {
            add("MissingTranslation")
            add("ExtraTranslation")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
}
