package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.protocol.ClientKeyCode
import com.toxicbakery.app.bison.protocol.ClientKeyEvent

/**
 * The form of one key for the pane input methods of Herdr. See [HerdrKeys].
 */
internal sealed interface HerdrKeyInput {
    /** A key name for `pane.send_keys`, for example `ctrl+c`. Herdr encodes it for the terminal modes of the pane. */
    data class Named(val spec: String) : HerdrKeyInput

    /** The terminal bytes of a key, for `pane.send_text`. Herdr writes this text to the pane with no change. */
    data class Raw(val bytes: String) : HerdrKeyInput
}

/**
 * Converts a [ClientKeyEvent] to a form that Herdr accepts.
 *
 * `pane.send_keys` accepts only the key names of `parse_key_combo` in `src/config/keybinds.rs` of Herdr 0.9.5. That
 * parser has no name for Home, End, PageUp, PageDown, Delete, or Insert. For an unknown name, Herdr answers
 * `invalid_key` and writes no key of the request. Thus the client sends these six keys as their xterm bytes with
 * `pane.send_text`.
 *
 * The Herdr API does not tell the cursor key mode (DECCKM) of a pane. Thus Home and End with no modifier always use
 * the normal mode bytes `ESC [ H` and `ESC [ F`. In the application mode, a terminal sends `ESC O H` and `ESC O F`.
 * A program that reads only the application mode bytes does not see these two keys.
 */
internal object HerdrKeys {

    /** Returns the form of [event] that Herdr accepts, or null when Herdr does not accept the key. */
    fun encode(event: ClientKeyEvent): HerdrKeyInput? = when (event.code) {
        ClientKeyCode.Enter -> named(event, "Enter")
        ClientKeyCode.Escape -> named(event, "Esc")
        ClientKeyCode.Tab -> named(event, "Tab")
        ClientKeyCode.Backspace -> named(event, "Backspace")
        ClientKeyCode.Up -> named(event, "Up")
        ClientKeyCode.Down -> named(event, "Down")
        ClientKeyCode.Left -> named(event, "Left")
        ClientKeyCode.Right -> named(event, "Right")
        ClientKeyCode.CharC -> named(event, "c")
        ClientKeyCode.CharB -> named(event, "b")
        ClientKeyCode.CharD -> named(event, "d")
        ClientKeyCode.CharZ -> named(event, "z")
        ClientKeyCode.CharY -> named(event, "y")
        ClientKeyCode.CharN -> named(event, "n")
        ClientKeyCode.Home -> xtermKey(event, number = 1, final = 'H')
        ClientKeyCode.End -> xtermKey(event, number = 1, final = 'F')
        ClientKeyCode.Insert -> xtermKey(event, number = 2, final = '~')
        ClientKeyCode.Delete -> xtermKey(event, number = 3, final = '~')
        ClientKeyCode.PageUp -> xtermKey(event, number = 5, final = '~')
        ClientKeyCode.PageDown -> xtermKey(event, number = 6, final = '~')
    }

    /**
     * Returns true when Herdr accepts [spec] as a key of `pane.send_keys`.
     *
     * This function follows `normalize_api_key_alias` in `src/app/api_helpers.rs` and `parse_key_combo` in
     * `src/config/keybinds.rs` of Herdr 0.9.5.
     */
    fun isSupportedName(spec: String): Boolean {
        val key = spec.trim()
        if (key in KEY_ALIASES) return true
        if (key.endsWith("++")) return isModifierCombo(key.dropLast(2))
        var keyPart: String? = null
        for (part in key.split('+')) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed.lowercase() in MODIFIERS) continue
            if (keyPart != null) return false
            keyPart = trimmed
        }
        val name = keyPart ?: return false
        val lower = name.lowercase()
        return lower in KEY_NAMES ||
            name.codePointCount(0, name.length) == 1 ||
            (lower.startsWith('f') && lower.substring(1).toUByteOrNull() != null)
    }

    /** Returns true when each `+` part of [combo] is a modifier token, as `parse_modifier_combo` requires. */
    private fun isModifierCombo(combo: String): Boolean =
        combo.split('+').all { it.trim().lowercase() in MODIFIERS }

    private fun named(event: ClientKeyEvent, base: String): HerdrKeyInput.Named? {
        val spec = buildString {
            if (event.ctrl) append("ctrl+")
            if (event.alt) append("alt+")
            if (event.shift && base.length > 1) append("shift+")
            append(base)
        }
        return if (isSupportedName(spec)) HerdrKeyInput.Named(spec) else null
    }

    /**
     * Returns the xterm bytes of a key: `ESC [ number final` with no modifier, and `ESC [ number ; modifier final` with
     * a modifier. Home and End have no number when they have no modifier.
     */
    private fun xtermKey(event: ClientKeyEvent, number: Int, final: Char): HerdrKeyInput.Raw {
        val modifier = 1 +
            (if (event.shift) 1 else 0) +
            (if (event.alt) 2 else 0) +
            (if (event.ctrl) 4 else 0)
        val params = when {
            modifier > 1 -> "$number;$modifier"
            final == '~' -> "$number"
            else -> ""
        }
        return HerdrKeyInput.Raw("$ESC[$params$final")
    }

    private const val ESC = '\u001b'

    /** The keys of `normalize_api_key_alias`. */
    private val KEY_ALIASES = setOf("C-c", "c-c", "+")

    /** The modifier tokens of `parse_modifier_token`. */
    private val MODIFIERS = setOf(
        "ctrl", "control", "shift", "alt", "option", "meta", "cmd", "command", "super", "hyper",
    )

    /** The key names of `parse_key_combo`, in lower case. */
    private val KEY_NAMES = setOf(
        "space", "enter", "return", "esc", "escape", "tab", "backspace", "bs", "left", "right", "up", "down",
        "minus", "comma", "period", "slash", "backslash", "quote", "double_quote", "double-quote", "semicolon",
        "colon", "percent", "ampersand", "backtick", "plus",
    )
}
