package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.KnownHost

/**
 * A host key decision that the user must make before the app connects to [machineId] again.
 *
 * The transport stops the connection before it sends credentials and puts this prompt in
 * [SshStateManager.hostKeyPrompts]. The UI shows the prompt. The transport does not wait for the answer.
 */
sealed interface HostKeyPrompt {
    /** The machine that the connection was for. */
    val machineId: String

    /** The host key that the server sent. */
    val presented: KnownHost

    /**
     * The server sent a host key that the app does not know. When the user accepts [presented],
     * the UI saves it and connects again.
     */
    data class Unknown(
        override val machineId: String,
        override val presented: KnownHost,
    ) : HostKeyPrompt

    /**
     * The server sent a host key that is different from the saved key [known]. This can be an attack.
     * The UI shows both fingerprints. The user can remove [known] and then connect again.
     */
    data class Changed(
        override val machineId: String,
        val known: KnownHost,
        override val presented: KnownHost,
    ) : HostKeyPrompt
}
