/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import com.android.build.gradle.tasks.MergeSourceSetFolders
import com.nishtahir.CargoBuildTask
import org.gradle.kotlin.dsl.support.delegates.ProjectDelegate

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("org.mozilla.rust-android-gradle.rust-android")
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
    workingDir = layout.projectDirectory.file(libnet).asFile
    commandLine(
        "cargo",
        "run",
        "--bin",
        "uniffi-bindgen",
        "generate",
        "--library",
        layout.projectDirectory.dir("build").dir("rustJniLibs").dir("android")
            .dir("arm64-v8a").file("libnet.so").asFile.path,
        "--language",
        "kotlin",
        "--out-dir",
        layout.buildDirectory.get().dir("generated").dir("kotlin").asFile.path
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
                    inputs.dir(
                        layout.buildDirectory.get().dir("rustJniLibs")
                            .dir(buildTask.toolchain!!.folder)
                    )
                    dependsOn(buildTask)
                }
        }
}

tasks.preBuild.configure {
    dependsOn.add(tasks.withType(CargoBuildTask::class.java))
    dependsOn.add(uniffiBindgen)
}

abstract class CleanRustTarget @Inject constructor(private val projectLayout: ProjectLayout) :
    DefaultTask() {
    @TaskAction
    fun clean() {
        projectLayout.projectDirectory.dir("libnet").dir("target").asFile.deleteRecursively()
    }
}

tasks.register("cleanRustTarget", CleanRustTarget::class)

tasks.getByName("clean") {
    dependsOn("cleanRustTarget")
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
