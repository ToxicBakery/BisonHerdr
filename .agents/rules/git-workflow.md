# Git Workflow Rule: Commit to `main` on Task Completion

Whenever you complete a user request, implementation phase, or self-contained logical unit of work in this repository:

1. **Target Branch:** Always work on and commit to the `main` branch unless the user explicitly asks for a different branch.
2. **Verify First:** Run relevant checks or tests for the files you changed.
3. **Commit Immediately Upon Completion:** Stage your changes (`git add`) and create a clean commit (`git commit -m "..."`) on `main` before finishing your turn.
4. **Clean Working Tree:** Confirm with `git status` that the working tree is clean when your task is complete.
