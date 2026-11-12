package com.toxicbakery.app.bison.ssh

/**
 * Tells [SshTransportManager] if the app is in the foreground: one or more activities of the app are started.
 *
 * The surface lease of a machine is on only while the app is in the foreground. `:app:android` binds this interface
 * with Kodein to the counter of the started activities. A test gives a value that it can change.
 */
fun interface ForegroundState {

    /** Returns true while one or more activities of the app are started. */
    fun isInForeground(): Boolean

    companion object {
        /** Always in the foreground. Use it only for a manager with no activity, for example in a test. */
        val ALWAYS: ForegroundState = ForegroundState { true }
    }
}
