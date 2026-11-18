# TRYST — Update Guide

Git is controlled by the user (Eclipse EGit). The safe update workflow (also exposed by UPM):

1. Inspect status (branch + dirty files).
2. **Stop on local changes** — never discard.
3. `git fetch --all --prune`.
4. Divergence analysis (outgoing/incoming counts).
5. Stop on unpushed local commits.
6. Require explicit confirmation.
7. `git pull --ff-only` only.
8. Update dependencies with `./mvnw -B -U -DskipTests clean verify` when requested.
9. Validate: `./mvnw -B validate`.

Safety: no resets, no rebases, no forced pushes, no silent branch switches. Verified on a disposable copy: refusal on dirty tree, refusal without confirmation, fast-forward integration with confirmation, refusal with unpushed commits.
