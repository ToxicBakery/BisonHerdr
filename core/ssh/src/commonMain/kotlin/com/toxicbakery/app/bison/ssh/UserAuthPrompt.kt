package com.toxicbakery.app.bison.ssh

import kotlinx.coroutines.CompletableDeferred

/**
 * A request to the user to confirm the identity with a strong biometric or the device screen lock.
 *
 * The key [keyAlias] needs this user authentication before it can sign the login to [machineId]. The
 * transport puts the prompt in [SshStateManager.userAuthPrompts] and waits for the answer. A UI shows
 * the prompt and gives the answer through [SshStateManager.hostUserAuthPrompts]. [machineName] is the
 * machine name that the UI shows.
 *
 * Two prompts are equal only when they are the same instance.
 */
class UserAuthPrompt internal constructor(
    val machineId: String,
    val machineName: String,
    val keyAlias: String,
) {
    /** The answer to this prompt. Only the first answer has an effect. */
    internal val answer = CompletableDeferred<UserAuthOutcome>()

    override fun toString(): String = "UserAuthPrompt(machineId=$machineId, keyAlias=$keyAlias)"
}

/** The result of a [UserAuthPrompt]. */
sealed interface UserAuthOutcome {

    /** The user confirmed the identity. The key can sign for a short time. */
    data object Confirmed : UserAuthOutcome

    /** The user or the system closed the prompt before the user confirmed the identity. */
    data object Cancelled : UserAuthOutcome

    /** The prompt stopped with an error, for example after too many attempts. [reason] is the text of the system. */
    data class Failed(val reason: String) : UserAuthOutcome

    /** No UI shows prompts, for example because the app is in the background. The app did not show a prompt. */
    data object NoUi : UserAuthOutcome

    /** The user did not answer before the time limit. */
    data object TimedOut : UserAuthOutcome
}
