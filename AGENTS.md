# Herdr Tablet — Agent Instructions

This file defines workspace-wide instructions and workflow rules for AI coding agents working in `herdr-tablet`.

## 1. Git Workflow & Commit Policy (REQUIRED)

- **Commit to `main` as work completes:** Agents **must** commit changes directly to the `main` branch as each task, phase, or logical unit of work is completed. Do not leave completed work uncommitted in the working tree.
- **Atomic, verified commits:**
  1. Verify that any relevant builds, unit tests, or checks pass before committing.
  2. Check `git status` and `git diff` to ensure only intended files are staged and no temporary files or secrets are included.
  3. Commit to `main` with a clear, descriptive commit message (e.g., `feat: ...`, `fix: ...`, `docs: ...`, `chore: ...`, `refactor: ...`).
  4. If a task modifies the `herdr/` submodule (e.g., extracting `herdr-protocol` or updating `client_shell` API schema entries), commit those changes inside `herdr/` first, then stage and commit the updated `herdr` submodule pointer on `main` in the root repository.

## 2. Project Mission & Architecture

- **Mission:** Build a high-quality native Android tablet & foldable app for [Herdr](herdr/) that:
  1. **Survives backgrounding and network roaming** without dropping terminal state (`HerdrSessionService` foreground service, Mosh UDP SSP zero-traffic idle suspend, `client_shell.surface.set` zero-bandwidth daemon lease, and `NetworkCallback` fast-roaming).
  2. **Provides a strong, touch-first terminal experience as the primary input** ("Zen Canvas" 96%+ terminal viewport, split-thumb modifier/navigation clusters, native Compose prompt composer, direct BSP split gestures, and 120Hz `libghostty-vt` JNI rendering).
- **Design Reference:** Always consult [`docs/plans/01-core-app-design.md`](docs/plans/01-core-app-design.md) before making architectural, protocol, or module-graph changes.

## 3. Codebase Conventions

- **Strict Layered Module Graph:**
  - `:core:*` modules are Kotlin Multiplatform (`commonMain` / `commonTest`) and must **never** import from `:platform:*` or `:app:*`.
  - `:core:di` depends on `:core:common`, never the reverse.
  - `:core:fake-server` is wired into `:app:android` via `debugImplementation` only; release builds bind `NoDebugServer`.
- **Dependency Injection:** Use **Kodein** (`org.kodein.di`) across all modules (`coreDiModule`, `platformModule`, `screenModule`).
- **State Management:** Use unidirectional MVI (`BaseViewModel<State, Event, Action>`, `MutableStateFlow`, `StateMutation<State>`) and focused `*Delegate` classes.
- **License:** All new code in this repository is licensed under the **Apache License, Version 2.0** ([`LICENSE`](LICENSE)). Keep the dependency graph free of copyleft (GPL/AGPL) libraries.
