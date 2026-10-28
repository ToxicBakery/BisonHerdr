package com.toxicbakery.app.bison.activity

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.user_auth_prompt_description
import com.toxicbakery.app.bison.resources.user_auth_prompt_subtitle
import com.toxicbakery.app.bison.resources.user_auth_prompt_title
import com.toxicbakery.app.bison.ssh.UserAuthOutcome
import com.toxicbakery.app.bison.ssh.UserAuthPrompt
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.resources.getString

/**
 * Shows a [BiometricPrompt] for [prompt] and returns the result. Call it on the main thread.
 *
 * The prompt accepts a strong biometric or the device screen lock, the same types that unlock a key with
 * `requireBiometric`. It has no `CryptoObject`: after a user authentication, the key can sign for a short
 * time on the JSch thread. When the caller cancels this function, it closes the prompt.
 */
internal suspend fun FragmentActivity.showUserAuthPrompt(prompt: UserAuthPrompt): UserAuthOutcome {
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(getString(Res.string.user_auth_prompt_title))
        .setSubtitle(getString(Res.string.user_auth_prompt_subtitle, prompt.machineName))
        .setDescription(getString(Res.string.user_auth_prompt_description, prompt.keyAlias))
        .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        .build()
    val mainExecutor = ContextCompat.getMainExecutor(this)
    return suspendCancellableCoroutine { continuation ->
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(UserAuthOutcome.Confirmed)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (continuation.isActive) continuation.resume(userAuthOutcomeFor(errorCode, errString))
            }

            // onAuthenticationFailed: the biometric did not agree. The prompt stays open for a new attempt.
        }
        val biometricPrompt = BiometricPrompt(this, mainExecutor, callback)
        continuation.invokeOnCancellation {
            mainExecutor.execute { biometricPrompt.cancelAuthentication() }
        }
        biometricPrompt.authenticate(promptInfo)
    }
}

/** Returns the [UserAuthOutcome] for the [BiometricPrompt] error [errorCode] with the text [errString]. */
internal fun userAuthOutcomeFor(errorCode: Int, errString: CharSequence): UserAuthOutcome =
    when (errorCode) {
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED,
        -> UserAuthOutcome.Cancelled

        else -> UserAuthOutcome.Failed(errString.toString())
    }
