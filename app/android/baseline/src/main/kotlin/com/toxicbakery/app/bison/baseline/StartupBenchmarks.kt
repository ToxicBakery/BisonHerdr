package com.toxicbakery.app.bison.baseline

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmarks measuring cold startup time (`timeToInitialDisplayMs` and `timeToFullDisplayMs`)
 * for BisonHerdr with no pre-compilation vs. Baseline + Startup Profile compilation.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmarks {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupCompilationNone() = benchmark(CompilationMode.None())

    @Test
    fun startupCompilationBaselineProfiles() = benchmark(
        CompilationMode.Partial(BaselineProfileMode.Require)
    )

    private fun benchmark(compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = BaselineProfileGenerator.PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = {
                pressHome()
            },
        ) {
            startActivityAndWait()
            device.wait(Until.hasObject(By.text(INITIAL_SCREEN_TITLE_PATTERN)), DEFAULT_TIMEOUT_MS)
            device.waitForIdle()
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 5_000L
        val INITIAL_SCREEN_TITLE_PATTERN: Pattern =
            Pattern.compile(".*(BisonHerdr|Machine Federation).*")
    }
}
