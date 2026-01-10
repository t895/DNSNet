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
    alias(libs.plugins.dnsnet.android.library)
    alias(libs.plugins.rust.android.gradle)
    alias(libs.plugins.dnsnet.atomicfu)
    alias(libs.plugins.dnsnet.hilt)
}

val libnetModule = "libnet_bindings"

// Required for reproducible builds on F-Droid
val remapCargo = listOf(
    "--config",
    "build.rustflags = [ '--remap-path-prefix=${System.getenv("CARGO_HOME")}=/rust/cargo' ]",
)

cargo {
    module = libnetModule
    libname = "net_bindings"

    targets = listOf("arm64", "arm", "x86_64")
    targetDirectory = "./target"

    pythonCommand = "python3"

    val isDebug = gradle.startParameter.taskNames.any {
        it.lowercase().contains("debug")
    }
    if (!isDebug) {
        profile = "release"
    }
}

val uniffiBindgen = tasks.register<Exec>("uniffiBindgen") {
    workingDir = project.layout.projectDirectory.asFile
    commandLine = listOf(
        "cargo",
        "run",
        "--bin",
        "uniffi-bindgen",
        "generate",
        "--library",
        project.layout.projectDirectory.dir("build").dir("rustJniLibs").dir("android")
            .dir("arm64-v8a").file("libnet_bindings.so").asFile.path,
        "--language",
        "kotlin",
        "--out-dir",
        project.layout.buildDirectory.get().dir("generated").dir("kotlin").asFile.path
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
                    dependsOn(buildTask)
                }
        }
}

tasks.preBuild.configure {
    dependsOn.add("uniffiBindgen")
}

abstract class CleanRustTarget @Inject constructor(
    private val projectLayout: ProjectLayout,
    private val fileSystemOperations: FileSystemOperations
) : DefaultTask() {
    @TaskAction
    fun clean() {
        fileSystemOperations.delete {
            delete(projectLayout.projectDirectory.dir("target"))
        }
    }

    companion object {
        const val NAME = "cleanRustTarget"
    }
}

tasks.register(CleanRustTarget.NAME, CleanRustTarget::class)

tasks.getByName("clean") {
    dependsOn(CleanRustTarget.NAME)
}

android {
    namespace = "dev.clombardo.dnsnet.service"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("x86_64", "arm64-v8a", "armeabi-v7a")
        }
    }

    ndkVersion = "29.0.14206865"

    sourceSets {
        getByName("main") {
            java.srcDir("build/generated/kotlin")
            jniLibs.srcDir("build/rustJniLibs")
        }
    }
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.core.ktx)

    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }

    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.extensions.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)

    implementation(project(":common"))
    implementation(project(":ui-common"))
    implementation(project(":resources"))
    implementation(project(":settings"))
    implementation(project(":blocklogger"))
    implementation(project(":network"))
}
