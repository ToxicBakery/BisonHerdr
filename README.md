# BisonHerdr

**BisonHerdr** (`com.toxicbakery.app.bison`) is a high-performance, native Android tablet and foldable terminal workspace and multi-machine coding-agent control center powered by [Herdr](https://github.com/herdrdev/herdr).

It is designed around two core principles:

1. **Survive Backgrounding & Network Roaming** — A foreground service keeps the SSH sessions open when you switch apps or when the tablet sleeps. In the background, the app stops its terminal reads. SSH uses TCP, so a Wi-Fi ↔ cellular change can close a session. The app then checks each session, connects again at once when a session does not reply, and keeps the last terminal state on the screen.
2. **A Strong, Touch-First Terminal Experience as the Primary Input** — Instead of shrinking a desktop TUI onto glass or relegating touch to an afterthought, the terminal is a full-bleed, high-refresh native canvas paired with ergonomic split-thumb controls, direct touch gestures, and a native prompt composer that makes driving interactive shells, TUIs, and AI coding agents fast and natural on a tablet.

---

## Core Goals

### 1. Resilience: Survive Backgrounding & Roaming
Traditional SSH clients on Android drop connections when you switch apps to inspect a browser preview, lock the tablet, or walk from Wi-Fi to 5G. BisonHerdr uses a layered lifecycle and transport design to keep each session open, and to connect again quickly when a network change closes it:

- **Android Foreground Service (`specialUse`)**: `BisonSessionService` keeps the transport sockets open while you use browsers, docs, or other apps. The ongoing notification has a *Disconnect All* action. An agent alert has *Approve*, *Reject*, and *Reply* actions. In this version, only the demo server sends agent events, so a real host gives no agent alert. Agent alerts for real hosts are planned. The service stops itself when the app stays in the background for 30 s with no connected machine and no connection in progress.
- **Mosh UDP SSP Backbone + Zero-Traffic Background Suspend (planned)**: This version does not include Mosh. The SSH endpoint path is the only transport. A later version will use Mosh's State Synchronization Protocol (`AES-128-OCB` over UDP). Then the app sends no packets in the background, and gets the latest terminal frame in one UDP round trip when it comes back. [Issue #22](https://github.com/ToxicBakery/BisonHerdr/issues/22) has the work for Mosh.
- **Background Surface Lease**: When `ON_STOP` fires, the app turns off the surface lease of each connected machine. The client then stops its pane reads, and the SSH session stays open with no terminal traffic. When `ON_START` fires, the app shows the last frame at once, and the next poll reads the new state. This version does not send `client_shell.surface.set` to the Herdr server.
- **Network Change Check (`ConnectivityManager.NetworkCallback`)**: `BisonSessionService` watches the default network. On `onAvailable` and `onLinkPropertiesChanged`, the app checks each connected machine a maximum of one time each second. The check sends an SSH keepalive and runs `exec true`, with a limit of 3 s. When the check fails, the app connects again at once. On `onLost`, the app closes the SSH sockets, so that no attempt waits for a TCP timeout.
- **Reconnect Loop with Backoff**: When a session closes, the app connects again in the background and shows *Reconnecting…*. The loop waits 1, 2, 4, 8, 16, and then 30 s between failed attempts, with a random jitter of up to 25 percent. It tries again only after a network failure. A changed host key, a failed login, or a missing key stops the loop and shows the cause. After a reconnect, the app reads the surface again and sends the queued prompts. The UDP socket rebind and the SSP heartbeat need Mosh, which is planned ([issue #22](https://github.com/ToxicBakery/BisonHerdr/issues/22)).

### 2. Primary Input: Touch-First Terminal Experience
On a tablet, touch and the on-screen keyboard (or folio keyboard) are first-class inputs. BisonHerdr replaces tiny text-mode chrome with native, ergonomic touch controls:

- **Zen Canvas (Full-Screen Terminal Viewport)**: The app reads each visible pane with `pane.read` and draws only the pane cells. Thus no Herdr sidebar or tab bar shows, and your panes use the full screen. A compact top HUD shows workspace and tab pills, agent attention badges, and a connection pill with the measured poll time.
- **Split-Thumb Ergonomic Floating Clusters**: Docked at the bottom-left (`Esc`, `Tab`, `Ctrl`, `Alt`, `^C`, `^B`, `/`, `|` by default) and bottom-right (`←`, `↓`, `↑`, `→`, `Enter`, and a drag trackpad for the arrows) corners, with the `Prompt` pill between them. The clusters are right under your thumbs when you hold a tablet in landscape, and the center viewport stays clear.
- **Native Prompt & Command Composer**: An expandable card above the thumb keys with a multi-line text field, so the Android IME gives voice dictation, swipe typing, and autocorrect. You select the target: the focused pane, the first agent of the snapshot, or a broadcast. The app sends the prompt with Herdr's `agent.prompt` / `pane.send_input` RPCs.
- **Direct BSP Touch Manipulation**: Drag pane dividers with snap points (25% / 33% / 50% / 67% / 75%), and double-tap a divider to set its split to 50%. Double-tap a pane to zoom or unzoom it (`pane.zoom`), and pinch with two fingers to change the font size.
- **Native Compose Canvas**: `TerminalSurfaceCanvas` draws the cells of each Herdr `PaneSurfaceFrame` in Compose, with box-drawing geometry, 24-bit TrueColor, and 19 curated themes. The app sets no frame rate: Compose draws a new frame when the cells change. **Gruvbox Dark** is the default theme, and **OLED Pitch Black** is a battery-saver theme. A `libghostty-vt` VT parser over JNI is planned for the Mosh path.

---

## Architecture Overview

| Layer | Technology | Responsibility |
| :--- | :--- | :--- |
| **UI & Ergonomics** | Compose Multiplatform + Material 3 | `TerminalSurfaceCanvas`, BSP split-tree layout, split-thumb clusters, agent command drawer, multi-machine fleet screen |
| **Shared Client Logic** | Kotlin Multiplatform (`commonMain`) + Kodein DI | Unidirectional MVI state stores, session lifecycle orchestration, SSH host management, theme & keymap persistence |
| **Terminal & Transport Core** | Kotlin (`:core:protocol`, `:platform:android:ssh`) + [JSch](https://github.com/mwiede/jsch) | SSH transport over JSch: `herdr api snapshot` and `herdr remote-api-bridge` requests on persistent `sh` channels. `:core:protocol` has the Herdr Endpoint Generation 1 models and `SurfaceDeltaAssembler`. A Mosh UDP SSP client and a `libghostty-vt` VT parser are planned. |
| **Remote Host Daemon** | [Herdr](https://github.com/herdrdev/herdr) (`herdr api snapshot` / `remote-api-bridge`) | Unmodified remote daemon providing persistent workspaces, BSP panes, PTY multiplexer, coding-agent status detection (`claude`, `codex`, `gemini`, `aider`, `opencode`), and JSON-RPC control plane |

For the complete technical specification, transport state machine, and implementation roadmap, see the **[Core App Design Document](docs/plans/01-core-app-design.md)**.

---

## Repository Structure

- [`docs/plans/01-core-app-design.md`](docs/plans/01-core-app-design.md) — Comprehensive architecture, UX, transport, and phased implementation plan.
- [`rust/`](rust/README.md) — `herdr-protocol`, a standalone Apache-2.0 Rust reference crate for the Herdr wire types. Gradle does not build it, and the app does not use it.

---

## License

This project is licensed under the **Apache License, Version 2.0** — see the [LICENSE](LICENSE) file for details.
