package com.toxicbakery.app.bison.protocol

import kotlinx.serialization.Serializable

/**
 * Parsed UDP port and 128-bit AES-OCB session key from `mosh-server`'s `MOSH CONNECT <port> <key>` banner.
 */
@Serializable
data class MoshConnectInfo(
    val udpPort: Int,
    val sessionKey: String,
)

/**
 * Mosh UDP bootstrap parser, ephemeral tablet `HERDR_CONFIG_PATH` generator, and
 * sideband routing rules against unmodified upstream Herdr servers.
 */
object MoshBootstrapConfig {

    private val MOSH_CONNECT_REGEX = Regex("""MOSH\s+CONNECT\s+(\d{1,5})\s+([A-Za-z0-9/+_=.-]+)""")

    private val FOCUS_METHODS_REQUIRING_API_SOCKET_IN_MOSH_MODE = setOf(
        "workspace.focus",
        "tab.focus",
        "pane.focus",
        "pane.focus_direction",
        "agent.focus",
        "workspace.create",
        "worktree.create",
        "worktree.open",
        "tab.create",
    )

    private val CLIENT_SHELL_SUPPORTED_METHODS = setOf(
        "client_shell.surface.set",
        "command.invoke",
        "integration.install",
        "integration.list",
        "layout.set_split_ratio",
        "pane.clear",
        "pane.close",
        "pane.copy_motion",
        "pane.copy_search",
        "pane.edit_scrollback",
        "pane.focus",
        "pane.focus_direction",
        "pane.input.set",
        "pane.link.activate",
        "pane.link.resolve",
        "pane.rename",
        "pane.resize",
        "pane.scroll",
        "pane.selection.read",
        "pane.split",
        "pane.swap",
        "pane.zoom",
        "product_announcement.dismiss",
        "release_notes.dismiss",
        "server.reload_config",
        "tab.close",
        "tab.create",
        "tab.focus",
        "tab.move",
        "tab.rename",
        "workspace.close",
        "workspace.create",
        "workspace.focus",
        "workspace.move",
        "workspace.move_block",
        "workspace.rename",
        "worktree.create",
        "worktree.list",
        "worktree.open",
        "worktree.remove",
    )

    /**
     * Parses `MOSH CONNECT <udp_port> <base64_aes_ocb_key>` from `mosh-server` stdout.
     */
    fun parseMoshConnectLine(output: String): MoshConnectInfo? {
        for (line in output.lineSequence()) {
            val match = MOSH_CONNECT_REGEX.find(line.trim()) ?: continue
            val port = match.groupValues[1].toIntOrNull() ?: continue
            val key = match.groupValues[2].trim()
            if (port in 1..65535 && key.isNotEmpty()) {
                return MoshConnectInfo(udpPort = port, sessionKey = key)
            }
        }
        return null
    }

    /**
     * Builds the ephemeral tablet `config.toml` passed via `HERDR_CONFIG_PATH` when launching
     * `herdr` inside `mosh-server` so unmodified `herdr` hides its TUI chrome, sidebar, and
     * inner ASCII borders while Compose draws them. The Mosh path is planned (issue #22), and no code uses this
     * config in this version.
     */
    fun buildTabletHerdrConfigToml(): String =
        """
        onboarding = false

        [ui]
        sidebar_start_collapsed = true
        sidebar_collapsed_mode = "hidden"
        mobile_width_threshold = 0
        hide_tab_bar_when_single_tab = true
        tab_bar_position = "top"
        pane_borders = "off"
        pane_outer_borders = false
        pane_scrollbars = false
        pane_gaps = false
        """.trimIndent() + "\n"

    /**
     * Returns `true` when [method] is a focus/navigation RPC that must be routed over
     * `herdr remote-api-bridge` (`herdr.sock`) in Mosh mode so `focus_all_shell_clients_on_default_target()`
     * switches the Mosh `herdr` client's active workspace/tab/pane.
     */
    fun isFocusRpcRequiringApiSocketInMoshMode(method: String): Boolean =
        method in FOCUS_METHODS_REQUIRING_API_SOCKET_IN_MOSH_MODE

    /**
     * Returns `true` when [method] is permitted on `ClientMessage::ClientShellEndpointRequest` (`client_shell == true`).
     */
    fun isClientShellSupportedMethod(method: String): Boolean =
        method in CLIENT_SHELL_SUPPORTED_METHODS
}
