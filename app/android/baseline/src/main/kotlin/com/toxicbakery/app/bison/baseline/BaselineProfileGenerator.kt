package com.toxicbakery.app.bison.baseline

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() = baselineProfileRule.collect(packageName = PACKAGE_NAME) {
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Machine Federation")), 5_000)
    }

    companion object {
        const val PACKAGE_NAME: String = "com.toxicbakery.app.bison"
    }
}
