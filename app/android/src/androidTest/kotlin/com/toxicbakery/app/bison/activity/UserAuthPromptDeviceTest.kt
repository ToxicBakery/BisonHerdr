package com.toxicbakery.app.bison.activity

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.user_auth_prompt_title
import com.toxicbakery.app.bison.ssh.AndroidKeystoreKeyManager
import com.toxicbakery.app.bison.ssh.SshStateManager
import com.toxicbakery.app.bison.ssh.UserAuthOutcome
import com.toxicbakery.app.bison.ssh.UserAuthRequiredException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DIAware
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * Device test: a key with `requireBiometric` is locked, and it signs after a successful prompt of [MainActivity].
 *
 * The test needs a device or an emulator with a PIN screen lock and no enrolled biometric. It enters the PIN in
 * the prompt. Give the PIN in the instrumentation argument `bisonTestPin`. Without the argument, the test does
 * not run. For example:
 *
 * ```
 * adb shell locksettings set-pin 1234
 * ./gradlew :app:android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.bisonTestPin=1234
 * ```
 */
@RunWith(AndroidJUnit4::class)
class UserAuthPromptDeviceTest {

    private val keyManager = AndroidKeystoreKeyManager()

    @After
    fun deleteTestKey() {
        keyManager.deleteKey(KEY_ALIAS)
    }

    @Test
    fun aBiometricKeySignsAfterASuccessfulPrompt() = runBlocking {
        val pin = InstrumentationRegistry.getArguments().getString(PIN_ARGUMENT)
        assumeTrue("Give the screen lock PIN in the argument $PIN_ARGUMENT", !pin.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assumeTrue("The test needs a secure screen lock", keyguard.isDeviceSecure)
        val stateManager = (context as DIAware).di.direct.instance<SshStateManager>()
        keyManager.deleteKey(KEY_ALIAS)
        keyManager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256, requireBiometric = true)

        // A device unlock in the last 30 s unlocks the key too. Wait until the key is locked.
        assertTrue("The key did not lock", awaitLockedKey())

        ActivityScenario.launch(MainActivity::class.java).use {
            val outcome = async(Dispatchers.Default) {
                stateManager.requestUserAuth(
                    machineId = MACHINE_ID,
                    machineName = MACHINE_ID,
                    keyAlias = KEY_ALIAS,
                    timeoutMs = PROMPT_TIMEOUT_MS,
                )
            }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val title = getString(Res.string.user_auth_prompt_title)
            assertTrue("The prompt did not open", device.wait(Until.hasObject(By.text(title)), PROMPT_WAIT_MS))
            device.executeShellCommand("input text $pin")
            device.pressEnter()

            assertEquals(UserAuthOutcome.Confirmed, outcome.await())
        }

        // The key is unlocked for 30 s after the prompt.
        val sshSignature = keyManager.signSshChallenge(KEY_ALIAS, KeyAlgorithm.EcdsaP256, CHALLENGE)
        assertTrue(sshSignature.isNotEmpty())
        assertTrue("The key does not make a signature that verifies", signAndVerifyWithStoredKey())
    }

    /** Returns true when a sign throws a [UserAuthRequiredException] before the time limit. */
    private suspend fun awaitLockedKey(): Boolean {
        repeat(LOCK_WAIT_SECONDS) {
            try {
                keyManager.signSshChallenge(KEY_ALIAS, KeyAlgorithm.EcdsaP256, CHALLENGE)
            } catch (_: UserAuthRequiredException) {
                return true
            }
            delay(ONE_SECOND_MS)
        }
        return false
    }

    /** Signs [CHALLENGE] with the stored private key and verifies the signature with the stored public key. */
    private fun signAndVerifyWithStoredKey(): Boolean {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val signature = Signature.getInstance(ECDSA_SIGNATURE).run {
            initSign(keyStore.getKey(KEY_ALIAS, null) as PrivateKey)
            update(CHALLENGE)
            sign()
        }
        return Signature.getInstance(ECDSA_SIGNATURE).run {
            initVerify(keyStore.getCertificate(KEY_ALIAS).publicKey)
            update(CHALLENGE)
            verify(signature)
        }
    }

    private companion object {
        const val PIN_ARGUMENT = "bisonTestPin"
        const val KEY_ALIAS = "bison_device_test_user_auth_prompt"
        const val MACHINE_ID = "user-auth-device-test"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ECDSA_SIGNATURE = "SHA256withECDSA"
        const val PROMPT_TIMEOUT_MS = 60_000L
        const val PROMPT_WAIT_MS = 10_000L
        const val LOCK_WAIT_SECONDS = 40
        const val ONE_SECOND_MS = 1_000L
        val CHALLENGE = "ssh-userauth-prompt-device-test".encodeToByteArray()
    }
}
