package com.toxicbakery.app.bison.ssh

import java.io.IOException

/**
 * The session of the machine [machineName] is closed, and its [SessionSupervisor] has no new session yet. The
 * command did not run.
 *
 * It is an [IOException], because the cause is a closed connection. Thus [RealSshBackend.toAuthResult] gives
 * [SshAuthResult.NetworkError].
 */
class SessionUnavailableException(val machineName: String) : IOException("The session to $machineName is not open")
