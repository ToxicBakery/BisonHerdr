package com.toxicbakery.app.bison.ssh

/** The result of [SshBackend.checkLiveness]. */
sealed interface LivenessResult {

    /** The session replied in time. */
    data object Live : LivenessResult

    /**
     * The session did not reply in time, or the machine had no open session. [sessionId] identifies the session that
     * the check used, or is null when the machine had no session.
     *
     * Give [sessionId] to [SshBackend.reconnectNow]. Thus a check of an old session does not close a newer session
     * that the reconnect loop opened during the check.
     */
    data class Dead(val sessionId: Long?) : LivenessResult
}
