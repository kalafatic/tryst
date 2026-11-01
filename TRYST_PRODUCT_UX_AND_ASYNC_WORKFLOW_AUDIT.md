# TRYST – Product UX and Asynchronous Workflow Audit

**Document Version:** 1.0.0
**Date:** 2026-11-01
**Baseline Git Commit:** `539768259664f6695bf44f7efce06615bca6fbeb`

---

## 1. Product Vision & Asynchronous Workflow Evaluation

The central premise of **TRYST** (inspired by Google Jules) is to free software engineers from continuous monitoring of development agents. The platform must excel at two operational paradigms:

1. **Interactive Development:** Live conversational interaction, step-by-step guidance, immediate feedback, and real-time streaming output when the engineer chooses to remain engaged.
2. **Asynchronous Autonomous Development:** A reliable "fire-and-forget" workflow where the engineer submits a high-level task, closes the browser, logs out, or shuts down their workstation. The server continues executing autonomously, generates code modifications, executes tests, performs static review, and preserves a complete review package. The engineer returns minutes or hours later to inspect the results, edit changes, and explicitly authorize Git commits.

### Current Workflow Reality:
In the current implementation:
- **Interactive Mode:** Implemented exclusively through desktop SWT widgets in `MainWorkspaceView.java`. The user can type a task name, click "Run Task with Agent", and see text stream into an SWT `StyledText` widget. However, this only functions if the desktop window is kept open on a display server.
- **Asynchronous Mode:** **Broken / Fragile**. If the user closes the desktop application, the entire JVM process terminates (`PlatformUI.RETURN_OK`). In-flight background threads in `ManagedAsyncExecutionService` are forcibly halted, and uncompleted tasks remain stuck in `RUNNING` status without resumption or crash reconciliation. There is no web interface to return to.

---

## 2. Functional Area UX Audit

### 2.1 Workbench (Project & Task Submission)
- **Intended UX:**
  - Fast workspace and project selection.
  - Multi-line natural-language task prompt with support for markdown instructions, file references (`@path/to/file`), and voice/dictation extension points.
  - Context indicators showing selected project branch, active git status, and configured Ollama model.
  - Instant durable task submission with persistent task ID generation.
- **Current Desktop Implementation (`MainWorkspaceView.java`):**
  - Requires opening a modal `InputDialog` with single-line text input for task title.
  - No markdown preview, no file mentions, no branch indicators.
  - Tasks can only be added to a globally active workspace.
- **Recommendations for Web UX:**
  - Build a sleek, single-page application (SPA) header and project drawer.
  - Provide a rich prompt input card with multi-line textarea, keyboard submission (`Cmd/Ctrl+Enter`), model selector dropdown (populated dynamically from `/api/ai/models`), and task priority toggle.

### 2.2 Execution Center (Task Management & Progress Visibility)
- **Intended UX:**
  - Overview of all tasks partitioned by state: Queued, In Progress, Pending Review, Completed, Failed, Cancelled.
  - Live progress visualization: agent iteration count, current step (planning, reading file, editing, running tests, reviewing), elapsed time, and token streaming.
  - One-click safe task cancellation with immediate feedback.
  - Disconnection-resilient progress: if the user closes and re-opens the browser, the web client fetches the historical log and subscribes to the live SSE stream seamlessly.
- **Current Desktop Implementation:**
  - SWT `TableViewer` listing tasks in a single column with string format `[STATUS] Title - Description`.
  - No iteration progress bar or step indicators; raw text is dumped into a single text pane.
  - Closing the window kills the process.
- **Recommendations for Web UX:**
  - Implement an Execution Center split view: left column displays real-time task queue with badges; right pane displays step progression timeline (Plan -> Tool Execution -> Test Run -> Review) with expandable log output.
  - Connect to `/api/events` via standard browser `EventSource` (SSE).

### 2.3 Review Center (Generated Git Diff & Inline Editing)
- **Intended UX (Critical Product Differentiator):**
  - Side-by-side or unified diff viewer of all files modified during task execution.
  - **Inline Editing:** The user must be able to click directly into modified lines or file panes to refine the agent's work, fix typos, or adjust implementation details before commit.
  - Re-validation trigger: after user edits, a "Re-run Tests" button re-executes tests against the edited workspace.
  - Explicit commit gating: "Approve & Commit" button with customizable commit message, creating a signed or structured commit on a dedicated branch.
  - "Reject" button with optional feedback to restart agent loop.
- **Current Implementation:**
  - **Missing**. Only a backend class `CodeReviewService.java` exists, scanning diffs for regex strings (`password =`, `System.exit`).
  - No visual diff viewer; no editor; no user approval checkpoint.
- **Recommendations for Web UX:**
  - Implement a dedicated Review Center view using syntax-highlighted diff rendering (unified and split diff view).
  - Provide an embedded in-browser text editor for modified files.
  - Include an explicit "Approve & Commit" modal that captures the commit message and calls `/api/reviews/{id}/approve`.

### 2.4 Notifications & Alerts
- **Intended UX:**
  - In-app notification center and browser native desktop notifications (HTML5 Notification API) alerting the engineer when an asynchronous task completes, fails, or requires review approval.
- **Current Implementation:**
  - Missing.
- **Recommendations for Web UX:**
  - Implement lightweight web notification toast components and browser notification opt-in.

### 2.5 Server Administration Dashboard
- **Intended UX:**
  - Administrative control pane for server operators:
    - User accounts, roles (Admin, Developer, Viewer), and active logins.
    - System health: JVM memory, CPU utilization, active OS processes.
    - AI metrics: Ollama endpoint status, model cache, inference tokens/sec, average latency.
    - Persistent security audit logs: path access, tool executions, failed logins.
- **Current Implementation:**
  - Missing.
- **Recommendations for Web UX:**
  - Implement a clean Admin navigation tab in the web interface restricted to users with `ADMIN` role.

---

## 3. Web Application UX Architecture & Accessibility

To maintain consistency with the lightweight, pure-Java, dependency-free ethos of TRYST:
- **Frontend Architecture:**
  - Pure modern web standards: semantic HTML5, clean CSS (custom properties / modern layout grids), and modular vanilla JavaScript (ES6 modules).
  - No Node.js, npm build pipelines, Webpack, or heavy frameworks (React/Angular) required at runtime. The static assets will be served directly by the server's embedded HTTP server.
  - Responsive design adapting gracefully to desktop browsers, tablets, and mobile devices.
- **Accessibility & Interaction:**
  - High-contrast visual hierarchy with clear focus rings for keyboard navigation.
  - ARIA attributes on buttons, modals, tabs, and alerts (`role="status"`, `aria-live="polite"` for log streaming).
  - Keyboard shortcuts: `Ctrl+Enter` to submit task, `Esc` to close modals, `Ctrl+Shift+R` to switch to Review Center.

---

## 4. Summary of UX Audit Findings

| UX Area | Current State | Target State | Gap Severity | Target Implementation Phase |
|---|---|---|---|---|
| **Workbench** | Modal single-line dialogs in SWT | Rich web workspace with markdown prompt & branch status | HIGH | Phase 1 (PROMPT 02/04) |
| **Execution Center** | Raw table and text pane | Step timeline, status queue badges, live SSE log streaming | HIGH | Phase 1 (PROMPT 02/04) |
| **Review Center** | Nonexistent (backend rules only) | Syntax-highlighted diff viewer, inline file editor, approval commit gate | CRITICAL | Phase 2 (PROMPT 03/04) |
| **Async Reconnection** | Broken (closing app kills JVM) | Resilient reconnection: reloads state, catches up logs | CRITICAL | Phase 1 (PROMPT 02/04) |
| **Administration** | Nonexistent | Web dashboard for users, system metrics, AI latency & audit logs | MEDIUM | Phase 3 (PROMPT 04/04) |
| **Notifications** | Nonexistent | Browser push & in-app toast alerts on task completion | LOW | Phase 3 (PROMPT 04/04) |
