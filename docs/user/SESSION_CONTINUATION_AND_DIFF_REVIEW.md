# TRYST – Session Continuation & Diff Review (User Guide)

**Date:** 2026-11-21
**Applies to:** TRYST web interface (embedded SPA) and HTTP API.

## 1. Concepts

- **Task** – one asynchronous coding execution (intake submission).
- **Session** – the persistent workspace for a coding objective. Each intake submission creates a coding session; tasks created via *Continue* belong to the same session.
- **Opening vs. Resuming vs. Continuing vs. New task**:
  - *Open*: view a session/task state (Studio) – read-only inspection.
  - *Resume*: re-activate a PAUSED task (Studio Pause/Resume buttons).
  - *Continue*: queue a **new task in the same session, project and sandbox** with a new instruction. Previous uncommitted changes are preserved and the new diff accumulates on top of them.
  - *New task*: creates a **new session** with a fresh sandbox clone.

## 2. Continuing a Session

1. Open the task in **Studio** (⚡ Studio in the Queue/Dashboard) or from the Code Reviews page.
2. Click **▶ Continue Session**, enter the instruction/correction (and optional title/priority), submit.
3. The follow-up task runs in the same sandbox. The AI receives: the session conversation history, the previous task's title/description/status, and your instruction.
4. Review the accumulated diff (previous + new changes) in Code Reviews.

API equivalents: `POST /api/tasks/{id}/continue` (works even for legacy tasks without a session — a session is created and backfilled) or `POST /api/sessions/{id}/continue`. Browse sessions via `GET /api/sessions` / `GET /api/sessions/{id}`.

## 3. Diff Review

- **Code Reviews** page lists all change sets (filterable by project via the sidebar or the global PROJECT selector).
- Select a review to see: lifecycle stepper, AI explanation (when generated), **Unified / Side-by-Side** toggle, per-file additions/deletions, revision history, build/test status.
- **Comments & rework**: click any diff line (or "Add Global Comment") to attach feedback to a file/line range with code context; mark comments as *Include in AI Rework* and optionally *blocking*. Then **Request AI Rework** — the same task re-enters the queue with `rework_count++`, the comments are injected into the AI prompt, and a new revision is produced in the same sandbox.
- **Manual edits**: "Manual Edit Proposed Changes" writes a file directly into the workspace and recomputes the diff.
- **Final actions** (explicit, never automatic):
  - *Approve Review* (exact revision; blocked by unresolved blocking comments and working-tree fingerprint mismatch)
  - *Commit Approved Changes* (human authorization only; recorded in `git_commits`)
  - *Push to GitHub* (after commit; conflict-checked)
  - *Reject*, *Re-run Build & Tests* (honors the project's configured build/test commands)

## 4. Project Configuration

**Projects & Repos** tab → select a project → configure:
- **Repository URL** (HTTPS/SSH/local path), **Default Branch**
- **Build Command** / **Test Command** (empty = auto-detect Maven/Gradle/npm/Cargo/Python)
- **Project-Specific Instructions** (injected into every task for this project)

Project configuration is persisted per project and does not affect other projects or sessions. Continuing a session reuses the project's configuration.

## 5. Administration

**Administration** tab:
- *Overview*: live component health (database, HTTP, auth, AI provider/model availability, workers, repository pool, sandboxes), version, uptime, queue counts and execution statistics.
- *Task Supervision*: per-task session/worker/failure details, ↻ Retry for `RECOVERY_REQUIRED` tasks, Cancel.
- *Workers*: capacity/busy/available + live worker slots.
- *Resource Pools*: real mirror status (SYNCED/STALE/MISSING) and sandbox counts.
- *Users & Roles*: create/lock/unlock/activate/deactivate/role change.

The **Dashboard** shows the same health data with a "Server Health" panel (real component states — no fabricated values).

## 6. Known Limitations

- When the AI provider/model is unavailable, tasks **fail honestly** (`MODEL_UNAVAILABLE`) — TRYST never fabricates code changes.
- "Ask AI to Explain Snippet" requires a wired inference service; otherwise it returns the stored explanation with `aiGenerated: false`.
- Dev-mode (`tryst.dev.mode`, default on) grants an unauthenticated admin context; production deployments must disable dev mode and complete first-run setup.
