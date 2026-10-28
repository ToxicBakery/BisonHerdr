package com.toxicbakery.app.bison.ssh

/**
 * The login stopped, because the user did not answer the questions of a host. [result] is
 * [SshAuthResult.InputRequired] or [SshAuthResult.InputCancelled], and tells the host that asked.
 *
 * [KeyboardInteractiveUserInfo] throws it on the thread of `Session.connect`. JSch lets a [RuntimeException] go out
 * of the login with no change, and closes the session. [RealSshBackend.toAuthResult] gives [result].
 */
class UserInputException private constructor(
    val result: SshAuthResult,
) : RuntimeException(describe(result)) {

    companion object {
        /**
         * Returns the exception for the [outcome] of a prompt of [hostPort]. Throws an [IllegalArgumentException] for
         * [KeyboardInteractiveOutcome.Answered], because an answer does not stop the login.
         */
        fun of(hostPort: String, outcome: KeyboardInteractiveOutcome): UserInputException =
            UserInputException(
                when (outcome) {
                    KeyboardInteractiveOutcome.NoUi -> SshAuthResult.InputRequired(hostPort, timedOut = false)
                    KeyboardInteractiveOutcome.TimedOut -> SshAuthResult.InputRequired(hostPort, timedOut = true)
                    KeyboardInteractiveOutcome.Cancelled -> SshAuthResult.InputCancelled(hostPort)
                    is KeyboardInteractiveOutcome.Answered ->
                        throw IllegalArgumentException("An answer does not stop the login")
                },
            )

        private fun describe(result: SshAuthResult): String =
            when (result) {
                is SshAuthResult.InputRequired ->
                    if (result.timedOut) {
                        "The user did not answer the questions of ${result.hostPort} before the time limit"
                    } else {
                        "The host ${result.hostPort} asks questions, and no UI is open to ask the user"
                    }

                is SshAuthResult.InputCancelled -> "The user cancelled the questions of ${result.hostPort}"
                else -> result.toString()
            }
    }
}
