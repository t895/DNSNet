/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import io.gitlab.arturbosch.detekt.Detekt

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.accrescent.bundletool)
    alias(libs.plugins.arturbosch.detekt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.gradle.play.publisher)
}

android {
    namespace = "dev.clombardo.dnsnet"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.clombardo.dnsnet"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
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
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }

        create("benchmark") {
            val benchmark = ".benchmark"
            applicationIdSuffix = benchmark
            versionNameSuffix = benchmark
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "DNSNet Benchmark")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Required for reproducible builds on F-Droid
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    androidResources {
        generateLocaleConfig = true
    }

    /**
     * Workaround for compiler error with hilt+ksp
     */
    packaging {
        resources {
            excludes += "/META-INF/gradle/incremental.annotation.processors"
        }
    }

    /**
     * Already excluded in the :service module
     */
    lint {
        disable += "RemoveWorkManagerInitializer"
    }
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
}

dependencies {
    implementation(libs.androidx.appcompat)

    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    debugImplementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.coil.compose)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.activity.compose)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.atomicfu)

    // Baseline profiles
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.core.splashscreen)

    detektPlugins(libs.detekt.formatting)

    implementation(libs.haze)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)

    implementation(libs.hilt)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)

    implementation(project(":ui-app"))
    implementation(project(":ui-common"))
    implementation(project(":settings"))
    implementation(project(":log"))
    implementation(project(":file"))
    implementation(project(":resources"))
    implementation(project(":service"))
    implementation(project(":notification"))
    implementation(project(":blocklogger"))
}

detekt {
    toolVersion = libs.versions.detekt.get()
    config.setFrom(file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    autoCorrect = true
}

tasks.withType<Detekt>().configureEach {
    reports {
        html.required.set(true)
    }
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
