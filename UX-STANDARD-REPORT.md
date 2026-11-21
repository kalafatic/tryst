# TRYST — Unified RCP / Web UX Standard — Delivery Report

Date: 2026-11-21

## 1. Executive summary

TRYST already implements the standard: a 12-menu RCP action bar with Tools → Open
Terminal / Open TRYST Web Interface (live endpoint resolution), a complete Help
menu (Contents/Getting Started/Context Help/Keyboard Shortcuts/CLI
Commands/Diagnostics/About), F1 context help via the served `help.html`, five
programmatic RCP context menus plus the Contextual Action Contract "More Actions"
submenu, a web SPA with a keyboard-accessible right-click context menu, and a
table-driven headless CLI with REPL. This work adds the mandatory `/agents` CLI
command, a Save Output action for the console, a Help button in the web UI, and
the corresponding help-page updates.

## 2. Existing infrastructure reused

- `TrystServerApplication` single command table (dispatch + help + REPL) —
  extended with `agents`; the new command reads the live
  `GET /api/integration/applications` endpoint (ConnectedApplicationsHttpHandler),
  the same data source as the Administration → AI Harnesses page.
- `ConsoleOutputController.getAllEntries()` — the authoritative console buffer for
  the new Save Output action.
- `TerminalLauncher` / `WebInterfaceLauncher` / `HelpLauncher` — unchanged.
- Web context menu (`trystCtxMenu` + `/api/contextual/*`) — unchanged.

## 3. Components modified

| File | Change |
|---|---|
| `plugins/eu.kalafatic.tryst.application/src/.../TrystServerApplication.java` | Added the `agents` command (GET `/api/integration/applications`: name, type, enabled, status, selected — connection configuration is never printed) + `extractJsonArray` helper in the existing manual JSON style. |
| `plugins/eu.kalafatic.tryst.ui/src/.../view/ConsoleOutputView.java` | Added "Save Output…" action (toolbar + context menu): saves the full captured console to a user-chosen `.log`/`.txt` file. |
| `plugins/eu.kalafatic.tryst.infrastructure/src/.../server/TrystHttpServer.java` | Web SPA top nav: Help button (`#navHelp`) opening `/help.html` in a new tab via the existing `apiUrl()` base resolution (context-path safe). |
| `plugins/eu.kalafatic.tryst.infrastructure/src/web/help.html` | CLI table: added `agents` row; replaced the "intentionally not exposed" note with the live-registry description. |

## 4. New actions

- CLI `/agents` — connected coding agents (family harnesses: DCode, DStudio, EVO,
  OpenCode) with live status and selection.
- Console Save Output… (RCP).
- Web Help button.

## 5. Context menu coverage (RCP)

| Surface | Actions |
|---|---|
| TRYST Navigator | Per node: Session (Open/Copy ID), Project (Copy Path/Open Terminal/Create Task), Git repo (Pull/Copy Path/Open Terminal), Branch, Task (state-aware resume/pause/stop/cancel/start/move/archive/delete), Review, Workspace, Database table — plus the registry-driven "More Actions" submenu and Refresh/Expand/Collapse |
| Project Explorer | Refresh, Copy Path, Open Terminal |
| Console | Select All, Copy Message, Copy Details, **Save Output… (new)**, Navigate to Task, Scroll Lock, Pause/Resume, Clear |
| User Detail | Refresh User Data |
| Database page | View Data, View Structure, SQL templates |

## 6. Help coverage

- Help menu (all six entries + About) → `help.html#<anchor>`; F1 opens the anchor
  for the active part via `HelpLauncher.PART_TO_ANCHOR` (12 part mappings).
- Web: Help button added (previously no help entry point in the SPA).

## 7. CLI help coverage

- `help [topic]` generated from the single `commandTable`. Vocabulary now: `help,
  status, web, terminal, models, sessions, providers, agents, config, logs, stop,
  restart, start, exit`.
- Unknown commands exit 1 with Levenshtein suggestions; `tryst>` REPL uses the
  same table.

## 8. Tests executed

- Full Tycho build `./mvnw -B clean verify` → all bundles compiled and packaged
  SUCCESS. Test suite: `Tests run: 373, Failures: 4, Errors: 0, Skipped: 0`.
  The 4 failures are in pre-existing live/environment-dependent suites unrelated
  to this change:
  - `EvoMcpClientLiveIntegrationTest` (3) — live calls against an EVO MCP server
    that is not running in this environment.
  - `OpenCodeEndToEndWorkflowTest.testRealOpenCodeTaskThroughTrystHttpWorkflow`
    (1) — a real OpenCode CLI run (107 s) that failed in the sandbox environment.
- Runtime verification (materialized product, Java 21 VM, isolated
  `-Duser.home`, free ports):
  - `tryst … server help` → full help output including `agents`.
  - `tryst … server agents` → server started, live
    `GET /api/integration/applications` executed, printed
    "No connected coding agents." (fresh config) and shut down cleanly
    (`APPLICATION_TERMINATED`).

## 9. GUI behavior verified

- RCP GUI: not verified interactively (no X server on this host;
  `useUIHarness=false`). Console Save Output uses the standard SWT `FileDialog`;
  verified by compilation only.
- Web UI: Help button is a plain JS `window.open(apiUrl('/help.html'))` — served
  by the existing `StaticAssetHandler`; not browser-verified in this session.

## 10. Remaining limitations

- `ContextualActionIds` defines `session.open` / `session.duplicate` /
  `project.open` / `session.history` / `session.artifacts`, but only a subset has
  providers (the domain has no session-rename operation) — unchanged.
- The `Project` and `Web` top-level menus remain empty shells (`MB_ADDITIONS`
  only) — left untouched to avoid inventing actions.
- The web SPA has no tooltip system beyond ad-hoc `title` attributes.
- The TCP console protocol (PING/STATUS/…) has its own command list; it was left
  unchanged (separate protocol, documented in help).
