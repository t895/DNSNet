/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import com.github.triplet.gradle.androidpublisher.ReleaseStatus

plugins {
    alias(libs.plugins.dnsnet.android.application)
    alias(libs.plugins.dnsnet.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.dnsnet.kotlin.json)
    alias(libs.plugins.dnsnet.atomicfu)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.accrescent.bundletool)
    alias(libs.plugins.dnsnet.hilt)
    alias(libs.plugins.gradle.play.publisher)
}

android {
    namespace = "dev.clombardo.dnsnet"

    defaultConfig {
        applicationId = "dev.clombardo.dnsnet"
        versionCode = rootProject.extra["versionCode"] as Int
        versionName = rootProject.extra["versionName"] as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val storeFilePath = System.getenv("STORE_FILE_PATH")
    if (storeFilePath != null) {
        val keyAlias = System.getenv("KEY_ALIAS")
        val keyPassword = System.getenv("KEY_PASSWORD")
        val storeFile = file(storeFilePath)
        val storePassword = System.getenv("STORE_PASSWORD")
        signingConfigs {
            create("release") {
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
                this.storeFile = storeFile
                this.storePassword = storePassword
                enableV4Signing = true
            }
        }

        bundletool {
            signingConfig {
                this.keyAlias.set(keyAlias)
                this.keyPassword.set(keyPassword)
                this.storeFile.set(storeFile)
                this.storePassword.set(storePassword)
            }
        }
    }

    buildTypes {
        debug {
            val debug = ".debug"
            applicationIdSuffix = debug
            versionNameSuffix = debug
            resValue("string", "app_name", "DNSNet Debug")
        }

        release {
            if (storeFilePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        create("benchmark") {
            val benchmark = ".benchmark"
            applicationIdSuffix = benchmark
            versionNameSuffix = benchmark
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "DNSNet Benchmark")
        }
    }

    // Required for reproducible builds on F-Droid
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    /**
     * Workaround for compiler error with hilt+ksp
     */
    packaging {
        resources {
            excludes += "/META-INF/gradle/incremental.annotation.processors"
        }
        jniLibs {
            excludes.apply {
                add("**/x86/**")
                add("**/armeabi/**")
                add("**/mips64/**")
                add("**/mips/**")
            }
        }
    }

    /**
     * Already excluded in the :service module
     */
    lint {
        disable += "RemoveWorkManagerInitializer"
    }
}

dependencies {
    implementation(libs.androidx.appcompat)

    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.coil.compose)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.activity.compose)

    // Baseline profiles
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.core.splashscreen)

    implementation(libs.haze)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)

    implementation(libs.androidx.hilt.work)

    implementation(project(":ui-app"))
    implementation(project(":ui-common"))
    implementation(project(":settings"))
    implementation(project(":common"))
    implementation(project(":resources"))
    implementation(project(":service"))
    implementation(project(":blocklogger"))
}

play {
    val keyPath = System.getenv("SERVICE_ACCOUNT_KEY_PATH")
    if (keyPath != null) {
        serviceAccountCredentials.set(file(keyPath))
    }
    track.set(System.getenv("STORE_TRACK") ?: "internal")
    releaseStatus.set(ReleaseStatus.IN_PROGRESS)
    defaultToAppBundles.set(true)
    userFraction.set(0.2)
}
