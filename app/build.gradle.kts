plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.jak_linux.dns66"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.jak_linux.dns66"
        minSdk = 21
        targetSdk = 34
        versionCode = 29
        versionName = "0.6.8"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Proxy stuff
    implementation("org.pcap4j:pcap4j-core:1.8.2")
    implementation("org.pcap4j:pcap4j-packetfactory-static:1.8.2")
    implementation("dnsjava:dnsjava:3.0.0")

    implementation("com.google.code.gson:gson:2.10.1")
}
