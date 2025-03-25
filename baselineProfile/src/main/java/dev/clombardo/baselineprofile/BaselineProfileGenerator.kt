/* Copyright (C) 2025 Charles Lombardo <clombardo169@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.clombardo.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.clombardo.dnsnet.ui.app.Setup
import dev.clombardo.dnsnet.ui.app.Start
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This test class generates a basic startup baseline profile for the target package.
 *
 * We recommend you start with this but add important user flows to the profile to improve their performance.
 * Refer to the [baseline profile documentation](https://d.android.com/topic/performance/baselineprofiles)
 * for more information.
 *
 * You can run the generator with the "Generate Baseline Profile" run configuration in Android Studio or
 * the equivalent `generateBaselineProfile` gradle task:
 * ```
 * ./gradlew :app:generateReleaseBaselineProfile
 * ```
 * The run configuration runs the Gradle task and applies filtering to run only the generators.
 *
 * Check [documentation](https://d.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args)
 * for more information about available instrumentation arguments.
 *
 * After you run the generator, you can verify the improvements running the [StartupBenchmarks] benchmark.
 *
 * When using this class to generate a baseline profile, only API 33+ or rooted API 28+ are supported.
 *
 * The minimum required version of androidx.benchmark to generate a baseline profile is 1.2.0.
 **/
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        // The application id for the running build variant is read from the instrumentation arguments.
        rule.collect(
            packageName = InstrumentationRegistry.getArguments().getString("targetAppId")
                ?: throw Exception("targetAppId not passed as instrumentation runner arg"),

            // See: https://d.android.com/topic/performance/baselineprofiles/dex-layout-optimizations
            includeInStartupProfile = true
        ) {
            pressHome()
            startActivityAndWait()

            val getStarted = By.res(Setup.TEST_TAG_GET_STARTED)
            device.wait(Until.hasObject(getStarted), 5_000)
            device.findObject(getStarted)?.click()

            val acknowledgeOne = By.res(Setup.TEST_TAG_ACKNOWLEDGED_ONE)
            device.wait(Until.hasObject(acknowledgeOne), 5_000)
            device.findObject(acknowledgeOne)?.click()

            val acknowledgeTwo = By.res(Setup.TEST_TAG_ACKNOWLEDGED_TWO)
            device.wait(Until.hasObject(acknowledgeTwo), 5_000)
            device.findObject(acknowledgeTwo)?.click()

            val continueButton = By.res(Setup.TEST_TAG_CONTINUE)
            device.wait(Until.hasObject(continueButton), 5_000)
            device.findObject(continueButton)?.click()

            val startButton = By.res(Start.TEST_TAG_START_BUTTON)
            device.wait(Until.hasObject(startButton), 5_000)
            device.findObject(startButton).clickAndWait(Until.newWindow(), 5_000)

            val vpnOkButton = By.text("OK")
            device.wait(Until.hasObject(vpnOkButton), 5_000)
            device.findObject(vpnOkButton)?.click()

            val hosts = By.res("homeNavigation:Hosts")
            device.wait(Until.hasObject(hosts), 5_000)
            device.findObject(hosts).click()

            val apps = By.res("homeNavigation:Apps")
            device.findObject(apps).click()

            val dns = By.res("homeNavigation:DNS")
            device.findObject(dns).click()
        }
    }
}
