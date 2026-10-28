package com.toxicbakery.app.bison

import com.toxicbakery.app.bison.model.FeatureFlags
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Makes sure that FIDO2 security keys stay off in a build with no `bison.hardwareSecurityKeys` property, as in CI
 * (issue #23). A build with `-Pbison.hardwareSecurityKeys=true` is a test build, and this test fails in it.
 */
class FeatureFlagsDefaultTest {

    @Test
    fun hardwareSecurityKeysAreOffByDefault() {
        assertFalse(BuildConfig.HARDWARE_SECURITY_KEYS)
        assertFalse(FeatureFlags().hardwareSecurityKeys)
    }
}
