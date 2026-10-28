# BisonHerdr

**BisonHerdr** (`com.toxicbakery.app.bison`) is a high-performance, native Android tablet and foldable terminal workspace and multi-machine coding-agent control center powered by [Herdr](https://github.com/herdrdev/herdr).

It is designed around two core principles:

1. **Survive Backgrounding & Network Roaming Effortlessly** — Sessions stay alive across Android app switching, tablet sleep, and Wi-Fi ↔ cellular transitions without dropping connections, losing terminal state, or draining battery in the background.
2. **A Strong, Touch-First Terminal Experience as the Primary Input** — Instead of shrinking a desktop TUI onto glass or relegating touch to an afterthought, the terminal is a full-bleed, high-refresh native canvas paired with ergonomic split-thumb controls, direct touch gestures, and a native prompt composer that makes driving interactive shells, TUIs, and AI coding agents fast and natural on a tablet.

---

## Core Goals

### 1. Resilience: Survive Backgrounding & Roaming
Traditional SSH clients on Android drop connections when you switch apps to inspect a browser preview, lock the tablet, or walk from Wi-Fi to 5G. BisonHerdr eliminates connection fragility through a layered lifecycle and transport architecture:

- **Android Foreground Service (`connectedDevice` / `specialUse`)**: Holds active transport sockets open inside a persistent `BisonSessionService` with live notification actions (*Approve Pending Agent*, *Disconnect All*) while you switch to browsers, docs, or other apps.
- **Mosh UDP SSP Backbone + Zero-Traffic Background Suspend (planned)**: This version does not include Mosh. The SSH endpoint path is the only transport. A later version will use Mosh's State Synchronization Protocol (`AES-128-OCB` over UDP). Then the app sends no packets in the background, and gets the latest terminal frame in one UDP round trip when it comes back. [Issue #22](https://github.com/ToxicBakery/BisonHerdr/issues/22) has the work for Mosh.
- **Zero-Bandwidth Daemon Surface Lease**: When `ON_STOP` fires on the Herdr control channel, the client sends `client_shell.surface.set({ active: false })` to pause high-frequency terminal surface deltas while retaining the logical session lease, then restores the viewport and requests a fresh keyframe via `client_shell.surface.set({ active: true })` on `ON_START`.
- **Sub-100ms Network Roaming (`ConnectivityManager.NetworkCallback`, planned)**: In this version, a network change has no effect on the SSH session. A later version will detect Wi-Fi ↔ cellular handoffs immediately, and connect the SSH session and the Herdr control sideband again in the background. Items 10 and 11 of [issue #20](https://github.com/ToxicBakery/BisonHerdr/issues/20) hold this work. The UDP socket rebind and the SSP heartbeat need Mosh, which is also planned.

### 2. Primary Input: Touch-First Terminal Experience
On a tablet, touch and the on-screen keyboard (or folio keyboard) are first-class inputs. BisonHerdr replaces tiny text-mode chrome with native, ergonomic touch controls:

- **Zen Canvas (96%+ Terminal Viewport)**: Suppresses Herdr's inner ASCII sidebar and top tab bar so your panes occupy the entire screen. A single compact 36dp top bar shows workspace tabs, agent attention badges, and your live connection/RTT pill.
- **Split-Thumb Ergonomic Floating Clusters**: Docked at the bottom-left (`Esc`, `Ctrl`, `Alt`, `Tab`, `/`, `-`, `|`, `~`) and bottom-right (`↑`, `↓`, `←`, `→`, `Enter`, `PgUp`/`PgDn`, `Prompt`) corners—right under your thumbs when holding a tablet in landscape, leaving the center viewport unobstructed.
- **Native Prompt & Command Composer**: A collapsible Material 3 bottom sheet supporting full Android IME features—voice dictation, swipe typing, spellcheck/autocorrect, multi-line editing, clipboard/screenshot attachment, and target selection (focused pane, specific agent, or broadcast)—dispatched atomically via Herdr's `agent.prompt` / `pane.send_input` RPCs.
- **Direct BSP Touch Manipulation**: Drag pane dividers with snap points (25% / 33% / 50% / 67% / 75%), double-tap a divider to equalize splits, double-tap a pane header to zoom/unzoom (`pane.zoom`), and use two-finger swipe to switch workspaces.
- **120Hz Native Compose Canvas**: `TerminalSurfaceCanvas` draws the cells of each Herdr `PaneSurfaceFrame` in Compose, with box-drawing geometry, 24-bit TrueColor, and 19 curated themes. **Gruvbox Dark** is the default theme, and **OLED Pitch Black** is a battery-saver theme. A `libghostty-vt` VT parser over JNI is planned for the Mosh path.

---

## Architecture Overview

| Layer | Technology | Responsibility |
| :--- | :--- | :--- |
| **UI & Ergonomics** | Jetpack Compose + Material 3 Adaptive (`WindowSizeClass`) | 120Hz `TerminalSurfaceCanvas`, BSP split-tree layout, split-thumb clusters, command palette, multi-machine fleet drawer |
| **Shared Client Logic** | Kotlin Multiplatform (`commonMain`) + Kodein DI | Unidirectional MVI state stores, session lifecycle orchestration, SSH host management, theme & keymap persistence |
| **Terminal & Transport Core** | Kotlin (`:core:protocol`, `:platform:android:ssh`) + [JSch](https://github.com/mwiede/jsch) | SSH transport over JSch + Herdr Endpoint Generation 1 models and `SurfaceDeltaAssembler` in `:core:protocol`. A Mosh UDP SSP client and a `libghostty-vt` VT parser are planned. |
| **Remote Host Daemon** | [Herdr](https://github.com/herdrdev/herdr) (`remote-client-bridge` / `remote-api-bridge`) | Unmodified remote daemon providing persistent workspaces, BSP panes, PTY multiplexer, coding-agent status detection (`claude`, `codex`, `gemini`, `aider`, `opencode`), and JSON-RPC control plane |

For the complete technical specification, transport state machine, and implementation roadmap, see the **[Core App Design Document](docs/plans/01-core-app-design.md)**.

---

## Repository Structure

- [`docs/plans/01-core-app-design.md`](docs/plans/01-core-app-design.md) — Comprehensive architecture, UX, transport, and phased implementation plan.
- [`rust/`](rust/README.md) — `herdr-protocol`, a standalone Apache-2.0 Rust reference crate for the Herdr wire types. Gradle does not build it, and the app does not use it.

---

## License

This project is licensed under the **Apache License, Version 2.0** — see the [LICENSE](LICENSE) file for details.
