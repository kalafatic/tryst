# TRYST Web-Based Code Review and Approval Workflow

**Document Version:** 1.0.0
**Date:** 2026-11-01
**Implementation Baseline:** PROMPT 03/04

---

## 1. Core Principles

The Code Review Center is a primary human-in-the-loop product differentiator for TRYST.
It ensures that:
1. No code written or modified by autonomous agents is ever committed without explicit human review and approval.
2. The user has the power to inspect unified diffs line-by-line, edit proposed changes directly in the workspace, and trigger build/test re-verification.
3. Content integrity is cryptographically enforced using SHA-256 fingerprints. Any modification after approval immediately invalidates the approval.

---

## 2. Domain Entities and Schema

### `CodeReview`
Located in `eu.kalafatic.tryst.domain.review.CodeReview`:
- `id` (UUID)
- `projectId` / `taskId` / `branch`
- `status` (`PENDING`, `APPROVED`, `REJECTED`, `COMMITTED`)
- `initialDiff`: Original Git diff produced by the autonomous agent loop.
- `effectiveDiff`: Current working tree diff including subsequent human edits.
- `contentFingerprint`: SHA-256 hash of `effectiveDiff`.
- `approvedFingerprint`: Fingerprint recorded at the moment of approval.
- `commitHash`: Recorded Git commit hash upon successful commit.
- `approvedBy`, `rejectedBy`, `rejectionReason`
- `buildStatus`, `testSummary`
- `createdAt`, `updatedAt`

### `ReviewFileEdit`
Located in `eu.kalafatic.tryst.domain.review.ReviewFileEdit`:
- Stores file path, original content, and modified content for each human edit made during the review.

### Persistence
Durable storage is provided by `SqliteCodeReviewRepository` backed by `code_reviews` and `review_file_edits` tables added in migration V2.

---

## 3. Web Review Center Delivery

`TrystHttpServer` serves the responsive Single Page Application from `/` and provides the following REST API endpoints:
- `GET /api/reviews`: Lists all review packages.
- `GET /api/reviews/{id}`: Returns review metadata, status, build and test diagnostics.
- `GET /api/reviews/{id}/diff`: Returns unified diff and content fingerprint.
- `POST /api/reviews/{id}/files/edit`: Writes user edits to workspace file on disk, updates `ReviewFileEdit`, recalculates Git diff, and updates review's effective fingerprint.
- `POST /api/reviews/{id}/build-test`: Re-runs Maven compile and test suites in project directory to verify human edits.
- `POST /api/reviews/{id}/approve`: Grants explicit review approval, capturing `approvedFingerprint`.
- `POST /api/reviews/{id}/reject`: Records rejection decision with reason.
- `POST /api/reviews/{id}/commit`: Triggers authorized Git commit.

---

## 4. UI Capabilities

The embedded SPA features:
- **Project Selection:** Switch active projects and scope view.
- **Review Queue:** Real-time list of pending, approved, and rejected reviews with status badges.
- **Unified Diff Viewer:** Unified diff presentation with line-by-line added (`+`, green) and removed (`-`, red) syntax highlighting.
- **Interactive File Editor:** Inline editing interface allowing users to adjust proposed code and save directly into the project workspace.
- **Action Toolbar:** One-click actions for Re-run Build/Test, Approve, Reject, and Commit.

---

## 5. Automated Verification Reference

- `eu.kalafatic.tryst.tests.CodeReviewAndCommitAuthorizationTest`
  - `testCodeReviewLifecycleAndFingerprintApproval`: Validates that modifying an approved review's effective diff automatically revokes approval and updates the content fingerprint.
