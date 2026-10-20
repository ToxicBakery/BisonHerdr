//! JNI entrypoints for `:core:protocol` and `:platform:android:ssh` under `com.toxicbakery.app.bison`.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jint, jstring, JNI_TRUE};
use jni::JNIEnv;

use herdr_protocol::{derive_splits_from_layout, PaneLayoutSnapshot};

use crate::endpoint_session::{mosh_tablet_herdr_config_toml, EndpointSession};
use crate::mosh_ssp::parse_mosh_connect_line;
use crate::ssh_client::{
    build_mosh_bootstrap_command, build_remote_api_bridge_command,
    build_remote_client_bridge_command, route_rpc_method, RpcChannelRoute,
};
use crate::vt_synthesizer::{pty_rows_for_viewport, VtSynthesizer};

const DEFAULT_SCROLLBACK_LINES: usize = 2000;
const DEFAULT_CELL_WIDTH_PX: u32 = 10;
const DEFAULT_CELL_HEIGHT_PX: u32 = 20;

type SynthesizerCacheMap = HashMap<u32, (u16, u16, VtSynthesizer)>;

#[allow(clippy::type_complexity)]
static SYNTHESIZERS: OnceLock<Mutex<HashMap<u32, (u16, u16, VtSynthesizer)>>> = OnceLock::new();

fn synthesizer_cache() -> &'static Mutex<SynthesizerCacheMap> {
    SYNTHESIZERS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn pane_cache_key_from_layout(layout: Option<&PaneLayoutSnapshot>) -> u32 {
    let Some(snapshot) = layout else {
        return 0;
    };
    let key_str = if !snapshot.focused_pane_id.is_empty() {
        snapshot.focused_pane_id.as_str()
    } else if !snapshot.tab_id.is_empty() {
        snapshot.tab_id.as_str()
    } else {
        snapshot.workspace_id.as_str()
    };
    if key_str.is_empty() {
        return 0;
    }
    if let Ok(parsed) = key_str.parse::<u32>() {
        return parsed;
    }
    let mut hash = 0x811c_9dc5u32;
    for byte in key_str.as_bytes() {
        hash ^= u32::from(*byte);
        hash = hash.wrapping_mul(0x0100_0193);
    }
    hash
}

/// Synthesizes a `PaneSurfaceFrame` JSON using the thread-safe `VtSynthesizer` cache keyed by `pane_id`.
///
/// Reuses the cached `libghostty-vt` terminal instance across frames so terminal state is preserved,
/// resizing or recreating only when `(cols, rows)` (or `tab_count` PTY height) changes.
pub fn synthesize_mosh_frame_cached(
    pane_id: u32,
    cols: u16,
    rows: u16,
    tab_count: usize,
    vt_bytes: &[u8],
    layout: Option<&PaneLayoutSnapshot>,
) -> Result<String, String> {
    let frame = {
        let mut cache = synthesizer_cache()
            .lock()
            .map_err(|_| "VtSynthesizer cache mutex poisoned".to_owned())?;

        if let Some((cached_cols, cached_rows, synth)) = cache.get_mut(&pane_id) {
            let expected_pty_rows = pty_rows_for_viewport(rows, tab_count);
            if *cached_cols != cols
                || *cached_rows != rows
                || synth.pty_rows() != expected_pty_rows
            {
                if synth
                    .resize(
                        cols,
                        rows,
                        tab_count,
                        DEFAULT_CELL_WIDTH_PX,
                        DEFAULT_CELL_HEIGHT_PX,
                    )
                    .is_err()
                {
                    *synth = VtSynthesizer::new(cols, rows, tab_count, DEFAULT_SCROLLBACK_LINES)?;
                }
                *cached_cols = cols;
                *cached_rows = rows;
            }
        } else {
            let synth = VtSynthesizer::new(cols, rows, tab_count, DEFAULT_SCROLLBACK_LINES)?;
            cache.insert(pane_id, (cols, rows, synth));
        }

        let (_, _, synth) = cache
            .get_mut(&pane_id)
            .ok_or_else(|| "missing cached VtSynthesizer".to_owned())?;
        if !vt_bytes.is_empty() {
            synth.feed_vt_bytes(vt_bytes);
        }
        synth.synthesize_frame("mosh", 1, 1, layout, None)?
    };

    serde_json::to_string(&frame).map_err(|e| e.to_string())
}

fn jstring_to_rust(env: &mut JNIEnv<'_>, input: &JString<'_>) -> Result<String, String> {
    if input.is_null() {
        return Ok(String::new());
    }
    env.get_string(input)
        .map(Into::into)
        .map_err(|e| e.to_string())
}

fn rust_to_jstring(env: &mut JNIEnv<'_>, output: &str) -> jstring {
    match env.new_string(output) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Returns the ephemeral tablet `config.toml` passed to `HERDR_CONFIG_PATH` in Mosh mode.
#[no_mangle]
pub extern "system" fn Java_com_toxicbakery_app_bison_protocol_HerdrNativeBridge_nativeTabletConfigToml(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    rust_to_jstring(&mut env, mosh_tablet_herdr_config_toml())
}

/// Parses `MOSH CONNECT <port> <key>` from SSH output and returns JSON `{"port":...,"key_base64":...}`.
#[no_mangle]
pub extern "system" fn Java_com_toxicbakery_app_bison_protocol_HerdrNativeBridge_nativeParseMoshConnectJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    ssh_output: JString<'_>,
) -> jstring {
    let result = jstring_to_rust(&mut env, &ssh_output)
        .and_then(|text| parse_mosh_connect_line(&text))
        .and_then(|info| serde_json::to_string(&info).map_err(|e| e.to_string()));
    match result {
        Ok(json) => rust_to_jstring(&mut env, &json),
        Err(err) => {
            let err_json = serde_json::json!({ "error": err }).to_string();
            rust_to_jstring(&mut env, &err_json)
        }
    }
}

/// Derives `Vec<PaneSurfaceSplit>` JSON from a `PaneLayoutSnapshot` JSON payload.
#[no_mangle]
pub extern "system" fn Java_com_toxicbakery_app_bison_protocol_HerdrNativeBridge_nativeDeriveLayoutSplitsJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    layout_snapshot_json: JString<'_>,
) -> jstring {
    let result = jstring_to_rust(&mut env, &layout_snapshot_json)
        .and_then(|text| {
            serde_json::from_str::<PaneLayoutSnapshot>(&text).map_err(|e| e.to_string())
        })
        .and_then(|snapshot| {
            let splits = derive_splits_from_layout(&snapshot);
            serde_json::to_string(&splits).map_err(|e| e.to_string())
        });
    match result {
        Ok(json) => rust_to_jstring(&mut env, &json),
        Err(_) => rust_to_jstring(&mut env, "[]"),
    }
}

/// Synthesizes a `PaneSurfaceFrame` JSON from raw VT bytes and an optional `PaneLayoutSnapshot` JSON.
#[no_mangle]
pub extern "system" fn Java_com_toxicbakery_app_bison_protocol_HerdrNativeBridge_nativeSynthesizeMoshFrameJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    cols: jint,
    viewport_rows: jint,
    tab_count: jint,
    vt_bytes: JByteArray<'_>,
    layout_snapshot_json: JString<'_>,
) -> jstring {
    let cols_u16 = u16::try_from(cols.max(1)).unwrap_or(80);
    let rows_u16 = u16::try_from(viewport_rows.max(1)).unwrap_or(24);
    let tabs_usize = usize::try_from(tab_count.max(1)).unwrap_or(1);

    let bytes = env.convert_byte_array(&vt_bytes).unwrap_or_default();
    let layout = jstring_to_rust(&mut env, &layout_snapshot_json)
        .ok()
        .filter(|s| !s.trim().is_empty())
        .and_then(|s| serde_json::from_str::<PaneLayoutSnapshot>(&s).ok());

    let pane_id = pane_cache_key_from_layout(layout.as_ref());
    let result = synthesize_mosh_frame_cached(
        pane_id,
        cols_u16,
        rows_u16,
        tabs_usize,
        &bytes,
        layout.as_ref(),
    );

    match result {
        Ok(json) => rust_to_jstring(&mut env, &json),
        Err(err) => {
            let err_json = serde_json::json!({ "error": err }).to_string();
            rust_to_jstring(&mut env, &err_json)
        }
    }
}

/// Encodes an `EndpointClientHello` frame (`[u32LE length][bincode ClientMessage]`).
#[no_mangle]
pub extern "system" fn Java_com_toxicbakery_app_bison_protocol_HerdrNativeBridge_nativeEncodeClientHelloFrame<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    cols: jint,
    rows: jint,
    cell_width_px: jint,
    cell_height_px: jint,
    surface_active: jboolean,
) -> JByteArray<'local> {
    let bytes = EndpointSession::encode_client_hello(
        u16::try_from(cols.max(1)).unwrap_or(80),
        u16::try_from(rows.max(1)).unwrap_or(24),
        u32::try_from(cell_width_px.max(1)).unwrap_or(10),
        u32::try_from(cell_height_px.max(1)).unwrap_or(20),
        surface_active == JNI_TRUE,
    )
    .unwrap_or_default();
    env.byte_array_from_slice(&bytes).unwrap_or_default()
}

/// Routes a JSON-RPC method name to `"client_shell_lane"` or `"api_bridge_exec"`.
#[no_mangle]
pub extern "system" fn Java_com_toxicbakery_app_bison_protocol_HerdrNativeBridge_nativeRouteRpcMethod(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    method: JString<'_>,
    mosh_active: jboolean,
    surface_active: jboolean,
) -> jstring {
    let method_str = jstring_to_rust(&mut env, &method).unwrap_or_default();
    let route = route_rpc_method(
        &method_str,
        mosh_active == JNI_TRUE,
        surface_active == JNI_TRUE,
    );
    let label = match route {
        RpcChannelRoute::ClientShellLane => "client_shell_lane",
        RpcChannelRoute::ApiBridgeExec => "api_bridge_exec",
    };
    rust_to_jstring(&mut env, label)
}

/// Builds the Mosh SSH bootstrap command line.
#[no_mangle]
pub extern "system" fn Java_com_toxicbakery_app_bison_protocol_HerdrNativeBridge_nativeBuildMoshBootstrapCommand(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    mosh_server_bin: JString<'_>,
    udp_port_range: JString<'_>,
    session_name: JString<'_>,
) -> jstring {
    let bin = jstring_to_rust(&mut env, &mosh_server_bin).ok();
    let range = jstring_to_rust(&mut env, &udp_port_range).ok();
    let session = jstring_to_rust(&mut env, &session_name).ok();
    let cmd = build_mosh_bootstrap_command(bin.as_deref(), range.as_deref(), session.as_deref());
    rust_to_jstring(&mut env, &cmd)
}

/// Builds the `herdr remote-client-bridge --idle-timeout-v1` command line.
#[no_mangle]
pub extern "system" fn Java_com_toxicbakery_app_bison_protocol_HerdrNativeBridge_nativeBuildRemoteClientBridgeCommand(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    session_name: JString<'_>,
) -> jstring {
    let session = jstring_to_rust(&mut env, &session_name).ok();
    let cmd = build_remote_client_bridge_command(session.as_deref());
    rust_to_jstring(&mut env, &cmd)
}

/// Builds the `herdr remote-api-bridge` command line.
#[no_mangle]
pub extern "system" fn Java_com_toxicbakery_app_bison_protocol_HerdrNativeBridge_nativeBuildRemoteApiBridgeCommand(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    session_name: JString<'_>,
) -> jstring {
    let session = jstring_to_rust(&mut env, &session_name).ok();
    let cmd = build_remote_api_bridge_command(session.as_deref());
    rust_to_jstring(&mut env, &cmd)
}

#[cfg(test)]
mod tests {
    use super::*;
    use herdr_protocol::PaneSurfaceFrame;

    #[test]
    fn synthesize_mosh_frame_cached_reuses_terminal_state_and_resizes_in_place() {
        let pane_id = 42_001;

        let json_first =
            synthesize_mosh_frame_cached(pane_id, 20, 4, 1, b"Hello ", None).unwrap();
        let frame_first: PaneSurfaceFrame = serde_json::from_str(&json_first).unwrap();
        assert_eq!(frame_first.frame.width, 20);
        assert_eq!(frame_first.frame.height, 4);

        // Second call feeds incremental bytes and must retain "Hello " from the cached VtSynthesizer
        let json_second =
            synthesize_mosh_frame_cached(pane_id, 20, 4, 1, b"World!", None).unwrap();
        let frame_second: PaneSurfaceFrame = serde_json::from_str(&json_second).unwrap();
        let row0: String = frame_second.frame.cells[0..12]
            .iter()
            .map(|c| c.symbol.as_str())
            .collect();
        assert_eq!(row0, "Hello World!");

        // Third call resizes to (30, 6) and verifies dimensions update without dropping buffer
        let json_resized = synthesize_mosh_frame_cached(pane_id, 30, 6, 1, b"", None).unwrap();
        let frame_resized: PaneSurfaceFrame = serde_json::from_str(&json_resized).unwrap();
        assert_eq!(frame_resized.frame.width, 30);
        assert_eq!(frame_resized.frame.height, 6);
        let resized_row0: String = frame_resized.frame.cells[0..12]
            .iter()
            .map(|c| c.symbol.as_str())
            .collect();
        assert_eq!(resized_row0, "Hello World!");
    }
}
