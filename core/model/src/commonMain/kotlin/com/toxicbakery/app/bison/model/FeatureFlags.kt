package com.toxicbakery.app.bison.model

/**
 * The features that a build turns on or off. The app binds one instance in the DI graph. Code that gets no instance
 * from the DI graph uses the default instance. Each flag is off by default.
 *
 * The Android app reads the values from Gradle properties. See `app/android/build.gradle.kts`.
 */
data class FeatureFlags(
    /**
     * True to show the FIDO2 hardware security key option, to start the USB and NFC discovery, and to log in with
     * a security key. False hides the option, and the app refuses each connect of a machine with a security key.
     *
     * Issue #23 (https://github.com/ToxicBakery/BisonHerdr/issues/23) adds this feature. The flag stays off until
     * the manual test in `docs/testing/fido2-security-keys.md` passes. The Gradle property
     * `bison.hardwareSecurityKeys=true` sets it for a test build.
     */
    val hardwareSecurityKeys: Boolean = false,
)
