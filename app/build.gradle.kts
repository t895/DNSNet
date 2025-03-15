import com.android.build.gradle.tasks.MergeSourceSetFolders
import com.nishtahir.CargoBuildTask
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
    alias(libs.plugins.cash.licensee)
    alias(libs.plugins.usefulness.licensee)
    alias(libs.plugins.arturbosch.detekt)
    alias(libs.plugins.rust.android.gradle)
}

val libnet = "libnet"

// Required for reproducible builds on F-Droid
val remapCargo = listOf(
    "--config",
    "build.rustflags = [ '--remap-path-prefix=${System.getenv("HOME")}/.cargo=/rust/cargo' ]",
)

cargo {
    module = "../$libnet"
    libname = "net"

    targets = listOf("arm64", "arm", "x86", "x86_64")

    pythonCommand = "python3"

    val isDebug = gradle.startParameter.taskNames.any {
        it.lowercase().contains("debug")
    }
    if (!isDebug) {
        profile = "release"
    }
}

val task = tasks.register<Exec>("uniffiBindgen") {
    val s = File.separatorChar
    workingDir = file("${project.rootDir}${s}$libnet")
    commandLine(
        "cargo",
        "run",
        "--bin",
        "uniffi-bindgen",
        "generate",
        "--library",
        "${project.rootDir}${s}app${s}build${s}rustJniLibs${s}android${s}arm64-v8a${s}$libnet.so",
        "--language",
        "kotlin",
        "--out-dir",
        layout.buildDirectory.dir("generated${s}kotlin").get().asFile.path
    )
}

project.afterEvaluate {
    tasks.withType(CargoBuildTask::class)
        .forEach { buildTask ->
            tasks.withType(MergeSourceSetFolders::class)
                .configureEach {
                    inputs.dir(
                        layout.buildDirectory.dir("rustJniLibs" + File.separatorChar + buildTask.toolchain!!.folder)
                    )
                    dependsOn(buildTask)
                }
        }
}

tasks.preBuild.configure {
    dependsOn.add(tasks.withType(CargoBuildTask::class.java))
    dependsOn.add(task)
}

android {
    namespace = "dev.clombardo.dnsnet"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.clombardo.dnsnet"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 44
        versionName = "1.1.10"

        ndk {
            abiFilters += listOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a")
        }
    }

    ndkVersion = "28.0.13004108"

    sourceSets {
        getByName("main").java.srcDir("build/generated/kotlin")
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
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
                this.storeFile = storeFile
                this.storePassword = storePassword
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

    lint {
        disable += "ExtraTranslation"
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
    implementation(libs.androidx.material3.adaptive.navigation.suite)

    implementation(libs.accompanist.permissions)

    implementation(libs.coil.compose)

    implementation(libs.androidx.navigation.compose)

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

    implementation(libs.androidx.preference.ktx)

    implementation(libs.androidx.core.splashscreen)

    implementation(libs.materialswitch)

    detektPlugins(libs.detekt.formatting)

    implementation(libs.haze)

    implementation(libs.string.similarity.kotlin)

    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }
}

licensee {
    allow("MIT")
    allow("Apache-2.0")
    allow("BSD-3-Clause")
    allowUrl("https://opensource.org/licenses/mit")
    allowUrl("https://github.com/usefulness/licensee-for-android/blob/master/LICENSE") // MIT
    allowUrl("https://github.com/aallam/string-similarity-kotlin/blob/main/LICENSE") // MIT
}

licenseeForAndroid {
    enableKotlinCodeGeneration = true
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
