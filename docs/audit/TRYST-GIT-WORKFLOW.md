# TRYST Git-Based Autonomous Development Workflow

## 1. Isolated Workspace Sandboxes

- Every task executes in an isolated sandbox managed by `WorkspaceService.allocateTaskWorkspace()`.
- Sandboxes are rooted at `.tryst/workspaces/sandboxes/{ownerId}/{taskId}/`.
- Paths are validated against path traversal (`..`), null-byte injections, and symlink escapes using canonical real paths (`PermissionManager.validatePathWithinWorkspace`).
- Concurrency locks are acquired per workspace root directory (`PermissionManager.getWorkspaceLock`).

---

## 2. Full Git Lifecycle Verification

1. **Repository Synchronization**:
   - `GitService.cloneOrFetchRepository()` clones or fetches the target branch into the sandbox directory.
   - `GIT_TERMINAL_PROMPT=0` and `GIT_ASKPASS=echo` prevent terminal hangs.

2. **Intent-to-Add Untracked File Tracking**:
   - When tools create new files, `GitService.getDiff()` and `GitTools.GitDiffTool` run `git add -N .` (intent-to-add).
   - Both modified files and newly created files appear in diffs without being prematurely committed.

3. **Content Fingerprint & Review Pinning**:
   - `CodeReview.calculateFingerprint()` computes a SHA-256 hash of normalized diff contents.
   - When a human user approves a review (`review.approve(userId, revisionNumber)`), the exact current revision and fingerprint are permanently recorded (`approvedRevision`, `approvedFingerprint`).
   - If files in the working tree are modified after approval, the fingerprint changes, immediately invalidating the approval.

4. **Human-in-the-Loop Commit Gating**:
   - AI agents are strictly forbidden (`CodeReview.isAiAgent(userId)`) from approving reviews or committing changes.
   - `GitService.commitAuthorizedReview()` verifies:
     * Authorizer is a human user.
     * Review status is `APPROVED`.
     * Exact fingerprint matches staged diff and working tree diff.
     * No unresolved blocking comments exist.
   - Upon successful commit, `GitCommitRecord` is persisted in the database. Remote pushes require explicit secondary authorization (`pushAuthorized`).
