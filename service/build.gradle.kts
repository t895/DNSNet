/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import com.android.build.gradle.tasks.MergeSourceSetFolders
import com.nishtahir.CargoBuildTask

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.rust.android.gradle)
    alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val libnet = "libnet"

// Required for reproducible builds on F-Droid
val remapCargo = listOf(
    "--config",
    "build.rustflags = [ '--remap-path-prefix=${System.getenv("CARGO_HOME")}=/rust/cargo' ]",
)

cargo {
    module = libnet
    libname = "net"

    targets = listOf("arm64", "arm", "x86_64")

    pythonCommand = "python3"

    val isDebug = gradle.startParameter.taskNames.any {
        it.lowercase().contains("debug")
    }
    if (!isDebug) {
        profile = "release"
    }
}

val uniffiBindgen = tasks.register<Exec>("uniffiBindgen") {
    val s = File.separatorChar
    workingDir = file("${projectDir}${s}$libnet")
    commandLine(
        "cargo",
        "run",
        "--bin",
        "uniffi-bindgen",
        "generate",
        "--library",
        "${projectDir}${s}build${s}rustJniLibs${s}android${s}arm64-v8a${s}$libnet.so",
        "--language",
        "kotlin",
        "--out-dir",
        layout.buildDirectory.dir("generated${s}kotlin").get().asFile.path
    )
}

uniffiBindgen.configure {
    dependsOn.add(tasks.withType(CargoBuildTask::class.java))
}

project.afterEvaluate {
    tasks.withType(CargoBuildTask::class)
        .forEach { buildTask ->
            tasks.withType(MergeSourceSetFolders::class)
                .configureEach {
                    inputs.dir(layout.buildDirectory.dir("rustJniLibs" + File.separatorChar + buildTask.toolchain!!.folder))
                    dependsOn(buildTask)
                }
        }
}

tasks.preBuild.configure {
    dependsOn.add(tasks.withType(CargoBuildTask::class.java))
    dependsOn.add(uniffiBindgen)
}

tasks.getByName("clean") {
    doFirst {
        delete(layout.projectDirectory.dir(libnet + File.separatorChar + "target"))
    }
}

android {
    namespace = "dev.clombardo.dnsnet.service"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("x86_64", "arm64-v8a", "armeabi-v7a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    ndkVersion = "28.0.13004108"

    sourceSets {
        getByName("main") {
            java.srcDir("build/generated/kotlin")
            jniLibs.srcDir("build/rustJniLibs")
        }
    }

    buildTypes {
        create("benchmark")
    }
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.atomicfu)

    implementation(libs.androidx.core.ktx)

    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }

    implementation(libs.hilt)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.extensions.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)

    implementation(project(":log"))
    implementation(project(":file"))
    implementation(project(":ui-common"))
    implementation(project(":resources"))
    implementation(project(":settings"))
    implementation(project(":blocklogger"))
    implementation(project(":notification"))
}
