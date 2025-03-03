// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false

    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.serialization) apply false

    alias(libs.plugins.kotlinx.atomicfu) apply false

    alias(libs.plugins.androidx.baselineprofile) apply false

    alias(libs.plugins.accrescent.bundletool) apply false

    alias(libs.plugins.cash.licensee) apply false
    alias(libs.plugins.usefulness.licensee) apply false

    alias(libs.plugins.arturbosch.detekt) apply false

    alias(libs.plugins.rust.android.gradle) apply false
}
