# BisonHerdr — Agent Instructions

This file defines workspace-wide instructions and workflow rules for AI coding agents that work in the BisonHerdr repository (`bison-herdr`).

## 1. Definition of Done & Git Workflow (REQUIRED)

- **Zero-Warning Builds (Definition of Done):** Work is **not** complete until all relevant Gradle/Kotlin and Cargo/Rust builds and tests pass **with zero compiler, linker, or build warnings**. Fix all deprecation, unused-symbol, unchecked-cast, clippy, and build configuration warnings before considering any task done.
- **Use Git Worktrees & Pull Requests for All Changes:** Agents **must never** commit directly to `main`. All changes **must** be developed in an isolated `git worktree` on a dedicated branch and merged via a GitHub Pull Request (`gh pr create`) targeting `main`. Do not leave completed work uncommitted or unsubmitted.
- **Atomic, Verified Commits & PR Workflow:**
  1. Create an isolated worktree and branch from `main` for each logical unit of work (e.g., `git worktree add ../bison-herdr-<slug> -b <type>/<slug> origin/main`).
  2. Verify that all relevant builds and unit tests complete **without warnings** or errors before committing.
  3. Check `git status` and `git diff` to ensure only intended files are staged and no temporary files or plaintext secrets are included.
  4. Commit to the worktree branch with a clear, descriptive Conventional Commit message (e.g., `feat: ...`, `fix: ...`, `docs: ...`, `chore: ...`, `refactor: ...`).
  5. Push the branch to `origin` and open a Pull Request targeting `main` using `gh pr create`.
  6. Remove temporary worktrees (`git worktree remove`) once changes are merged.

## 2. Project Mission & Architecture

- **Mission:** Build **BisonHerdr** (`com.toxicbakery.app.bison`), a high-quality native Android tablet & foldable app for [Herdr](https://github.com/herdrdev/herdr) (operating against unmodified upstream Herdr servers) that:
  1. **Survives backgrounding and network roaming** without dropping terminal state (`BisonSessionService` foreground service, Mosh UDP SSP zero-traffic idle suspend (planned, [#22](https://github.com/ToxicBakery/BisonHerdr/issues/22)), `client_shell.surface.set` zero-bandwidth daemon lease (target; this version does not send it), and `NetworkCallback` fast-roaming (this version: a session check and a reconnect)).
     - **Background surface lease (this version):** In `onStop`, the app turns off the lease of each connected machine. While the lease is off, the client pauses its 400 ms poll (`herdr api snapshot` and `pane.read`). The SSH session stays open, and JSch sends a keepalive each 10 s. In this version, the app does not send `client_shell.surface.set`.
     - **Network change (this version):** After a network change, the app sends an SSH keepalive and runs `exec true`, with a limit of 3 s. When this check fails, the app connects again at once. On `onLost`, the app closes the SSH sockets. The reconnect loop uses exponential backoff with jitter, and tries again only after a network failure.
  2. **Provides a strong, touch-first terminal experience as the primary input** ("Zen Canvas" terminal viewport (target: 96%+ of the screen; no code measures it), split-thumb modifier/navigation clusters, native Compose prompt composer, direct BSP split gestures, and 120Hz `libghostty-vt` JNI rendering (planned; a Compose canvas draws the Herdr `PaneSurfaceFrame` cells in this version)).
- **Design Reference:** Always consult [`docs/plans/01-core-app-design.md`](docs/plans/01-core-app-design.md) before making architectural, protocol, or module-graph changes.

## 3. Codebase Conventions

- **Root Package Namespace:** Use `com.toxicbakery.app.bison` as the root package across all modules (`:app:android`, `:core:*`, `:platform:*`, `composite`).
- **Strict Layered Module Graph:**
  - `:core:*` modules are Kotlin Multiplatform (`commonMain` / `commonTest`) and must **never** import from `:platform:*` or `:app:*`.
  - `:core:di` depends on `:core:common`, never the reverse.
  - `:core:fake-server` is wired into `:app:android` via `debugImplementation` only; release builds bind `NoDebugServer`.
- **Dependency Injection:** Use **Kodein** (`org.kodein.di`) across all modules (`coreDiModule`, `platformModule`, `screenModule`).
- **State Management:** Use unidirectional MVI (`BaseViewModel<State, Event, Action>`, `MutableStateFlow`, `StateMutation<State>`) and focused `*Delegate` classes.
- **License:** All new code in this repository is licensed under the **Apache License, Version 2.0** ([`LICENSE`](LICENSE)). Keep the dependency graph free of copyleft (GPL/AGPL) libraries.
