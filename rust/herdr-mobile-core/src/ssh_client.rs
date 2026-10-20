//! SSH2 bootstrap command builders, RPC channel routing rules, and `russh` session helpers.

use std::sync::Arc;

use russh::client;
use russh::keys::ssh_key;
use serde::{Deserialize, Serialize};

use crate::endpoint_session::mosh_tablet_herdr_config_toml;

/// Transport mode preference per `HostMachine`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TransportMode {
    #[default]
    AutoPreferMosh,
    MoshOnly,
    SshEndpointOnly,
}

/// Which control channel should carry a given Herdr JSON-RPC request.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RpcChannelRoute {
    /// Send over the persistent `herdr remote-client-bridge` socket via `ClientShellEndpointRequest`.
    ClientShellLane,
    /// Open a lightweight multiplexed SSH2 `exec` channel running `herdr remote-api-bridge` (`herdr.sock`).
    ApiBridgeExec,
}

/// Methods permitted on `ClientShellEndpointRequest` (`client_shell: true` in Herdr's `define_operations!`).
pub const CLIENT_SHELL_PERMITTED_METHODS: &[&str] = &[
    "server.reload_config",
    "product_announcement.dismiss",
    "release_notes.dismiss",
    "command.invoke",
    "client_shell.surface.set",
    "workspace.create",
    "workspace.focus",
    "workspace.rename",
    "workspace.move",
    "workspace.move_block",
    "workspace.close",
    "worktree.list",
    "worktree.create",
    "worktree.open",
    "worktree.remove",
    "tab.create",
    "tab.focus",
    "tab.rename",
    "tab.move",
    "tab.close",
    "layout.set_split_ratio",
    "pane.focus",
    "pane.focus_direction",
    "pane.split",
    "pane.swap",
    "pane.zoom",
    "pane.resize",
    "pane.scroll",
    "pane.clear",
    "pane.edit_scrollback",
    "pane.selection.read",
    "pane.copy_motion",
    "pane.copy_search",
    "pane.input.set",
    "pane.link.resolve",
    "pane.link.activate",
    "pane.rename",
    "pane.close",
    "integration.list",
    "integration.install",
];

/// Methods that change workspace/tab/pane focus and MUST be routed over `herdr.sock`
/// (`herdr remote-api-bridge`) when Mosh UDP is the active terminal backbone so that
/// `focus_all_shell_clients_on_default_target()` switches the Mosh `herdr` client's view.
pub const MOSH_FOCUS_SYNC_METHODS: &[&str] = &[
    "workspace.focus",
    "tab.focus",
    "pane.focus",
    "agent.focus",
    "workspace.create",
    "tab.create",
];

/// Determines the optimal channel route for a Herdr JSON-RPC method against an unmodified server.
pub fn route_rpc_method(method: &str, mosh_active: bool, surface_active: bool) -> RpcChannelRoute {
    if method == "client_shell.surface.set" {
        return RpcChannelRoute::ClientShellLane;
    }
    if mosh_active && MOSH_FOCUS_SYNC_METHODS.contains(&method) {
        return RpcChannelRoute::ApiBridgeExec;
    }
    if !surface_active {
        return RpcChannelRoute::ApiBridgeExec;
    }
    if CLIENT_SHELL_PERMITTED_METHODS.contains(&method) {
        RpcChannelRoute::ClientShellLane
    } else {
        RpcChannelRoute::ApiBridgeExec
    }
}

/// Builds the remote shell bootstrap command that writes the ephemeral tablet `config.toml`
/// and launches `mosh-server new` attached to `herdr`.
pub fn build_mosh_bootstrap_command(
    mosh_server_bin: Option<&str>,
    udp_port_range: Option<&str>,
    session_name: Option<&str>,
) -> String {
    let mosh_bin = mosh_server_bin
        .filter(|s| !s.trim().is_empty())
        .unwrap_or("mosh-server");
    let port_flag = udp_port_range
        .filter(|s| !s.trim().is_empty())
        .map(|range| format!(" -p {range}"))
        .unwrap_or_default();
    let session_arg = session_name
        .filter(|s| !s.trim().is_empty())
        .map(|name| format!(" --session {name}"))
        .unwrap_or_default();
    let config_toml = mosh_tablet_herdr_config_toml();

    format!(
        "sh -c 'command -v {mosh_bin} >/dev/null 2>&1 || exit 127; \
         CFG=\"${{XDG_RUNTIME_DIR:-/tmp}}/bison-herdr-tablet-$$.toml\"; \
         cat <<\"EOF_BISON_CFG\" > \"$CFG\"\n{config_toml}EOF_BISON_CFG\n\
         exec {mosh_bin} new -s -c 256 -l LANG=en_US.UTF-8{port_flag} -- \
         env HERDR_CONFIG_PATH=\"$CFG\" herdr{session_arg}'"
    )
}

/// Builds the SSH exec command for the persistent Endpoint Generation 1 control/fallback bridge.
pub fn build_remote_client_bridge_command(session_name: Option<&str>) -> String {
    match session_name.filter(|s| !s.trim().is_empty()) {
        Some(name) => format!("herdr --session {name} remote-client-bridge --idle-timeout-v1"),
        None => "herdr remote-client-bridge --idle-timeout-v1".to_owned(),
    }
}

/// Builds the SSH exec command for multiplexed `herdr.sock` JSON-RPC and `events.subscribe` streams.
pub fn build_remote_api_bridge_command(session_name: Option<&str>) -> String {
    match session_name.filter(|s| !s.trim().is_empty()) {
        Some(name) => format!("herdr --session {name} remote-api-bridge"),
        None => "herdr remote-api-bridge".to_owned(),
    }
}

/// `russh` client handler for SSH2 connections and host key verification.
pub struct BisonSshClientHandler {
    pub expected_host_key_fingerprint: Option<String>,
}

#[async_trait::async_trait]
impl client::Handler for BisonSshClientHandler {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        server_public_key: &ssh_key::PublicKey,
    ) -> Result<bool, Self::Error> {
        if let Some(expected) = &self.expected_host_key_fingerprint {
            let actual = server_public_key
                .fingerprint(ssh_key::HashAlg::Sha256)
                .to_string();
            Ok(&actual == expected)
        } else {
            Ok(true)
        }
    }
}

/// Creates a default `russh::client::Config` tuned for mobile roaming and SSH2 channel multiplexing.
pub fn default_russh_client_config() -> Arc<client::Config> {
    let config = client::Config {
        inactivity_timeout: Some(std::time::Duration::from_secs(60)),
        keepalive_interval: Some(std::time::Duration::from_secs(15)),
        keepalive_max: 3,
        ..Default::default()
    };
    Arc::new(config)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn routes_focus_rpcs_over_api_bridge_when_mosh_is_active() {
        for method in [
            "workspace.focus",
            "tab.focus",
            "pane.focus",
            "agent.focus",
            "workspace.create",
            "tab.create",
        ] {
            assert_eq!(
                route_rpc_method(method, true, false),
                RpcChannelRoute::ApiBridgeExec,
                "{method} must route over herdr.sock in Mosh mode"
            );
        }
        // In SSH Endpoint mode with active surface, workspace.focus can use the ClientShellLane
        assert_eq!(
            route_rpc_method("workspace.focus", false, true),
            RpcChannelRoute::ClientShellLane
        );
        // Non-client_shell methods always use ApiBridgeExec
        for method in [
            "agent.read",
            "pane.read",
            "agent.prompt",
            "pane.send_input",
            "pane.layout",
            "events.subscribe",
        ] {
            assert_eq!(
                route_rpc_method(method, false, true),
                RpcChannelRoute::ApiBridgeExec
            );
        }
        // client_shell.surface.set always uses ClientShellLane even when parked
        assert_eq!(
            route_rpc_method("client_shell.surface.set", false, false),
            RpcChannelRoute::ClientShellLane
        );
    }

    #[test]
    fn builds_mosh_and_bridge_commands_with_tablet_config() {
        let cmd = build_mosh_bootstrap_command(None, Some("60000:60100"), Some("dev"));
        assert!(cmd.contains("mosh-server new -s -c 256 -l LANG=en_US.UTF-8 -p 60000:60100"));
        assert!(cmd.contains("HERDR_CONFIG_PATH=\"$CFG\" herdr --session dev"));
        assert!(cmd.contains("pane_borders = \"off\""));

        assert_eq!(
            build_remote_client_bridge_command(None),
            "herdr remote-client-bridge --idle-timeout-v1"
        );
        assert_eq!(
            build_remote_api_bridge_command(Some("work")),
            "herdr --session work remote-api-bridge"
        );
    }
}
