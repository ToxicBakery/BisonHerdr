package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.JSchException

/**
 * The login to the ProxyJump bastion [bastionAddress] failed. [bastionAddress] has the form `user@host:port`.
 *
 * The message starts with the message of [cause] and then names the bastion. Thus [RealSshBackend.toAuthResult]
 * gives the same kind as for [cause]: for example [SshAuthResult.AuthFailed] for a refused login, and
 * [SshAuthResult.NetworkError] when the client cannot get to the bastion.
 */
class BastionConnectException(
    val bastionAddress: String,
    cause: JSchException,
) : JSchException("${cause.message} (bastion $bastionAddress)", cause)
