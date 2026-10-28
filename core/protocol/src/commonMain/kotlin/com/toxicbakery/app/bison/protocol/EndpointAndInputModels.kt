package com.toxicbakery.app.bison.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClientSurfaceSize(
    val cols: UShort,
    val rows: UShort,
)

/**
 * Client -> Server handshake envelope (`endpoint.hello.v1`).
 */
@Serializable
data class EndpointClientHello(
    val generation: UInt = 1u,
    @SerialName("cell_width_px") val cellWidthPx: UShort = 18u,
    @SerialName("cell_height_px") val cellHeightPx: UShort = 36u,
    @SerialName("surface_size") val surfaceSize: ClientSurfaceSize,
    @SerialName("pixel_mouse") val pixelMouse: Boolean = true,
    @SerialName("direct_graphics") val directGraphics: Boolean = false,
    @SerialName("endpoint_keybindings") val endpointKeybindings: Boolean = false,
    @SerialName("mouse_capture") val mouseCapture: Boolean = true,
    @SerialName("surface_active") val surfaceActive: Boolean = true,
    @SerialName("surface_reuse") val surfaceReuse: Boolean = true,
    @SerialName("surface_delta") val surfaceDelta: Boolean = true,
    @SerialName("surface_scroll") val surfaceScroll: Boolean = true,
    @SerialName("snapshot_codecs") val snapshotCodecs: List<String> = listOf("shell.snapshot.v1"),
    @SerialName("surface_codecs") val surfaceCodecs: List<String> = listOf("shell.surface.v1"),
    @SerialName("input_codecs") val inputCodecs: List<String> = listOf("shell.input.semantic.v1"),
    @SerialName("blob_codecs") val blobCodecs: List<String> = listOf("shell.blob.v1"),
)

/**
 * Server -> Client handshake response (`endpoint.welcome.v1`).
 */
@Serializable
data class EndpointServerWelcome(
    val generation: UInt = 1u,
    @SerialName("server_version") val serverVersion: String = "",
    @SerialName("snapshot_codec") val snapshotCodec: String = "shell.snapshot.v1",
    @SerialName("surface_codec") val surfaceCodec: String = "shell.surface.v1",
    @SerialName("input_codec") val inputCodec: String = "shell.input.semantic.v1",
    @SerialName("blob_codec") val blobCodec: String = "shell.blob.v1",
    val methods: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
)

/**
 * Semantic input events sent over `ClientShellPaneInput` (`shell.input.semantic.v1`).
 */
@Serializable
sealed interface ClientPaneInputEvent {
    @Serializable
    @SerialName("key")
    data class Key(val event: ClientKeyEvent) : ClientPaneInputEvent

    /**
     * Text that the user types, for example with the keyboard or a quick key. The SSH client sends it as raw bytes
     * (`pane.send_text`), so the program in the pane reads it as keys and never as a paste.
     */
    @Serializable
    @SerialName("text_commit")
    data class TextCommit(val text: String) : ClientPaneInputEvent

    @Serializable
    @SerialName("mouse")
    data class Mouse(val event: ClientMouseEvent) : ClientPaneInputEvent

    /**
     * Text that the user pastes. The SSH client sends it whole with `pane.send_input` and no keys. Herdr then puts the
     * text in a bracketed paste when the pane has bracketed paste on, as a terminal does. The client does not send a
     * paste whose request is over 1,000,000 bytes. No UI makes this event yet.
     */
    @Serializable
    @SerialName("paste")
    data class Paste(val text: String) : ClientPaneInputEvent
}

@Serializable
data class ClientKeyEvent(
    val code: ClientKeyCode,
    val kind: ClientKeyKind = ClientKeyKind.Press,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val superKey: Boolean = false,
)

@Serializable
enum class ClientKeyCode {
    @SerialName("enter")
    Enter,

    @SerialName("escape")
    Escape,

    @SerialName("tab")
    Tab,

    @SerialName("backspace")
    Backspace,

    @SerialName("up")
    Up,

    @SerialName("down")
    Down,

    @SerialName("left")
    Left,

    @SerialName("right")
    Right,

    @SerialName("home")
    Home,

    @SerialName("end")
    End,

    @SerialName("page_up")
    PageUp,

    @SerialName("page_down")
    PageDown,

    @SerialName("delete")
    Delete,

    @SerialName("insert")
    Insert,

    @SerialName("char_c")
    CharC,

    @SerialName("char_b")
    CharB,

    @SerialName("char_d")
    CharD,

    @SerialName("char_z")
    CharZ,

    @SerialName("char_y")
    CharY,

    @SerialName("char_n")
    CharN,
}

@Serializable
enum class ClientKeyKind {
    @SerialName("press")
    Press,

    @SerialName("repeat")
    Repeat,

    @SerialName("release")
    Release,
}

@Serializable
data class ClientMouseEvent(
    val kind: ClientMouseKind,
    val button: ClientMouseButton = ClientMouseButton.Left,
    val position: ClientMousePosition,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
)

@Serializable
enum class ClientMouseKind {
    @SerialName("down")
    Down,

    @SerialName("drag")
    Drag,

    @SerialName("up")
    Up,

    @SerialName("move")
    Move,

    @SerialName("scroll_up")
    ScrollUp,

    @SerialName("scroll_down")
    ScrollDown,
}

@Serializable
enum class ClientMouseButton {
    @SerialName("left")
    Left,

    @SerialName("middle")
    Middle,

    @SerialName("right")
    Right,

    @SerialName("none")
    None,
}

@Serializable
data class ClientMousePosition(
    val col: UShort,
    val row: UShort,
    @SerialName("pixel_x") val pixelX: UInt? = null,
    @SerialName("pixel_y") val pixelY: UInt? = null,
)

/**
 * Client-Shell command lane request envelope (`ClientMessage::ClientShellEndpointRequest`).
 */
@Serializable
data class ClientShellEndpointRequest(
    val id: String,
    val method: String,
    val paramsJson: String = "{}",
)

/**
 * Forward-compatible Endpoint Control envelope (`ClientMessage::EndpointControl` / `ServerMessage::EndpointControl`).
 */
@Serializable
data class EndpointControlEnvelope(
    val kind: String,
    val dataJson: String,
)
