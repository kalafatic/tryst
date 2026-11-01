# TRYST Git Commit Authorization Architecture

**Document Version:** 1.0.0
**Date:** 2026-11-01
**Implementation Baseline:** PROMPT 03/04

---

## 1. Threat Model & Verification Goals

Automated development platforms face substantial risks if agents or untrusted processes can trigger Git commits:
1. Unreviewed agent code could be committed to main or release branches.
2. Direct REST requests could bypass UI review checks.
3. Time-of-check / time-of-use (TOCTOU) races could allow malicious modifications to be inserted into the working tree after human approval was granted.

TRYST eliminates these risks through cryptographic commit gating inside `GitService`.

---

## 2. Authorized Commit Flow

Commit authorization is enforced by `GitService.commitAuthorizedReview(Path repoDir, CodeReview review, String authorizerId, String commitMessage)`:

1. **Repository Verification:** Asserts `repoDir` is a valid Git repository containing `.git`.
2. **Approval State Gate:** Requires `review.isApproved() == true`. Unapproved reviews immediately return an authorization error.
3. **Working Tree Fingerprint Verification:**
   - Computes current working tree diff via `git diff`.
   - Computes SHA-256 fingerprint of current diff:
     ```java
     String currentFingerprint = CodeReview.calculateFingerprint(currentDiff);
     ```
   - Verifies `currentFingerprint.equals(review.getApprovedFingerprint())`. If any file in the workspace was modified, added, or deleted after approval, the commit is rejected.
4. **Staging & Commit:**
   - Stages verified changes (`git add .`).
   - Executes commit with message attributing authorizer and review ID.
5. **Commit Hash Capture:**
   - Runs `git rev-parse HEAD` to capture authoritative SHA commit hash.
   - Updates `review.recordCommit(commitHash)`.
   - Updates `task.complete()`.

---

## 3. Defense Against Bypasses

- No direct HTTP endpoint can trigger a Git commit without providing a valid `reviewId` whose persisted state satisfies all cryptographic preconditions.
- If a user edits a file via `/api/reviews/{id}/files/edit` after clicking Approve, `review.updateEffectiveDiff()` automatically resets `status` to `PENDING` and nullifies `approvedFingerprint`.
- Commits are scoped strictly to the project's authorized repository directory.

---

## 4. Automated Verification Reference

- `eu.kalafatic.tryst.tests.CodeReviewAndCommitAuthorizationTest`
  - `testAuthorizedGitCommitGating`:
    - Scenario 1: Commit rejected when review is pending/unapproved.
    - Scenario 2: Commit rejected when working tree is tampered after approval.
    - Scenario 3: Commit succeeds when working tree matches approved fingerprint, recording commit hash.
