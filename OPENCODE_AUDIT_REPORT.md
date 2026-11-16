# OpenCode Audit Report – Verified Evidence (v1.19.5)

## A. Environment & Installation

| Component | Verified Value | Source |
|-----------|----------------|--------|
| CLI Binary | `/home/petr/.opencode/bin/opencode` | Filesystem |
| CLI Version | **1.19.5** | `opencode --version` |
| Runtime | Node.js v18.19.1 | `node --version` |
| npm | 9.2.0 | `npm --version` |
| OS | Ubuntu 24.04.5 LTS (x86_64) | System info |
| Ollama | v0.40.16, running at `http://127.0.0.1:11434/v1` | Verified endpoint |
| Local models | `qwen3-coder-30b-32k:latest`, `qwen3-coder-next:32k` | `ollama list` |
| Cache dir | `/home/petr/.cache/opencode/` | Standard XDG layout |
| State dir | `/home/petr/.local/state/opencode/` | Standard XDG layout |
| Data dir | `/home/petr/.local/share/opencode/` | SQLite DB location |

## B. Configuration (`/home/petr/.config/opencode/opencode.jsonc`)

| Setting | Value |
|---------|-------|
| `model` | `ollama/qwen3-coder-30b-32k:latest` |
| `default_agent` | `build` |
| `compaction.auto` | `true` |
| `compaction.prune` | `false` |
| `compaction.reserved` | 32768 tokens |
| `compaction.preserve_recent_tokens` | 65536 tokens |
| `tool_output.max_lines` | 20000 |
| `tool_output.max_bytes` | 2097152 (2 MiB) |
| **Providers** | deepseek, ollama |
| **Permission model** | Allow read/edit/grep/glob/list; bash with strict deny list (no `rm`, `rmdir`, `sudo`, `mkfs`, `dd`, `shutdown`, `reboot`) |
| **External directories** | All allowed (`"*": "allow"`) |

## C. Models – Configured vs. Actual

| Configured model | Context (config) | Output (config) | Actual (Ollama) | Verified |
|------------------|------------------|-----------------|-----------------|----------|
| `qwen3-coder-30b-32k:latest` | 32768 | 8192 | ~262K | CONFIG ≠ ACTUAL |
| `qwen3-coder-next:32k` | 32768 | 8192 | ~262K | CONFIG ≠ ACTUAL |
| `deepseek-v4-pro` | 1048576 | 393216 | Not locally verifiable | CONFIG only |

> **Discrepancy note**: Config specifies 32K context for Ollama models, but actual Ollama model metadata reports ~262K context. Tool output limits (20000 lines / 2 MiB) are *hard* caps enforced by OpenCode, independent of LLM context.

## D. Agents

| Agent | Usage | Evidence |
|-------|-------|----------|
| `build` | Default agent | `default_agent: "build"` |
| `build` | 10 recent sessions | SQL: `SELECT COUNT(*) FROM session WHERE agent='build'` |
| `explore` | 4 recent sessions | SQL: `SELECT COUNT(*) FROM session WHERE agent='explore'` |
| `general` | 1 recent session | SQL: `SELECT COUNT(*) FROM session WHERE agent='general'` |

> No custom agent definitions (`.opencode/agents/`) or `skills.json`/`mcp.json` files found in config directory.

## E. Persistence – SQLite Database (`/home/petr/.local/share/opencode/opencode.db`)

| Table | Purpose |
|-------|---------|
| `session` | Task/session lifecycle |
| `todo` | Per-session task list |
| `project` | Workspace/project metadata |
| `workspace` | Workspace definitions |
| `message`, `part`, `session_message` | Chat message history |
| `event`, `event_sequence` | Event log |
| `migration`, `data_migration` | Schema migrations |
| `permission`, `credential`, `account` | Auth & access control |

**Database statistics**:
- Total tables: **19**
- Total sessions ever created: **42**
- Total todos ever created: **7**
- Active todos: **7** (4 completed, 1 in_progress, 2 pending)

**Recent models in use**:
- `qwen3-coder-30b-32k:latest` (default)
- `qwen3-coder-next:32k` (explore sessions)
- `deepseek-v4-pro` (one session)
- `deepseek-flash` (one session)

**Recent sessions** (ordered by creation):
| ID | Title | Agent | Model | Status |
|----|-------|-------|-------|--------|
| `ses_e1ddc953effepyTmPJDAJfSf2j` | Maven Java version verification | build | qwen3-coder-30b-32k | completed |
| `ses_e1de112dbffeTYwp4AI6SNNiIZ` | Maven build diagnosis | build | qwen3-coder-30b-32k | completed |
| `ses_e1de32643ffeOjEMmu1E05no0L` | Git repository status | build | qwen3-coder-30b-32k | completed |
| `ses_e1e187f47ffeqOteQu5SQJ30d8` | Search for skills & MCP configs | explore | qwen3-coder-next:32k | completed |
| `ses_e1e203604ffe0kTloezvb3EbM9` | Read all agent files | explore | qwen3-coder-next:32k | completed |
| `ses_e2725531fffep8ubze0Bikc9Gt` | Analyze database schema | build | qwen3-coder-30b-32k | **in_progress** |

**Pending todos** (from active session `ses_e2725531fffep8ubze0Bikc9Gt`):
| Content | Status | Priority |
|---------|--------|----------|
| Analyze database schema and persistence layer dependencies | in_progress | high |
| Document identified architectural inconsistencies and technical debt | pending | medium |
| Await user instructions for specific task | pending | medium |

## F. Documented Limitations & Constraints

| Constraint | Status | Evidence |
|------------|--------|----------|
| No `rm`/`rmdir`/`sudo`/`mkfs`/`dd`/`shutdown`/`reboot` bash commands | ACTIVE | Configured in `permission.bash.deny` |
| Max tool output: 20000 lines / 2 MiB | ACTIVE | `tool_output.max_lines`, `tool_output.max_bytes` |
| Auto compaction enabled, prune disabled | ACTIVE | `compaction.auto: true`, `compaction.prune: false` |
| Reserved token buffer: 32768 | ACTIVE | `compaction.reserved: 32768` |
| Preserve recent tokens: 65536 | ACTIVE | `compaction.preserve_recent_tokens: 65536` |
| All external directories accessible | ACTIVE | `permission.external_directory.*: "allow"` |
| No custom agents defined | CONFIRMED | No `.opencode/agents/` directory or custom agent JSON found |
| No skills.json or mcp.json | CONFIRMED | Not present in config directory |

## G. Unknowns & Recommendations

### Unknowns (cannot verify without runtime logs)

| Question | Status |
|----------|--------|
| Actual compaction behavior (trigger thresholds, timing) | Unknown |
| Subagent delegation (if `build` uses `explore` internally) | Unknown |
| Real-time context window usage vs. theoretical limits | Unknown |
| Full agent execution chain (what `build` actually invokes) | Unknown |
| How `session_context_epoch` table is used | Unknown |

### Recommendations

| Priority | Action |
|----------|--------|
| **High** | Update model context limits in config to reflect actual Ollama metadata (262K context for Qwen3-Coder models) |
| **High** | Audit and document actual agent responsibilities (especially if `build` invokes `explore` or `general`) |
| **Medium** | Enable `compaction.prune` only after verifying safe history retention policy |
| **Medium** | Capture and document actual tool output limits encountered in long-running sessions |
| **Low** | Define and commit custom agent configurations (`skills.json`, `mcp.json`) if needed for reproducibility |

---

*Report generated: 2026-11-16*
*Working directory: `/home/petr/git/tryst-121126`*
*Database: `/home/petr/.local/share/opencode/opencode.db`*
*Config: `/home/petr/.config/opencode/opencode.jsonc`*
