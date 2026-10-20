# Herdr Tablet

**Herdr Tablet** is a high-performance, native Android tablet and foldable terminal workspace and multi-machine coding-agent control center powered by [Herdr](https://github.com/herdrdev/herdr).

It is designed around two core principles:

1. **Survive Backgrounding & Network Roaming Effortlessly** — Sessions stay alive across Android app switching, tablet sleep, and Wi-Fi ↔ cellular transitions without dropping connections, losing terminal state, or draining battery in the background.
2. **A Strong, Touch-First Terminal Experience as the Primary Input** — Instead of shrinking a desktop TUI onto glass or relegating touch to an afterthought, the terminal is a full-bleed, high-refresh native canvas paired with ergonomic split-thumb controls, direct touch gestures, and a native prompt composer that makes driving interactive shells, TUIs, and AI coding agents fast and natural on a tablet.

---

## Core Goals

### 1. Resilience: Survive Backgrounding & Roaming
Traditional SSH clients on Android drop connections when you switch apps to inspect a browser preview, lock the tablet, or walk from Wi-Fi to 5G. Herdr Tablet eliminates connection fragility through a layered lifecycle and transport architecture:

- **Android Foreground Service (`connectedDevice` / `specialUse`)**: Holds active transport sockets open inside a persistent `HerdrSessionService` with live notification actions (*Approve Pending Agent*, *Disconnect All*) while you switch to browsers, docs, or other apps.
- **Mosh UDP SSP Backbone + Zero-Traffic Background Suspend**: Uses Mosh's State Synchronization Protocol (`AES-128-OCB` over UDP) so packets stop flowing automatically when backgrounded, resuming and synchronizing to the latest terminal frame in a single UDP round-trip on wake.
- **Zero-Bandwidth Daemon Surface Lease**: When `ON_STOP` fires on the Herdr control channel, the client sends `client_shell.surface.set({ active: false })` to pause high-frequency terminal surface deltas while retaining the logical session lease, then restores the viewport and requests a fresh keyframe via `client_shell.surface.set({ active: true })` on `ON_START`.
- **Sub-100ms Network Roaming (`ConnectivityManager.NetworkCallback`)**: Detects Wi-Fi ↔ cellular handoffs immediately, rebinds UDP sockets and triggers an SSP heartbeat to roam IP addresses seamlessly while re-establishing the Herdr control sideband in the background.

### 2. Primary Input: Touch-First Terminal Experience
On a tablet, touch and the on-screen keyboard (or folio keyboard) are first-class inputs. Herdr Tablet replaces tiny text-mode chrome with native, ergonomic touch controls:

- **Zen Canvas (96%+ Terminal Viewport)**: Suppresses Herdr's inner ASCII sidebar and top tab bar so your panes occupy the entire screen. A single compact 36dp top bar shows workspace tabs, agent attention badges, and your live connection/RTT pill.
- **Split-Thumb Ergonomic Floating Clusters**: Docked at the bottom-left (`Esc`, `Ctrl`, `Alt`, `Tab`, `/`, `-`, `|`, `~`) and bottom-right (`↑`, `↓`, `←`, `→`, `Enter`, `PgUp`/`PgDn`, `Prompt`) corners—right under your thumbs when holding a tablet in landscape, leaving the center viewport unobstructed.
- **Native Prompt & Command Composer**: A collapsible Material 3 bottom sheet supporting full Android IME features—voice dictation, swipe typing, spellcheck/autocorrect, multi-line editing, clipboard/screenshot attachment, and target selection (focused pane, specific agent, or broadcast)—dispatched atomically via Herdr's `agent.prompt` / `pane.send_input` RPCs.
- **Direct BSP Touch Manipulation**: Drag pane dividers with snap points (25% / 33% / 50% / 67% / 75%), double-tap a divider to equalize splits, double-tap a pane header to zoom/unzoom (`pane.zoom`), and use two-finger swipe to switch workspaces.
- **120Hz Native Compose Canvas + `libghostty-vt`**: Renders dirty terminal cells directly from shared `DirectByteBuffer` memory over JNI with box-drawing geometry, 24-bit TrueColor, and 19 curated themes (defaulting to **Gruvbox Dark**, plus an **OLED Pitch Black** battery-saver mode).

---

## Architecture Overview

| Layer | Technology | Responsibility |
| :--- | :--- | :--- |
| **UI & Ergonomics** | Jetpack Compose + Material 3 Adaptive (`WindowSizeClass`) | 120Hz `PaneSurfaceCanvas`, BSP split-tree layout, split-thumb clusters, command palette, multi-machine fleet drawer |
| **Shared Client Logic** | Kotlin Multiplatform (`commonMain`) + Kodein DI | Unidirectional MVI state stores, session lifecycle orchestration, SSH host management, theme & keymap persistence |
| **Native Terminal & Transport Core** | Rust (`rust/herdr-mobile-core`) + `libghostty-vt` via JNI | Clean-room Apache-2.0 Mosh UDP SSP client + Herdr `EndpointControl` (`rust/herdr-protocol`) delta decoder + SIMD-accelerated VT parser |
| **Remote Host Daemon** | [Herdr](https://github.com/herdrdev/herdr) (`remote-client-bridge` / `remote-api-bridge`) | Unmodified remote daemon providing persistent workspaces, BSP panes, PTY multiplexer, coding-agent status detection (`claude`, `codex`, `gemini`, `aider`, `opencode`), and JSON-RPC control plane |

For the complete technical specification, transport state machine, JNI memory contract, and implementation roadmap, see the **[Core App Design Document](docs/plans/01-core-app-design.md)**.

---

## Repository Structure

- [`docs/plans/01-core-app-design.md`](docs/plans/01-core-app-design.md) — Comprehensive architecture, UX, transport, and phased implementation plan.
- `rust/` & `vendor/libghostty-vt/` — Standalone Apache-2.0 Rust protocol types (`herdr-protocol`), JNI Mosh/VT bridge (`herdr-mobile-core`), and vendored `libghostty-vt`.

---

## License

This project is licensed under the **Apache License, Version 2.0** — see the [LICENSE](LICENSE) file for details.
