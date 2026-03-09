/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import dev.clombardo.dnsnet.convention.androidTest

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
}

androidTest {
    namespace = "dev.clombardo.baselineprofile"
    compileSdkPreview = libs.versions.compileSdk.get()

    defaultConfig {
        minSdk = libs.versions.minSdkBaselineProfile.get().toInt()
        targetSdkPreview = libs.versions.targetSdk.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)

    implementation(project(":ui-app"))
}

androidComponents {
    onVariants { v ->
        val artifactsLoader = v.artifacts.getBuiltArtifactsLoader()
        @Suppress("UnstableApiUsage")
        v.instrumentationRunnerArguments.put(
            "targetAppId",
            v.testedApks.map { artifactsLoader.load(it)?.applicationId ?: "" }
        )
    }
}
