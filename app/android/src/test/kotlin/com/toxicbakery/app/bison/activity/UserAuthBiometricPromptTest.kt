package com.toxicbakery.app.bison.activity

import androidx.biometric.BiometricPrompt
import com.toxicbakery.app.bison.ssh.UserAuthOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests the map from a [BiometricPrompt] error to a [UserAuthOutcome]. */
class UserAuthBiometricPromptTest {

    @Test
    fun aCancelOfTheUserOrTheSystemIsCancelled() {
        listOf(
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_CANCELED,
        ).forEach { errorCode ->
            assertEquals(UserAuthOutcome.Cancelled, userAuthOutcomeFor(errorCode, "Cancelled"), "Error $errorCode")
        }
    }

    @Test
    fun otherErrorsFailWithTheTextOfTheSystem() {
        assertEquals(
            UserAuthOutcome.Failed("Too many attempts. Try again later."),
            userAuthOutcomeFor(BiometricPrompt.ERROR_LOCKOUT, "Too many attempts. Try again later."),
        )
        assertEquals(
            UserAuthOutcome.Failed("No screen lock"),
            userAuthOutcomeFor(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, "No screen lock"),
        )
    }
}
