package com.toxicbakery.app.bison.common.screen.machine

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.toxicbakery.app.bison.common.theme.LocalBisonPalette
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.common_cancel
import com.toxicbakery.app.bison.resources.common_connect
import com.toxicbakery.app.bison.resources.keyboard_interactive_body
import com.toxicbakery.app.bison.resources.keyboard_interactive_submit
import com.toxicbakery.app.bison.resources.keyboard_interactive_title
import com.toxicbakery.app.bison.resources.password_missing_body
import com.toxicbakery.app.bison.resources.password_missing_label
import com.toxicbakery.app.bison.resources.password_missing_save
import com.toxicbakery.app.bison.resources.password_missing_title
import com.toxicbakery.app.bison.ssh.KeyboardInteractiveOutcome
import com.toxicbakery.app.bison.ssh.KeyboardInteractivePrompt
import com.toxicbakery.app.bison.ssh.SshStateManager
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

/**
 * Shows the oldest open prompt of [SshStateManager.keyboardInteractivePrompts] in a [KeyboardInteractivePromptSheet],
 * and gives the answer to [SshStateManager.answerKeyboardInteractive].
 *
 * The transport asks the user only while the activity runs [SshStateManager.hostUserAuthPrompts], that is while it
 * is started. In the background, a login with questions thus stops at once and does not wait. A prompt that is open
 * when the app goes to the background stays open until its time limit, and this host shows it again when the app
 * comes back. Thus the user can read a one-time code in a different app and then answer.
 */
@Composable
internal fun KeyboardInteractivePromptHost() {
    val sshStateManager: SshStateManager by rememberInstance()
    val prompts by sshStateManager.keyboardInteractivePrompts.collectAsState()
    val prompt = prompts.firstOrNull() ?: return
    // A new prompt gets empty fields, also when it has the same questions as the last prompt.
    key(prompt) {
        KeyboardInteractivePromptSheet(
            prompt = prompt,
            onAnswer = { outcome -> sshStateManager.answerKeyboardInteractive(prompt, outcome) },
        )
    }
}

/**
 * Shows the questions of [prompt], one field for each question. A question with `echo = false` hides the typed text.
 *
 * - A prompt from a host shows the `host:port` that asks, the name and the instruction of the host, and the text of
 *   each question.
 * - A prompt for a missing password ([KeyboardInteractivePrompt.passwordMissing]) shows one password field and an
 *   option to save the password on this device.
 *
 * The send button gives [KeyboardInteractiveOutcome.Answered] to [onAnswer]. The cancel button and the back button give
 * [KeyboardInteractiveOutcome.Cancelled]. A tap outside the sheet does nothing, so that the user does not lose the
 * typed answers. The layout is the same as the host key sheet.
 */
@Composable
internal fun KeyboardInteractivePromptSheet(
    prompt: KeyboardInteractivePrompt,
    onAnswer: (KeyboardInteractiveOutcome) -> Unit,
) {
    val palette = LocalBisonPalette.current
    val answers = remember(prompt) { List(prompt.questions.size) { TextFieldState() } }
    var savePassword by remember(prompt) { mutableStateOf(false) }
    val firstField = remember(prompt) { FocusRequester() }
    val cancel = { onAnswer(KeyboardInteractiveOutcome.Cancelled) }
    val send = {
        onAnswer(
            KeyboardInteractiveOutcome.Answered(
                answers = answers.map { it.text.toString() },
                savePassword = prompt.passwordMissing && savePassword,
            ),
        )
    }
    Dialog(
        onDismissRequest = cancel,
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 340.dp, max = 520.dp)
                .border(1.5.dp, palette.accent, RoundedCornerShape(20.dp))
                .testTag(KEYBOARD_INTERACTIVE_DIALOG_TAG),
            shape = RoundedCornerShape(20.dp),
            color = palette.panelBg,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PromptHeader(prompt)
                prompt.questions.forEachIndexed { index, question ->
                    AnswerField(
                        state = answers[index],
                        label = if (prompt.passwordMissing) {
                            stringResource(Res.string.password_missing_label)
                        } else {
                            question.text.trim()
                        },
                        echo = question.echo,
                        isLast = index == prompt.questions.lastIndex,
                        onSend = send,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("$KEYBOARD_INTERACTIVE_ANSWER_TAG$index")
                            .let { if (index == 0) it.focusRequester(firstField) else it },
                    )
                }
                if (prompt.passwordMissing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = savePassword,
                            onCheckedChange = { savePassword = it },
                            modifier = Modifier.testTag(SAVE_PASSWORD_TAG),
                        )
                        Text(
                            text = stringResource(Res.string.password_missing_save),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.text,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    OutlinedButton(
                        onClick = cancel,
                        modifier = Modifier.testTag(KEYBOARD_INTERACTIVE_CANCEL_TAG),
                    ) {
                        Text(stringResource(Res.string.common_cancel))
                    }
                    Button(
                        onClick = send,
                        modifier = Modifier.testTag(KEYBOARD_INTERACTIVE_SEND_TAG),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accent,
                            contentColor = palette.terminalBg,
                        ),
                    ) {
                        Text(
                            text = if (prompt.passwordMissing) {
                                stringResource(Res.string.common_connect)
                            } else {
                                stringResource(Res.string.keyboard_interactive_submit)
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            LaunchedEffect(prompt) {
                // The first field gets the focus, so that the user can type at once. When the field is not ready, the
                // request fails, and the user taps the field.
                runCatching { firstField.requestFocus() }
            }
        }
    }
}

/**
 * Shows the field of one answer in [state]. When [echo] is false, the field hides the typed text. The keyboard action
 * of the last field calls [onSend]. The keyboard action of each other field moves to the next field.
 */
@Composable
private fun AnswerField(
    state: TextFieldState,
    label: String,
    echo: Boolean,
    isLast: Boolean,
    onSend: () -> Unit,
    modifier: Modifier,
) {
    val imeAction = if (isLast) ImeAction.Done else ImeAction.Next
    val onKeyboardAction = if (isLast) KeyboardActionHandler { onSend() } else null
    if (echo) {
        OutlinedTextField(
            state = state,
            label = { Text(label) },
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = imeAction),
            onKeyboardAction = onKeyboardAction,
            modifier = modifier,
        )
    } else {
        OutlinedSecureTextField(
            state = state,
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = imeAction,
            ),
            onKeyboardAction = onKeyboardAction,
            modifier = modifier,
        )
    }
}

/** Shows the title and the texts above the fields of [prompt]. */
@Composable
private fun PromptHeader(prompt: KeyboardInteractivePrompt) {
    val palette = LocalBisonPalette.current
    Text(
        text = if (prompt.passwordMissing) {
            stringResource(Res.string.password_missing_title)
        } else {
            stringResource(Res.string.keyboard_interactive_title)
        },
        style = MaterialTheme.typography.titleLarge,
        color = palette.accent,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = if (prompt.passwordMissing) {
            stringResource(Res.string.password_missing_body, prompt.machineName, prompt.hostPort)
        } else {
            stringResource(Res.string.keyboard_interactive_body, prompt.hostPort, prompt.machineName)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = palette.text,
    )
    if (!prompt.passwordMissing) {
        prompt.name.takeIf { it.isNotBlank() }?.let { name ->
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                color = palette.mauve,
            )
        }
        prompt.instruction.takeIf { it.isNotBlank() }?.let { instruction ->
            Text(
                text = instruction,
                style = MaterialTheme.typography.bodySmall,
                color = palette.text.copy(alpha = 0.72f),
            )
        }
    }
}

/** The test tag of the sheet of [KeyboardInteractivePromptSheet]. */
const val KEYBOARD_INTERACTIVE_DIALOG_TAG: String = "keyboardInteractivePromptDialog"

/** The start of the test tag of each answer field. The index of the question follows. */
const val KEYBOARD_INTERACTIVE_ANSWER_TAG: String = "keyboardInteractiveAnswer"

/** The test tag of the send button. */
const val KEYBOARD_INTERACTIVE_SEND_TAG: String = "keyboardInteractiveSend"

/** The test tag of the cancel button. */
const val KEYBOARD_INTERACTIVE_CANCEL_TAG: String = "keyboardInteractiveCancel"

/** The test tag of the option that saves a missing password. */
const val SAVE_PASSWORD_TAG: String = "savePassword"
