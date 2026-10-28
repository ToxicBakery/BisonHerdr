package com.toxicbakery.app.bison.ssh

import kotlinx.coroutines.CompletableDeferred

/** One question of a [KeyboardInteractivePrompt]. When [echo] is false, the UI hides the text that the user types. */
data class KeyboardInteractiveQuestion(
    val text: String,
    val echo: Boolean,
)

/**
 * A set of questions that the user must answer before a login to [machineId] can continue.
 *
 * - When [passwordMissing] is false, the SSH server at [hostPort] sent the questions with the `keyboard-interactive`
 *   method. [name], [instruction], and the text of each question come from the server. [hostPort] is the bastion or
 *   the target of the machine.
 * - When [passwordMissing] is true, this device has no password for the machine, for example after a restore from a
 *   backup. The prompt has one hidden question for the password, and the UI uses its own texts. The UI also offers to
 *   save the password ([KeyboardInteractiveOutcome.Answered.savePassword]).
 *
 * The transport puts the prompt in [SshStateManager.keyboardInteractivePrompts] and waits for the answer. A UI shows
 * the prompt and gives the answer with [SshStateManager.answerKeyboardInteractive]. [machineName] is the machine name
 * that the UI shows.
 *
 * Two prompts are equal only when they are the same instance.
 */
class KeyboardInteractivePrompt(
    val machineId: String,
    val machineName: String,
    val hostPort: String,
    val name: String,
    val instruction: String,
    val questions: List<KeyboardInteractiveQuestion>,
    val passwordMissing: Boolean = false,
) {
    /** The answer to this prompt. Only the first answer has an effect. */
    internal val answer = CompletableDeferred<KeyboardInteractiveOutcome>()

    override fun toString(): String =
        "KeyboardInteractivePrompt(machineId=$machineId, hostPort=$hostPort, questions=${questions.size}, " +
            "passwordMissing=$passwordMissing)"
}

/** The result of a [KeyboardInteractivePrompt]. */
sealed interface KeyboardInteractiveOutcome {

    /**
     * The user typed [answers], one for each question in the same sequence. [savePassword] is true when the user
     * wants to keep the typed password on this device. Only a prompt with
     * [KeyboardInteractivePrompt.passwordMissing] offers it.
     *
     * The text of this class does not show the answers, because an answer can be a password.
     */
    class Answered(
        val answers: List<String>,
        val savePassword: Boolean = false,
    ) : KeyboardInteractiveOutcome {
        override fun toString(): String = "Answered(answers=${answers.size}, savePassword=$savePassword)"
    }

    /** The user closed the prompt and gave no answers. */
    data object Cancelled : KeyboardInteractiveOutcome

    /** No UI shows prompts, for example because the app is in the background. The app did not show the prompt. */
    data object NoUi : KeyboardInteractiveOutcome

    /** The user did not answer before the time limit. */
    data object TimedOut : KeyboardInteractiveOutcome
}
