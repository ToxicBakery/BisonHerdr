package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

/**
 * The JSch [UserInfo] of one session of [machineId]: the bastion session or the target session. [hostPort] is the
 * `host:port` of that session.
 *
 * JSch answers one set of questions on its own before it calls this class. In each start of the
 * `keyboard-interactive` method, it gives the session password (`Session.setPassword`) one time to a set with exactly
 * one hidden question that contains "password:" (in any letter case). This also occurs for a question such as
 * "New password:". The password goes only to the host of the session. This class cannot stop it.
 *
 * JSch calls [promptKeyboardInteractive] on the thread of `Session.connect` for each other set of questions. The
 * function blocks that thread until it has the answers:
 * 1. A set with no questions gets no answers. The user sees no prompt.
 * 2. When [password] is not null, the first set that gets to this class with exactly one hidden question that contains
 *    "password" (in any letter case) gets [password]. This occurs one time for each session. The user sees no
 *    prompt. A question with the word "new" does not get [password].
 * 3. Each other set goes to [ask] as a [KeyboardInteractivePrompt] for [hostPort]. The answers of the user go to the
 *    host. A prompt with no answers throws a [UserInputException], and the login stops at once.
 *
 * The other functions never ask the user, and they never accept a host key or give a password:
 * - [promptYesNo] returns false. JSch asks it only about a host key. Only [KnownHostsRepository] and the host key
 *   sheet decide about host keys.
 * - [promptPassword] and [promptPassphrase] return false, and [getPassword] and [getPassphrase] return null. The
 *   password method of JSch uses only the bytes of `Session.setPassword`. Thus the stored password goes into a
 *   [String] only in step 2, because JSch accepts only [String] answers there. JSch changes that [String] into bytes
 *   at once and keeps no reference to it.
 *
 * When [callerJob] is cancelled, the wait for the answers stops, and the login stops with a [CancellationException].
 * The caller owns [password] and clears it after the login.
 */
internal class KeyboardInteractiveUserInfo(
    private val machineId: String,
    private val machineName: String,
    private val hostPort: String,
    private val password: ByteArray?,
    private val callerJob: Job?,
    private val sshDebugLog: SshDebugLog,
    private val ask: suspend (KeyboardInteractivePrompt) -> KeyboardInteractiveOutcome,
) : UserInfo, UIKeyboardInteractive {

    /** True after step 2 gave [password] one time. A second password question that gets here goes to the user. */
    @Volatile
    private var passwordUsed = false

    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?,
    ): Array<String> {
        val texts = prompt.orEmpty().toList()
        if (texts.isEmpty()) return emptyArray()
        val questions = texts.mapIndexed { index, text ->
            KeyboardInteractiveQuestion(text = text, echo = echo?.getOrNull(index) ?: false)
        }
        storedPasswordAnswer(questions)?.let { return it }
        sshDebugLog.record(
            machineId = machineId,
            tag = LOG_TAG,
            message = "The host $hostPort asks ${questions.size} question(s). The app asks the user.",
        )
        val outcome = askAndWait(
            KeyboardInteractivePrompt(
                machineId = machineId,
                machineName = machineName,
                hostPort = hostPort,
                name = name.orEmpty(),
                instruction = instruction.orEmpty(),
                questions = questions,
            ),
        )
        if (outcome is KeyboardInteractiveOutcome.Answered && outcome.answers.size == questions.size) {
            return outcome.answers.toTypedArray()
        }
        // An answer with a different number of answers is an error of the UI. The host cannot use it.
        val stop = if (outcome is KeyboardInteractiveOutcome.Answered) KeyboardInteractiveOutcome.Cancelled else outcome
        throw UserInputException.of(hostPort, stop).also { error ->
            sshDebugLog.record(machineId = machineId, tag = LOG_TAG, message = error.message.orEmpty(), isError = true)
        }
    }

    /** Returns [password] as the answer to [questions] for step 2, or null when step 2 does not apply. */
    private fun storedPasswordAnswer(questions: List<KeyboardInteractiveQuestion>): Array<String>? {
        val stored = password ?: return null
        val question = questions.singleOrNull() ?: return null
        val asksForPassword = !question.echo &&
            question.text.contains(PASSWORD_WORD, ignoreCase = true) &&
            !NEW_WORD.containsMatchIn(question.text)
        if (!asksForPassword || passwordUsed) return null
        passwordUsed = true
        sshDebugLog.record(
            machineId = machineId,
            tag = LOG_TAG,
            message = "The host $hostPort asks for the password. The app gave the password of the machine.",
        )
        return arrayOf(stored.decodeToString())
    }

    /**
     * Gives [prompt] to [ask] and blocks this thread until the result. The wait is a child of [callerJob], so a
     * cancel of the caller stops it. An error of [ask] gives [KeyboardInteractiveOutcome.NoUi], because the child
     * must not fail the caller.
     */
    private fun askAndWait(prompt: KeyboardInteractivePrompt): KeyboardInteractiveOutcome =
        runBlocking(callerJob ?: EmptyCoroutineContext) {
            try {
                ask(prompt)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                sshDebugLog.record(
                    machineId = machineId,
                    tag = LOG_TAG,
                    message = "The app could not ask the user: ${error.message ?: error::class.simpleName}",
                    isError = true,
                )
                KeyboardInteractiveOutcome.NoUi
            }
        }

    /** Returns false. Only [KnownHostsRepository] and the host key sheet decide about host keys. */
    override fun promptYesNo(message: String?): Boolean = false

    /** Returns false. The password method of JSch uses only the bytes of `Session.setPassword`. */
    override fun promptPassword(message: String?): Boolean = false

    /** Returns null. See [promptPassword]. */
    override fun getPassword(): String? = null

    /** Returns false. The app does not use key files with a passphrase. */
    override fun promptPassphrase(message: String?): Boolean = false

    /** Returns null. See [promptPassphrase]. */
    override fun getPassphrase(): String? = null

    /** Writes [message] from the host into the debug log. */
    override fun showMessage(message: String?) {
        if (message.isNullOrBlank()) return
        sshDebugLog.record(machineId = machineId, tag = LOG_TAG, message = "$hostPort: $message")
    }

    private companion object {
        const val LOG_TAG = "KeyboardInteractive"
        const val PASSWORD_WORD = "password"

        /** A question with this word asks for a new password, for example after the old password expired. */
        val NEW_WORD = Regex("\\bnew\\b", RegexOption.IGNORE_CASE)
    }
}
