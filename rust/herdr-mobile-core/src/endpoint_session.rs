//! Stateful Herdr Endpoint Generation 1 session client and tablet config generator.

use herdr_protocol::{
    endpoint::{
        EndpointAgentCompletions, EndpointAgentViewProjection, EndpointClientHello,
        EndpointServerWelcome, AGENT_COMPLETIONS_KIND, AGENT_VIEW_PROJECTION_KIND, BLOB_CODEC_V1,
        ENDPOINT_HELLO_KIND, ENDPOINT_PROTOCOL_GENERATION, ENDPOINT_SNAPSHOT_KIND,
        ENDPOINT_WELCOME_KIND, HEALTH_PING_KIND, HEALTH_PONG_KIND, INPUT_CODEC_V1,
        PRESENTATION_EFFECTS_READY_KIND, PRESENTATION_EFFECTS_SYNC_KIND, SNAPSHOT_CODEC_V1,
        SURFACE_CODEC_V1,
    },
    read_message, surface_reuse, write_message, ClientMessage, ClientShellSnapshot,
    ClientSurfaceSize, PaneSurfaceFrame, PaneSurfacePatch, SemanticNotification, ServerMessage,
    MAX_GRAPHICS_FRAME_SIZE,
};
use serde::{Deserialize, Serialize};

/// Ephemeral tablet `config.toml` passed via `HERDR_CONFIG_PATH` when launching `herdr` under
/// `mosh-server` so unmodified `herdr` hides its sidebar, mobile header, startup overlays,
/// and ASCII box-drawing borders/scrollbars without modifying the user's `~/.config/herdr/config.toml`.
pub const fn mosh_tablet_herdr_config_toml() -> &'static str {
    concat!(
        "onboarding = false\n",
        "\n",
        "[ui]\n",
        "sidebar_start_collapsed = true\n",
        "sidebar_collapsed_mode = \"hidden\"\n",
        "mobile_width_threshold = 0\n",
        "hide_tab_bar_when_single_tab = true\n",
        "tab_bar_position = \"top\"\n",
        "pane_borders = \"off\"\n",
        "pane_outer_borders = false\n",
        "pane_scrollbars = false\n",
        "pane_gaps = false\n",
    )
}

/// High-level decoded event produced by `EndpointSession::receive_server_message`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "event", content = "payload", rename_all = "snake_case")]
pub enum EndpointSessionEvent {
    Welcome(EndpointServerWelcome),
    Snapshot(Box<ClientShellSnapshot>),
    Surface(Box<PaneSurfaceFrame>),
    SurfacePatch(PaneSurfacePatch),
    SemanticNotification(SemanticNotification),
    PresentationSync {
        sync_data: String,
        ready_frame_bytes: Vec<u8>,
    },
    HealthPing {
        ping_data: String,
        pong_frame_bytes: Vec<u8>,
    },
    AgentCompletions(EndpointAgentCompletions),
    AgentViewProjection(EndpointAgentViewProjection),
    ResponseChunk {
        boot_id: String,
        request_id: String,
        final_chunk: bool,
        data: Vec<u8>,
    },
    ClientShellError {
        message: String,
    },
    ServerShutdown {
        reason: Option<String>,
    },
    IgnoredControl {
        kind: String,
    },
}

/// Stateful Endpoint Generation 1 session client.
pub struct EndpointSession {
    decoder: surface_reuse::Decoder,
    welcome: Option<EndpointServerWelcome>,
    latest_snapshot: Option<ClientShellSnapshot>,
    latest_surface: Option<PaneSurfaceFrame>,
    surface_active: bool,
}

impl Default for EndpointSession {
    fn default() -> Self {
        Self::new(true)
    }
}

impl EndpointSession {
    pub fn new(surface_active: bool) -> Self {
        Self {
            decoder: surface_reuse::Decoder::new(true, true),
            welcome: None,
            latest_snapshot: None,
            latest_surface: None,
            surface_active,
        }
    }

    pub fn welcome(&self) -> Option<&EndpointServerWelcome> {
        self.welcome.as_ref()
    }

    pub fn latest_snapshot(&self) -> Option<&ClientShellSnapshot> {
        self.latest_snapshot.as_ref()
    }

    pub fn latest_surface(&self) -> Option<&PaneSurfaceFrame> {
        self.latest_surface.as_ref()
    }

    pub fn is_surface_active(&self) -> bool {
        self.surface_active
    }

    pub fn boot_id(&self) -> Option<&str> {
        self.latest_snapshot
            .as_ref()
            .map(|s| s.boot_id.as_str())
            .or_else(|| self.latest_surface.as_ref().map(|s| s.boot_id.as_str()))
    }

    /// Builds a length-prefixed bincode `ClientMessage::EndpointControl` carrying `EndpointClientHello`.
    pub fn encode_client_hello(
        cols: u16,
        rows: u16,
        cell_width_px: u32,
        cell_height_px: u32,
        surface_active: bool,
    ) -> Result<Vec<u8>, String> {
        let hello = EndpointClientHello {
            generation: ENDPOINT_PROTOCOL_GENERATION,
            cell_width_px,
            cell_height_px,
            surface_size: ClientSurfaceSize { cols, rows },
            pixel_mouse: true,
            direct_graphics: false,
            endpoint_keybindings: false,
            mouse_capture: true,
            surface_active,
            surface_reuse: true,
            surface_delta: true,
            surface_scroll: true,
            snapshot_codecs: vec![SNAPSHOT_CODEC_V1.into()],
            surface_codecs: vec![SURFACE_CODEC_V1.into()],
            input_codecs: vec![INPUT_CODEC_V1.into()],
            blob_codecs: vec![BLOB_CODEC_V1.into()],
        };
        let data = serde_json::to_string(&hello).map_err(|e| e.to_string())?;
        let msg = ClientMessage::EndpointControl {
            kind: ENDPOINT_HELLO_KIND.into(),
            data,
        };
        let mut out = Vec::new();
        write_message(&mut out, &msg).map_err(|e| e.to_string())?;
        Ok(out)
    }

    /// Builds a `ClientMessage::ClientShellEndpointRequest` toggling `client_shell.surface.set`.
    ///
    /// Used on Android `onStop()` (`active: false`) for zero-bandwidth background parking and
    /// `onStart()` (`active: true`) for <50ms surface resume without SSH reconnect.
    pub fn encode_surface_lease_request(
        &mut self,
        request_id: &str,
        active: bool,
    ) -> Result<Vec<u8>, String> {
        let boot_id = self.boot_id().unwrap_or_default().to_owned();
        self.surface_active = active;
        let request = serde_json::json!({
            "id": request_id,
            "method": "client_shell.surface.set",
            "params": { "active": active }
        })
        .to_string();
        let msg = ClientMessage::ClientShellEndpointRequest { boot_id, request };
        let mut out = Vec::new();
        write_message(&mut out, &msg).map_err(|e| e.to_string())?;
        Ok(out)
    }

    /// Builds a length-prefixed `endpoint.presentation.ready.v1` fence acknowledgment.
    pub fn encode_presentation_ready(sync_data: &str) -> Result<Vec<u8>, String> {
        let msg = ClientMessage::EndpointControl {
            kind: PRESENTATION_EFFECTS_READY_KIND.into(),
            data: sync_data.to_owned(),
        };
        let mut out = Vec::new();
        write_message(&mut out, &msg).map_err(|e| e.to_string())?;
        Ok(out)
    }

    /// Builds a length-prefixed `endpoint.health.pong.v1` response.
    pub fn encode_health_pong(ping_data: &str) -> Result<Vec<u8>, String> {
        let msg = ClientMessage::EndpointControl {
            kind: HEALTH_PONG_KIND.into(),
            data: ping_data.to_owned(),
        };
        let mut out = Vec::new();
        write_message(&mut out, &msg).map_err(|e| e.to_string())?;
        Ok(out)
    }

    /// Decodes a framed `[u32LE length][bincode ServerMessage]` byte slice.
    pub fn decode_framed_server_message(
        &mut self,
        mut framed_bytes: &[u8],
    ) -> Result<EndpointSessionEvent, String> {
        let raw_msg: ServerMessage =
            read_message(&mut framed_bytes, MAX_GRAPHICS_FRAME_SIZE).map_err(|e| e.to_string())?;
        self.receive_server_message(raw_msg)
    }

    /// Processes a `ServerMessage`, running `surface_reuse::Decoder` for `surface_delta`,
    /// `surface_reuse`, and `surface_scroll` frames and updating retained session state.
    pub fn receive_server_message(
        &mut self,
        message: ServerMessage,
    ) -> Result<EndpointSessionEvent, String> {
        let decoded = self.decoder.decode(message)?;
        match decoded {
            ServerMessage::EndpointControl { kind, data } => match kind.as_str() {
                ENDPOINT_WELCOME_KIND => {
                    let welcome: EndpointServerWelcome =
                        serde_json::from_str(&data).map_err(|e| e.to_string())?;
                    self.welcome = Some(welcome.clone());
                    Ok(EndpointSessionEvent::Welcome(welcome))
                }
                ENDPOINT_SNAPSHOT_KIND => {
                    let snapshot: ClientShellSnapshot =
                        serde_json::from_str(&data).map_err(|e| e.to_string())?;
                    self.latest_snapshot = Some(snapshot.clone());
                    Ok(EndpointSessionEvent::Snapshot(Box::new(snapshot)))
                }
                PRESENTATION_EFFECTS_SYNC_KIND => {
                    let ready_frame_bytes = Self::encode_presentation_ready(&data)?;
                    Ok(EndpointSessionEvent::PresentationSync {
                        sync_data: data,
                        ready_frame_bytes,
                    })
                }
                HEALTH_PING_KIND => {
                    let pong_frame_bytes = Self::encode_health_pong(&data)?;
                    Ok(EndpointSessionEvent::HealthPing {
                        ping_data: data,
                        pong_frame_bytes,
                    })
                }
                AGENT_COMPLETIONS_KIND => {
                    let completions: EndpointAgentCompletions =
                        serde_json::from_str(&data).map_err(|e| e.to_string())?;
                    Ok(EndpointSessionEvent::AgentCompletions(completions))
                }
                AGENT_VIEW_PROJECTION_KIND => {
                    let projection: EndpointAgentViewProjection =
                        serde_json::from_str(&data).map_err(|e| e.to_string())?;
                    Ok(EndpointSessionEvent::AgentViewProjection(projection))
                }
                _ => Ok(EndpointSessionEvent::IgnoredControl { kind }),
            },
            ServerMessage::ClientShellSnapshot(snapshot) => {
                self.latest_snapshot = Some((*snapshot).clone());
                Ok(EndpointSessionEvent::Snapshot(snapshot))
            }
            ServerMessage::PaneSurface(surface) => {
                self.latest_surface = Some(surface.clone());
                Ok(EndpointSessionEvent::Surface(Box::new(surface)))
            }
            ServerMessage::PaneSurfacePatch(patch) => {
                if let Some(surface) = &mut self.latest_surface {
                    if surface.boot_id == patch.boot_id
                        && surface.projection_revision == patch.projection_revision
                        && surface.surface_revision == patch.base_surface_revision
                    {
                        for row in &patch.rows {
                            let start = usize::from(row.y) * usize::from(surface.frame.width)
                                + usize::from(row.x);
                            let end = start.saturating_add(row.cells.len());
                            if end <= surface.frame.cells.len() {
                                surface.frame.cells[start..end].clone_from_slice(&row.cells);
                            }
                        }
                        surface.surface_revision = patch.surface_revision;
                        surface.frame.cursor.clone_from(&patch.cursor);
                        surface.panes.clone_from(&patch.panes);
                    }
                }
                Ok(EndpointSessionEvent::SurfacePatch(patch))
            }
            ServerMessage::SemanticNotification(notification) => {
                Ok(EndpointSessionEvent::SemanticNotification(notification))
            }
            ServerMessage::ClientShellEndpointResponseChunk {
                boot_id,
                request_id,
                final_chunk,
                data,
            } => Ok(EndpointSessionEvent::ResponseChunk {
                boot_id,
                request_id,
                final_chunk,
                data,
            }),
            ServerMessage::ClientShellError { message } => {
                Ok(EndpointSessionEvent::ClientShellError { message })
            }
            ServerMessage::ServerShutdown { reason } => {
                Ok(EndpointSessionEvent::ServerShutdown { reason })
            }
            other => Ok(EndpointSessionEvent::IgnoredControl {
                kind: format!("{other:?}"),
            }),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use herdr_protocol::{surface_delta, FrameData, PaneSurfacePatchRow};

    #[test]
    fn tablet_config_toml_disables_all_server_chrome_and_borders() {
        let cfg = mosh_tablet_herdr_config_toml();
        assert!(cfg.contains("onboarding = false"));
        assert!(cfg.contains("sidebar_start_collapsed = true"));
        assert!(cfg.contains("sidebar_collapsed_mode = \"hidden\""));
        assert!(cfg.contains("mobile_width_threshold = 0"));
        assert!(cfg.contains("hide_tab_bar_when_single_tab = true"));
        assert!(cfg.contains("tab_bar_position = \"top\""));
        assert!(cfg.contains("pane_borders = \"off\""));
        assert!(cfg.contains("pane_outer_borders = false"));
        assert!(cfg.contains("pane_scrollbars = false"));
        assert!(cfg.contains("pane_gaps = false"));
    }

    #[test]
    fn endpoint_session_decodes_surface_delta_and_builds_lease_and_fence_frames() {
        let mut session = EndpointSession::new(true);

        let hello_bytes = EndpointSession::encode_client_hello(120, 40, 12, 24, true).unwrap();
        let decoded_hello: ClientMessage =
            read_message(&mut hello_bytes.as_slice(), MAX_GRAPHICS_FRAME_SIZE).unwrap();
        assert!(matches!(
            decoded_hello,
            ClientMessage::EndpointControl { kind, .. } if kind == ENDPOINT_HELLO_KIND
        ));

        let base_surface = PaneSurfaceFrame {
            boot_id: "boot-1".into(),
            projection_revision: 1,
            surface_revision: 1,
            frame: FrameData::empty(10, 4),
            panes: Vec::new(),
            splits: Vec::new(),
            popup: None,
            graphics: Default::default(),
        };
        let ev1 = session
            .receive_server_message(ServerMessage::PaneSurface(base_surface.clone()))
            .unwrap();
        assert!(matches!(ev1, EndpointSessionEvent::Surface(_)));

        // Encode a surface_delta frame and verify EndpointSession reconstructs the full surface
        let mut next_surface = base_surface.clone();
        next_surface.surface_revision = 2;
        next_surface.frame.cells[0].symbol = "Z".into();
        let delta_msg = surface_delta::message(
            &base_surface,
            &mut ServerMessage::PaneSurface(next_surface.clone()),
        )
        .unwrap()
        .expect("delta message");

        let ev2 = session.receive_server_message(delta_msg).unwrap();
        assert_eq!(ev2, EndpointSessionEvent::Surface(Box::new(next_surface)));
        assert_eq!(session.latest_surface().unwrap().frame.cells[0].symbol, "Z");

        // Apply a patch and verify retained surface updates
        let patch = PaneSurfacePatch {
            boot_id: "boot-1".into(),
            projection_revision: 1,
            base_surface_revision: 2,
            surface_revision: 3,
            rows: vec![PaneSurfacePatchRow {
                x: 1,
                y: 0,
                cells: vec![herdr_protocol::CellData {
                    symbol: "Q".into(),
                    ..Default::default()
                }],
            }],
            panes: Vec::new(),
            cursor: None,
        };
        session
            .receive_server_message(ServerMessage::PaneSurfacePatch(patch))
            .unwrap();
        assert_eq!(session.latest_surface().unwrap().frame.cells[1].symbol, "Q");

        // Test presentation sync fence response
        let sync_ev = session
            .receive_server_message(ServerMessage::EndpointControl {
                kind: PRESENTATION_EFFECTS_SYNC_KIND.into(),
                data: r#"{"epoch":4}"#.into(),
            })
            .unwrap();
        assert!(matches!(
            sync_ev,
            EndpointSessionEvent::PresentationSync { .. }
        ));

        // Test surface lease request builder
        let lease_bytes = session
            .encode_surface_lease_request("lease-1", false)
            .unwrap();
        assert!(!session.is_surface_active());
        let lease_msg: ClientMessage =
            read_message(&mut lease_bytes.as_slice(), MAX_GRAPHICS_FRAME_SIZE).unwrap();
        assert!(matches!(
            lease_msg,
            ClientMessage::ClientShellEndpointRequest { boot_id, request }
                if boot_id == "boot-1" && request.contains("client_shell.surface.set")
        ));
    }
}
