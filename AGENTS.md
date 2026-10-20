# Herdr Tablet — Agent Instructions

This file defines workspace-wide instructions and workflow rules for AI coding agents working in `herdr-tablet`.

## 1. Definition of Done & Git Workflow (REQUIRED)

- **Zero-Warning Builds (Definition of Done):** Work is **not** complete until all relevant Gradle/Kotlin and Cargo/Rust builds and tests pass **with zero compiler, linker, or build warnings**. Fix all deprecation, unused-symbol, unchecked-cast, clippy, and build configuration warnings before considering any task done.
- **Commit to `main` as work completes:** Agents **must** commit changes directly to the `main` branch as each task, phase, or logical unit of work is completed. Do not leave completed work uncommitted in the working tree.
- **Atomic, verified commits:**
  1. Verify that all relevant builds and unit tests complete **without warnings** or errors before committing.
  2. Check `git status` and `git diff` to ensure only intended files are staged and no temporary files or secrets are included.
  3. Commit to `main` with a clear, descriptive commit message (e.g., `feat: ...`, `fix: ...`, `docs: ...`, `chore: ...`, `refactor: ...`).

## 2. Project Mission & Architecture

- **Mission:** Build a high-quality native Android tablet & foldable app for [Herdr](https://github.com/herdrdev/herdr) (operating against unmodified upstream Herdr servers) that:
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
