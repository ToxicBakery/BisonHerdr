package com.toxicbakery.app.bison.ssh

/**
 * The `herdr` programs that [TestSshServer] gives to the tests of the real SSH path.
 *
 * A test must never run the real `herdr` of the test machine. The Gradle daemon can have the `HERDR_SOCKET_PATH` of a
 * live Herdr session, so a real `herdr` can read or change that session. CI has no `herdr`. Thus each command of
 * [TestSshServer] finds [NO_HERDR_SCRIPT], or a fake that the test installs with [TestSshServer.installHerdr].
 */
internal object TestHerdr {

    /** The path of the `herdr` of a home, `$HOME/.local/bin/herdr`. The client workers put this directory first. */
    const val HERDR_PATH = ".local/bin/herdr"

    /**
     * A `herdr` that acts as a host with no `herdr`, as on CI. It writes nothing to the output, and it stops with the
     * exit status 127 of a missing command.
     */
    const val NO_HERDR_SCRIPT = "#!/bin/sh\necho \"herdr: not found\" >&2\nexit 127\n"

    /** The fake Herdr 0.9.5 of `src/test/resources/fake-herdr.sh`. */
    val FAKE_HERDR_SCRIPT: String by lazy {
        requireNotNull(TestHerdr::class.java.getResource("/fake-herdr.sh")) { "No fake-herdr.sh" }.readText()
    }
}
