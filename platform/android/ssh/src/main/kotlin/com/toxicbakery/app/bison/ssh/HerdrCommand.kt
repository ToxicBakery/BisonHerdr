package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine

/**
 * Makes the start of each `herdr` command that the app runs on a host.
 *
 * Each Herdr session has its own API socket. A named session keeps its socket in `sessions/<name>/herdr.sock` in the
 * Herdr configuration directory. A `herdr` command without `--session` uses the socket of the default session. Thus
 * each `herdr` command for a machine with [HostMachine.herdrSessionName] must have the flag. This rule also applies to
 * each `herdr remote-api-bridge` call.
 *
 * Herdr removes `--session <name>` from the arguments before it reads the subcommand (`configure_from_args` in
 * `src/session.rs` of Herdr 0.9.5). With the flag, Herdr ignores `HERDR_SOCKET_PATH` and `HERDR_SESSION` in the
 * environment of the host.
 */
internal object HerdrCommand {

    /** Returns the start of a `herdr` command for [machine]. See [forSession]. */
    fun of(machine: HostMachine): String = forSession(machine.herdrSessionName)

    /**
     * Returns `herdr --session '<name>'` for the Herdr session [sessionName]. A null or blank name gives `herdr`, so
     * the command uses the default session.
     */
    fun forSession(sessionName: String?): String {
        val name = sessionName?.trim()?.takeIf { it.isNotEmpty() } ?: return HERDR
        return "$HERDR --session ${shellQuote(name)}"
    }

    /** Puts [value] in POSIX single quotes. The shell then gives the value to the command as one argument. */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private const val HERDR = "herdr"
}
