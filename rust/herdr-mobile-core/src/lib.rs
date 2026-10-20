//! Hybrid Rust JNI Core for BisonHerdr (`com.toxicbakery.app.bison`).
//!
//! Integrates:
//! - `mosh_ssp`: Clean-room Apache-2.0 Mosh UDP SSP client (`AES-128-OCB3` + `Instruction` Protobuf
//!   + predictive echo + zero-traffic background parking).
//! - `vt_synthesizer`: `ghostty_vt::Terminal` + `PaneLayoutSnapshot` -> `PaneSurfaceFrame` synthesizer.
//! - `endpoint_session`: Stateful Herdr Endpoint Generation 1 session client (`surface_delta`,
//!   `surface_reuse`, `surface_scroll`, `surface_interest` leases, and presentation fences).
//! - `ssh_client`: SSH2 bootstrap command builders, focus RPC routing, and `russh` session helpers.
//! - `jni_bridge`: Android/JVM JNI entrypoints.

pub mod endpoint_session;
pub mod jni_bridge;
pub mod mosh_ssp;
pub mod ssh_client;
pub mod vt_synthesizer;

pub use herdr_protocol as protocol;

#[cfg(test)]
mod e2e_tests {
    use herdr_protocol::{
        endpoint::{
            snapshot_message, EndpointClientHello, EndpointServerWelcome, ENDPOINT_HELLO_KIND,
            ENDPOINT_PROTOCOL_GENERATION, ENDPOINT_WELCOME_KIND, PRESENTATION_EFFECTS_READY_KIND,
            PRESENTATION_EFFECTS_SYNC_KIND,
        },
        read_message, surface_delta, surface_reuse, surface_scroll, write_message, ClientMessage,
        ClientShellSnapshot, FrameData, PaneLayoutPane, PaneLayoutRect, PaneLayoutSnapshot,
        PaneLayoutSplit, PaneLayoutSplitDirection, PaneSurfaceFrame, PaneSurfacePane,
        PaneSurfacePatch, PaneSurfacePatchRow, PaneSurfaceSplitDirection, ServerMessage,
        SurfaceRect, MAX_GRAPHICS_FRAME_SIZE, MODIFIER_UNDERLINED,
    };

    use crate::endpoint_session::{EndpointSession, EndpointSessionEvent};
    use crate::mosh_ssp::{
        open_ssp_datagram, parse_mosh_connect_line, seal_ssp_datagram, Aes128Ocb3,
        MoshClientEngine, PacketDirection, SspDatagram, SspInstruction, MOSH_PROTOCOL_VERSION,
    };
    use crate::ssh_client::{
        build_mosh_bootstrap_command, route_rpc_method, RpcChannelRoute,
    };
    use crate::vt_synthesizer::VtSynthesizer;

    #[test]
    fn e2e_mosh_bootstrap_ssp_lifecycle_predictive_echo_parking_roaming_and_vt_synthesis() {
        // 1. Build SSH Mosh bootstrap command and parse MOSH CONNECT banner
        let cmd = build_mosh_bootstrap_command(None, Some("60000:60100"), Some("workbox"));
        assert!(cmd.contains("HERDR_CONFIG_PATH"));
        assert!(cmd.contains("tab_bar_position = \"top\""));

        let ssh_banner = "MOSH CONNECT 60004 4k9X2vL8pQ1mN7rT5wY3zA\n";
        let connect_info = parse_mosh_connect_line(ssh_banner).expect("valid MOSH CONNECT");
        assert_eq!(connect_info.port, 60004);

        // 2. Initialize Mosh SSP client engine and VtSynthesizer (tab_count = 2 -> strips row 0)
        let mut client = MoshClientEngine::new(&connect_info.key_bytes, 40, true);
        let server_ocb = Aes128Ocb3::new(&connect_info.key_bytes);
        let mut synth = VtSynthesizer::new(40, 8, 2, 200).expect("init VtSynthesizer");
        assert_eq!(synth.pty_rows(), 9);

        let layout = PaneLayoutSnapshot {
            workspace_id: "w1".into(),
            tab_id: "w1:t1".into(),
            zoomed: false,
            area: PaneLayoutRect {
                x: 0,
                y: 0,
                width: 40,
                height: 8,
            },
            focused_pane_id: "w1:p1".into(),
            panes: vec![
                PaneLayoutPane {
                    pane_id: "w1:p1".into(),
                    focused: true,
                    rect: PaneLayoutRect {
                        x: 0,
                        y: 0,
                        width: 20,
                        height: 8,
                    },
                },
                PaneLayoutPane {
                    pane_id: "w1:p2".into(),
                    focused: false,
                    rect: PaneLayoutRect {
                        x: 20,
                        y: 0,
                        width: 20,
                        height: 8,
                    },
                },
            ],
            splits: vec![PaneLayoutSplit {
                id: "s0:root".into(),
                direction: PaneLayoutSplitDirection::Right,
                ratio: 0.5,
                rect: PaneLayoutRect {
                    x: 0,
                    y: 0,
                    width: 40,
                    height: 8,
                },
            }],
        };

        // 3. User types "git status" -> predictive echo overlays underlined cells before server ack
        client.predictive_echo.sync_cursor(0, 0, 40);
        let input_pkt = client
            .build_user_input_datagram(b"git status".to_vec(), Some("git status"), 100)
            .expect("build input datagram");
        assert_eq!(client.predictive_echo.pending_cells().len(), 10);

        let pre_ack_frame = synth
            .synthesize_frame(
                "boot-mosh-e2e",
                1,
                1,
                Some(&layout),
                Some(&client.predictive_echo),
            )
            .expect("synthesize pre-ack frame");
        let predicted_prefix: String = pre_ack_frame.frame.cells[0..10]
            .iter()
            .map(|c| c.symbol.as_str())
            .collect();
        assert_eq!(predicted_prefix, "git status");
        assert_ne!(
            pre_ack_frame.frame.cells[0].modifier & MODIFIER_UNDERLINED,
            0
        );

        // 4. Server decrypts client input and replies with ack_num = 1 and VT diff (row 1 = tab bar, row 2 = pane output)
        let received_by_server = open_ssp_datagram(&server_ocb, &input_pkt).expect("decrypt input");
        assert_eq!(received_by_server.direction, PacketDirection::ToServer);
        assert_eq!(received_by_server.instruction.new_num, Some(1));

        let vt_payload = b"\x1b[1;1H 1:main | 2:tests \x1b[2;1H$ git status --short";
        let server_reply = SspDatagram {
            seq: 1,
            direction: PacketDirection::ToClient,
            timestamp: 112,
            timestamp_reply: 100,
            instruction: SspInstruction {
                protocol_version: Some(MOSH_PROTOCOL_VERSION),
                old_num: Some(0),
                new_num: Some(1),
                ack_num: Some(1),
                throwaway_num: Some(0),
                diff: Some(vt_payload.to_vec()),
                chaff: None,
            },
        };
        let wire_reply = seal_ssp_datagram(&server_ocb, &server_reply).expect("seal server reply");
        let host_diff = client
            .receive_datagram(&wire_reply, 124)
            .expect("receive server reply")
            .expect("non-empty diff");
        assert_eq!(client.rtt_ms(), Some(24));
        assert!(client.predictive_echo.pending_cells().is_empty());

        synth.feed_vt_bytes(&host_diff);
        let acked_frame = synth
            .synthesize_frame(
                "boot-mosh-e2e",
                1,
                2,
                Some(&layout),
                Some(&client.predictive_echo),
            )
            .expect("synthesize acked frame");

        // Verify row 0 of synthesized frame has stripped the top tab bar and contains "$ git status --short"
        let row0_text: String = acked_frame.frame.cells[0..20]
            .iter()
            .map(|c| c.symbol.as_str())
            .collect();
        assert_eq!(row0_text, "$ git status --short");
        assert_eq!(acked_frame.panes.len(), 2);
        assert_eq!(acked_frame.splits.len(), 1);
        assert_eq!(
            acked_frame.splits[0].direction,
            PaneSurfaceSplitDirection::Horizontal
        );
        assert_eq!(acked_frame.splits[0].pos, 20);

        // 5. Zero-traffic background parking -> wakeup heartbeat -> IP roam
        assert!(client.set_parked(true, 200).unwrap().is_none());
        assert!(client.is_parked());
        let wakeup_pkt = client
            .set_parked(false, 350)
            .unwrap()
            .expect("wakeup heartbeat on unpark");
        assert!(!client.is_parked());
        let decoded_wakeup = open_ssp_datagram(&server_ocb, &wakeup_pkt).unwrap();
        assert_eq!(decoded_wakeup.instruction.ack_num, Some(1));

        let roam_pkt = client.build_heartbeat_datagram(400).unwrap();
        let decoded_roam = open_ssp_datagram(&server_ocb, &roam_pkt).unwrap();
        assert_eq!(decoded_roam.timestamp, 400);
        assert_eq!(
            route_rpc_method("workspace.focus", true, false),
            RpcChannelRoute::ApiBridgeExec
        );
    }

    #[test]
    fn e2e_ssh_endpoint_gen1_lifecycle_hello_welcome_snapshot_delta_reuse_scroll_lease_and_fence() {
        let mut session = EndpointSession::new(true);

        // 1. Encode EndpointClientHello and verify Generation 1 negotiation
        let hello_framed = EndpointSession::encode_client_hello(30, 12, 10, 20, true).unwrap();
        let hello_msg: ClientMessage =
            read_message(&mut hello_framed.as_slice(), MAX_GRAPHICS_FRAME_SIZE).unwrap();
        let ClientMessage::EndpointControl { kind, data } = hello_msg else {
            panic!("expected EndpointControl hello");
        };
        assert_eq!(kind, ENDPOINT_HELLO_KIND);
        let hello: EndpointClientHello = serde_json::from_str(&data).unwrap();
        assert_eq!(hello.generation, ENDPOINT_PROTOCOL_GENERATION);
        assert!(hello.supports_required_codecs());
        assert!(hello.surface_active);
        assert!(hello.surface_delta);
        assert!(hello.surface_reuse);
        assert!(hello.surface_scroll);

        // 2. Receive EndpointServerWelcome
        let welcome = EndpointServerWelcome::compatible(vec![
            "client_shell.surface.set".into(),
            "workspace.focus".into(),
            "layout.set_split_ratio".into(),
        ]);
        let mut welcome_framed = Vec::new();
        write_message(
            &mut welcome_framed,
            &ServerMessage::EndpointControl {
                kind: ENDPOINT_WELCOME_KIND.into(),
                data: serde_json::to_string(&welcome).unwrap(),
            },
        )
        .unwrap();
        let ev_welcome = session
            .decode_framed_server_message(&welcome_framed)
            .unwrap();
        assert_eq!(ev_welcome, EndpointSessionEvent::Welcome(welcome));
        assert!(session.welcome().is_some());

        // 3. Receive ClientShellSnapshot
        let snapshot = ClientShellSnapshot {
            boot_id: "boot-gen1".into(),
            revision: 1,
            config_diagnostic: None,
            product_announcement: None,
            update_available: None,
            update_install_command: "herdr update".into(),
            server_keybindings_toml: None,
            latest_release_notes_available: false,
            integration_updates_available: false,
            worktree_directory: "/home/ian/workspace".into(),
            release_notes: None,
            focused_workspace_id: Some("w1".into()),
            focused_tab_id: Some("w1:t1".into()),
            focused_pane_id: Some("w1:p1".into()),
            tab_bar_right: Vec::new(),
            tab_bar_right_separator: String::new(),
            agent_view_label: None,
            agent_order: Vec::new(),
            workspaces: Vec::new(),
            tabs: Vec::new(),
            panes: Vec::new(),
            agents: Vec::new(),
            commands: Vec::new(),
        };
        let mut snapshot_framed = Vec::new();
        write_message(&mut snapshot_framed, &snapshot_message(&snapshot).unwrap()).unwrap();
        let ev_snapshot = session
            .decode_framed_server_message(&snapshot_framed)
            .unwrap();
        assert_eq!(
            ev_snapshot,
            EndpointSessionEvent::Snapshot(Box::new(snapshot))
        );
        assert_eq!(session.boot_id(), Some("boot-gen1"));

        // 4. Receive initial PaneSurfaceFrame (rev 1), then surface_delta (rev 2), surface_reuse (rev 3), and surface_scroll (rev 4)
        let pane_rect = SurfaceRect {
            x: 2,
            y: 1,
            width: 20,
            height: 10,
        };
        let pane = PaneSurfacePane {
            pane_id: "w1:p1".into(),
            content_revision: 1,
            rect: pane_rect,
            inner_rect: pane_rect,
            scrollbar_rect: None,
            scroll: None,
            focused: true,
            mouse_reporting: false,
            sgr_pixel_mouse: false,
            alternate_screen_active: false,
            pixel_width: 0,
            pixel_height: 0,
        };
        let mut base_frame = FrameData::empty(30, 12);
        for row_idx in 0..pane_rect.height {
            let line = format!("row-{row_idx:02}-content-block");
            let start = usize::from(pane_rect.y + row_idx) * 30 + usize::from(pane_rect.x);
            for (ch_idx, ch) in line.chars().enumerate() {
                if ch_idx < usize::from(pane_rect.width) {
                    base_frame.cells[start + ch_idx].symbol = ch.to_string();
                }
            }
        }
        let surface_rev1 = PaneSurfaceFrame {
            boot_id: "boot-gen1".into(),
            projection_revision: 1,
            surface_revision: 1,
            frame: base_frame,
            panes: vec![pane.clone()],
            splits: Vec::new(),
            popup: None,
            graphics: Default::default(),
        };
        session
            .receive_server_message(ServerMessage::PaneSurface(surface_rev1.clone()))
            .unwrap();

        // 4a. surface_delta -> surface_revision = 2
        let mut surface_rev2 = surface_rev1.clone();
        surface_rev2.surface_revision = 2;
        surface_rev2.frame.cells[0].symbol = "Δ".into();
        let delta_msg = surface_delta::message(
            &surface_rev1,
            &mut ServerMessage::PaneSurface(surface_rev2.clone()),
        )
        .unwrap()
        .expect("surface_delta encoded");
        let ev_delta = session.receive_server_message(delta_msg).unwrap();
        assert_eq!(
            ev_delta,
            EndpointSessionEvent::Surface(Box::new(surface_rev2.clone()))
        );
        assert_eq!(session.latest_surface().unwrap().frame.cells[0].symbol, "Δ");

        // 4b. surface_reuse -> surface_revision = 3 (retains cells across projection_revision bump)
        let mut surface_rev3 = surface_rev2.clone();
        surface_rev3.projection_revision = 2;
        surface_rev3.surface_revision = 3;
        let reuse_msg = surface_reuse::message(2, &mut surface_rev3)
            .unwrap()
            .expect("surface_reuse encoded");
        let ev_reuse = session.receive_server_message(reuse_msg).unwrap();
        assert_eq!(
            ev_reuse,
            EndpointSessionEvent::Surface(Box::new(surface_rev3.clone()))
        );
        assert_eq!(session.latest_surface().unwrap().projection_revision, 2);
        assert_eq!(session.latest_surface().unwrap().surface_revision, 3);

        // 4c. surface_scroll -> surface_revision = 4 (scroll pane up by 1 row)
        let mut scrolled_frame = surface_rev3.frame.clone();
        for row_idx in 0..pane_rect.height {
            let line = format!("row-{:02}-content-block", row_idx + 1);
            let start = usize::from(pane_rect.y + row_idx) * 30 + usize::from(pane_rect.x);
            for x in 0..usize::from(pane_rect.width) {
                scrolled_frame.cells[start + x].symbol =
                    line.chars().nth(x).map_or(" ".into(), String::from);
            }
        }
        let mut patch_rows = Vec::new();
        for y in pane_rect.y..(pane_rect.y + pane_rect.height) {
            let start = usize::from(y) * 30 + usize::from(pane_rect.x);
            patch_rows.push(PaneSurfacePatchRow {
                x: pane_rect.x,
                y,
                cells: scrolled_frame.cells[start..start + usize::from(pane_rect.width)].to_vec(),
            });
        }
        let raw_scroll_patch = PaneSurfacePatch {
            boot_id: "boot-gen1".into(),
            projection_revision: 2,
            base_surface_revision: 3,
            surface_revision: 4,
            rows: patch_rows,
            panes: vec![pane],
            cursor: None,
        };
        let scroll_msg =
            surface_scroll::message(&surface_rev3, &raw_scroll_patch).expect("surface_scroll");
        let ev_scroll = session.receive_server_message(scroll_msg).unwrap();
        assert!(matches!(ev_scroll, EndpointSessionEvent::SurfacePatch(_)));
        assert_eq!(session.latest_surface().unwrap().surface_revision, 4);
        assert_eq!(
            session.latest_surface().unwrap().frame.cells,
            scrolled_frame.cells
        );

        // 5. Release surface lease on backgrounding (`client_shell.surface.set { active: false }`)
        let lease_off_bytes = session
            .encode_surface_lease_request("req-park", false)
            .unwrap();
        assert!(!session.is_surface_active());
        let decoded_lease_off: ClientMessage =
            read_message(&mut lease_off_bytes.as_slice(), MAX_GRAPHICS_FRAME_SIZE).unwrap();
        assert!(matches!(
            decoded_lease_off,
            ClientMessage::ClientShellEndpointRequest { boot_id, request }
                if boot_id == "boot-gen1"
                    && request.contains("\"method\":\"client_shell.surface.set\"")
                    && request.contains("\"active\":false")
        ));

        // 6. Presentation fence sync -> ready ack
        let sync_payload = r#"{"epoch":9,"boot_id":"boot-gen1"}"#;
        let ev_fence = session
            .receive_server_message(ServerMessage::EndpointControl {
                kind: PRESENTATION_EFFECTS_SYNC_KIND.into(),
                data: sync_payload.into(),
            })
            .unwrap();
        let EndpointSessionEvent::PresentationSync {
            sync_data,
            ready_frame_bytes,
        } = ev_fence
        else {
            panic!("expected PresentationSync event");
        };
        assert_eq!(sync_data, sync_payload);
        let decoded_ready: ClientMessage =
            read_message(&mut ready_frame_bytes.as_slice(), MAX_GRAPHICS_FRAME_SIZE).unwrap();
        assert_eq!(
            decoded_ready,
            ClientMessage::EndpointControl {
                kind: PRESENTATION_EFFECTS_READY_KIND.into(),
                data: sync_payload.into(),
            }
        );
    }
}
