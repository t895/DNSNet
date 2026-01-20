package dev.clombardo.dnsnet.convention

import com.android.build.api.dsl.TestExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

fun Project.androidTest(block: TestExtension.() -> Unit) {
    extensions.configure<TestExtension>(block)
}
