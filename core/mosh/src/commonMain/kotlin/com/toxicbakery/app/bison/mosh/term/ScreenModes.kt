package com.toxicbakery.app.bison.mosh.term

/** The mouse tracking modes (DECSET 1000 to 1003). The screen has one of them, or none. */
enum class MouseTracking(
    /** The DECSET number, or 0 for [Off]. */
    val mode: Int,
) {
    /** No mouse report. */
    Off(0),

    /** DECSET 1000: reports for a press and a release. */
    Normal(1000),

    /** DECSET 1001: highlight tracking. */
    Highlight(1001),

    /** DECSET 1002: reports also for a move while a button is down. */
    ButtonMotion(1002),

    /** DECSET 1003: reports for each move. */
    AnyMotion(1003),
}

/** The mouse encodings (DECSET 1005, 1006, and 1015). The screen has one of them, or the default encoding. */
enum class MouseEncoding(
    /** The DECSET number, or 0 for [Default]. */
    val mode: Int,
) {
    /** The X10 encoding with one byte for each coordinate. */
    Default(0),

    /** DECSET 1005: UTF-8 coordinates. */
    Utf8(1005),

    /** DECSET 1006: SGR mouse reports. */
    Sgr(1006),

    /** DECSET 1015: urxvt mouse reports. */
    Urxvt(1015),
}

/**
 * The DEC private modes that `mosh-server` sends in its diffs (decision 3). The server sends the modes of its
 * application, which is the Herdr attach client, not the pane program.
 *
 * A set of a tracking mode replaces the tracking mode. A reset of any tracking mode sets [MouseTracking.Off]. The
 * encodings follow the same rule. This is the rule of the emulator in `mosh-server` 1.4.0 (black-box test).
 */
data class ScreenModes(
    /** DECSET 25 (DECTCEM). */
    val cursorVisible: Boolean = true,
    /** DECSET 5 (DECSCNM): the full screen shows in reverse video. */
    val reverseVideo: Boolean = false,
    val mouseTracking: MouseTracking = MouseTracking.Off,
    val mouseEncoding: MouseEncoding = MouseEncoding.Default,
    /** DECSET 1004: reports for a focus change. */
    val focusEvents: Boolean = false,
    /** DECSET 2004: the application wants a paste between `CSI 200~` and `CSI 201~`. */
    val bracketedPaste: Boolean = false,
) {

    /**
     * Returns the modes after DECSET ([set] true) or DECRST ([set] false) of [mode]. Returns null when the
     * emulator does not know [mode].
     */
    internal fun with(mode: Int, set: Boolean): ScreenModes? {
        val tracking = MouseTracking.entries.firstOrNull { entry -> entry != MouseTracking.Off && entry.mode == mode }
        val encoding = MouseEncoding.entries.firstOrNull { entry ->
            entry != MouseEncoding.Default && entry.mode == mode
        }
        return when {
            mode == MODE_CURSOR_VISIBLE -> copy(cursorVisible = set)
            mode == MODE_REVERSE_VIDEO -> copy(reverseVideo = set)
            mode == MODE_FOCUS_EVENTS -> copy(focusEvents = set)
            mode == MODE_BRACKETED_PASTE -> copy(bracketedPaste = set)
            tracking != null -> copy(mouseTracking = if (set) tracking else MouseTracking.Off)
            encoding != null -> copy(mouseEncoding = if (set) encoding else MouseEncoding.Default)
            else -> null
        }
    }

    private companion object {
        const val MODE_CURSOR_VISIBLE = 25
        const val MODE_REVERSE_VIDEO = 5
        const val MODE_FOCUS_EVENTS = 1004
        const val MODE_BRACKETED_PASTE = 2004
    }
}
