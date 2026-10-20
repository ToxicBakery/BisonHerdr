# Definition of Done & Git Workflow Rule: Zero-Warning Builds & Commit to `main`

Whenever you complete a user request, implementation phase, or self-contained logical unit of work in this repository:

1. **Target Branch:** Always work on and commit to the `main` branch unless the user explicitly asks for a different branch.
2. **Definition of Done — Builds Complete Without Warnings:** Verify that all relevant Gradle/Kotlin and Cargo/Rust builds and tests pass **with zero warnings**. Any compiler, Gradle, or Cargo/clippy warning must be resolved before a task is considered done.
3. **Commit Immediately Upon Completion:** Stage your changes (`git add`) and create a clean commit (`git commit -m "..."`) on `main` before finishing your turn.
4. **Clean Working Tree:** Confirm with `git status` that the working tree is clean when your task is complete.
