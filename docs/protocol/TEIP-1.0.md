# TEIP — Tryst-Evolution-Integration-Protocol

**This file is a POINTER — it is no longer a normative specification.**

The single authoritative, normative TEIP specification is **TEIP 1.1**, maintained in the EVO
repository:

`evolution` repository → `docs/protocol/TEIP-1.1.md`

(Remote reference only. TRYST must not maintain a competing independent copy of the
specification.)

Why: the historical TEIP 1.0 specification existed in two divergent copies (one in TRYST, one
in EVO) with verified wire incompatibilities between the two implementations (create-execution
status codes, request field names, event cursor semantics, health/capabilities/result shapes,
error model, authentication enforcement). TEIP 1.1 consolidates both copies into one canonical
contract; it is the specification the TRYST implementation must conform to.

- Consolidation record (verified divergences and their resolutions): the 1.0→1.1 changes table
  in `TEIP-1.1.md` §1 (canonical copy in the EVO repository).
- Migration tracking (work packages and order): Phase 3 work packages WP-3.2 (TRYST client
  conformance) and WP-3.3 (EVO server conformance), see `docs/integration/TEIP-TRYST-IMPLEMENTATION.md`.

The previous content of this file (TEIP 1.0, TRYST variant) is superseded and is retained in
Git history only.
