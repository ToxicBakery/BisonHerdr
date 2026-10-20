package com.toxicbakery.app.bison.baseline

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates Baseline Profiles (`baseline-prof.txt`) and Startup Profiles (`startup-prof.txt`)
 * for `:app:android`.
 *
 * - [startup] records the Startup Profile (`includeInStartupProfile = true`) for DEX layout
 *   optimization and pre-compilation of classes/methods executed from cold process start through
 *   initial `MainActivity` + `MachineFederationScreen` frame composition.
 * - [criticalUserJourneys] records additional baseline profile hot paths across the initial
 *   Machine Federation screen, the 19-theme & ergonomics bottom sheet, and the host configuration
 *   screen (`ConfigureMachineScreen`).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        waitForInitialFederationScreen()
    }

    @Test
    fun criticalUserJourneys() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = false,
    ) {
        pressHome()
        startActivityAndWait()
        waitForInitialFederationScreen()

        // 1. Exercise Theme & Settings bottom sheet composition (19 color palettes & ergonomics)
        device.findObject(By.desc("Theme & Settings"))?.let { settingsButton ->
            settingsButton.click()
            device.wait(Until.hasObject(By.textContains("Color Themes")), DEFAULT_TIMEOUT_MS)
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
        }

        // 2. Exercise ConfigureMachineScreen navigation and form composition
        device.findObject(By.desc("Add Herdr Host"))?.let { addHostButton ->
            addHostButton.click()
            device.wait(Until.hasObject(By.textContains("Host Identity")), DEFAULT_TIMEOUT_MS)
            device.waitForIdle()
            val backButton = device.findObject(By.desc("Back"))
            if (backButton != null) {
                backButton.click()
            } else {
                device.pressBack()
            }
            waitForInitialFederationScreen()
        }
    }

    private fun MacrobenchmarkScope.waitForInitialFederationScreen() {
        device.wait(Until.hasObject(By.text(INITIAL_SCREEN_TITLE_PATTERN)), DEFAULT_TIMEOUT_MS)
        device.waitForIdle()
    }

    companion object {
        const val PACKAGE_NAME: String = "com.toxicbakery.app.bison"
        private const val DEFAULT_TIMEOUT_MS: Long = 5_000L
        private val INITIAL_SCREEN_TITLE_PATTERN: Pattern =
            Pattern.compile(".*(BisonHerdr|Machine Federation).*")
    }
}
