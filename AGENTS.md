# Herdr Tablet — Agent Instructions

This file defines workspace-wide instructions and workflow rules for AI coding agents working in `herdr-tablet`.

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
  1. **Survives backgrounding and network roaming** without dropping terminal state (`BisonSessionService` foreground service, Mosh UDP SSP zero-traffic idle suspend, `client_shell.surface.set` zero-bandwidth daemon lease, and `NetworkCallback` fast-roaming).
  2. **Provides a strong, touch-first terminal experience as the primary input** ("Zen Canvas" 96%+ terminal viewport, split-thumb modifier/navigation clusters, native Compose prompt composer, direct BSP split gestures, and 120Hz `libghostty-vt` JNI rendering).
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
