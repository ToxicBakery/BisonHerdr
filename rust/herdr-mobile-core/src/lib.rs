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
