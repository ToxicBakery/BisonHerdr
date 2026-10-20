package com.toxicbakery.app.bison.common.terminal

import androidx.compose.runtime.Immutable

enum class TerminalModifierKey {
    Ctrl,
    Alt,
    Shift,
}

/**
 * State of a touch modifier key (`Ctrl`, `Alt`, `Shift`) in the Left Split-Thumb Cluster:
 * - [Off]: Modifier is inactive.
 * - [StickyOneShot]: Armed for the next key press only, then automatically resets to [Off].
 * - [Locked]: Locked active for all subsequent key presses until tapped again.
 */
enum class ModifierMode {
    Off,
    StickyOneShot,
    Locked,
    ;

    val isActive: Boolean
        get() = this != Off
}

@Immutable
data class ModifierKeyState(
    val ctrl: ModifierMode = ModifierMode.Off,
    val alt: ModifierMode = ModifierMode.Off,
    val shift: ModifierMode = ModifierMode.Off,
    val lastCtrlTapMs: Long? = null,
    val lastAltTapMs: Long? = null,
    val lastShiftTapMs: Long? = null,
) {
    val hasActiveModifiers: Boolean
        get() = ctrl.isActive || alt.isActive || shift.isActive

    fun modeFor(key: TerminalModifierKey): ModifierMode = when (key) {
        TerminalModifierKey.Ctrl -> ctrl
        TerminalModifierKey.Alt -> alt
        TerminalModifierKey.Shift -> shift
    }

    /**
     * Handles a single tap on [key]:
     * - [ModifierMode.Off] -> [ModifierMode.StickyOneShot]
     * - [ModifierMode.StickyOneShot] or [ModifierMode.Locked] -> [ModifierMode.Off]
     */
    fun onSingleTap(key: TerminalModifierKey): ModifierKeyState {
        val next = when (modeFor(key)) {
            ModifierMode.Off -> ModifierMode.StickyOneShot
            ModifierMode.StickyOneShot, ModifierMode.Locked -> ModifierMode.Off
        }
        return withMode(key, next, tapTimestampMs = null)
    }

    /**
     * Handles a double-tap on [key], locking the modifier until tapped again.
     */
    fun onDoubleTap(key: TerminalModifierKey): ModifierKeyState {
        val next = when (modeFor(key)) {
            ModifierMode.Locked -> ModifierMode.Off
            ModifierMode.Off, ModifierMode.StickyOneShot -> ModifierMode.Locked
        }
        return withMode(key, next, tapTimestampMs = null)
    }

    /**
     * Handles a tap on [key] at [timestampMs]:
     * - Single tap from [ModifierMode.Off] arms [ModifierMode.StickyOneShot].
     * - Second tap within [doubleTapWindowMs] upgrades [ModifierMode.StickyOneShot] to [ModifierMode.Locked].
     * - Tap after [doubleTapWindowMs] or while [ModifierMode.Locked] releases to [ModifierMode.Off].
     */
    fun onTap(
        key: TerminalModifierKey,
        timestampMs: Long,
        doubleTapWindowMs: Long = DEFAULT_DOUBLE_TAP_WINDOW_MS,
    ): ModifierKeyState {
        val currentMode = modeFor(key)
        val lastTap = lastTapFor(key)
        val isRapidSecondTap = lastTap != null && (timestampMs - lastTap) in 0..doubleTapWindowMs
        val nextMode = when (currentMode) {
            ModifierMode.Off -> ModifierMode.StickyOneShot
            ModifierMode.StickyOneShot -> if (isRapidSecondTap) ModifierMode.Locked else ModifierMode.Off
            ModifierMode.Locked -> ModifierMode.Off
        }
        val recordedTimestamp = if (nextMode == ModifierMode.StickyOneShot) timestampMs else null
        return withMode(key, nextMode, recordedTimestamp)
    }

    /**
     * Consumes any [ModifierMode.StickyOneShot] modifiers after a key or character is sent,
     * while preserving [ModifierMode.Locked] modifiers.
     */
    fun consumeOneShot(): ModifierKeyState = copy(
        ctrl = if (ctrl == ModifierMode.StickyOneShot) ModifierMode.Off else ctrl,
        alt = if (alt == ModifierMode.StickyOneShot) ModifierMode.Off else alt,
        shift = if (shift == ModifierMode.StickyOneShot) ModifierMode.Off else shift,
        lastCtrlTapMs = null,
        lastAltTapMs = null,
        lastShiftTapMs = null,
    )

    /**
     * Formats [baseKey] (e.g. `"c"`, `"b"`, `"Tab"`, `"Up"`) into a Herdr key chord string
     * incorporating any active [ctrl], [alt], and [shift] modifiers.
     */
    fun formatKeyChord(baseKey: String): String {
        val normalizedBase = when {
            baseKey.startsWith("^") && baseKey.length == 2 -> {
                val letter = baseKey[1].lowercaseChar()
                return buildList {
                    add("ctrl")
                    if (alt.isActive) add("alt")
                    if (shift.isActive) add("shift")
                    add(letter.toString())
                }.joinToString("+")
            }
            else -> baseKey
        }
        if (!hasActiveModifiers) return normalizedBase
        return buildList {
            if (ctrl.isActive) add("ctrl")
            if (alt.isActive) add("alt")
            if (shift.isActive) add("shift")
            add(normalizedBase.lowercase())
        }.joinToString("+")
    }

    private fun lastTapFor(key: TerminalModifierKey): Long? = when (key) {
        TerminalModifierKey.Ctrl -> lastCtrlTapMs
        TerminalModifierKey.Alt -> lastAltTapMs
        TerminalModifierKey.Shift -> lastShiftTapMs
    }

    private fun withMode(
        key: TerminalModifierKey,
        mode: ModifierMode,
        tapTimestampMs: Long?,
    ): ModifierKeyState = when (key) {
        TerminalModifierKey.Ctrl -> copy(ctrl = mode, lastCtrlTapMs = tapTimestampMs)
        TerminalModifierKey.Alt -> copy(alt = mode, lastAltTapMs = tapTimestampMs)
        TerminalModifierKey.Shift -> copy(shift = mode, lastShiftTapMs = tapTimestampMs)
    }

    companion object {
        const val DEFAULT_DOUBLE_TAP_WINDOW_MS: Long = 350L
    }
}
