# Autonomous decisions

- Work only in the verified m1a release worktree; preserve other worktrees.
- Reuse valid evidence; no new real-data import/restore or production contact for this task.
- No new tool/framework/dependency merely for activity. Existing compiler/lint/tests first.
- Cloud import stays BLOCKED; local controller development is independent of owner risk acceptance.
- No V7 rollback after token/financial target writes. No original Flyway checksum changes.
- Existing detailed privacy and one-window decisions remain authoritative: docs/cloud-privacy-decision.md and docs/cloud-final-migration-window.md.

- Local testing uses only a new explicitly marked SYNTHETIC database. Existing MySQL/PG recovery copies are not test cleanup destinations.
- Prefer ALTER SEQUENCE RESTART inside the transaction over nontransactional setval in the new engine.
- Unknown COMMIT outcome is COMMIT_UNKNOWN, not a successful rollback; stop and reconcile through a new connection, never replay.
- Metadata-only cloud preflight cannot take record input. Real-cloud execution remains hard-disabled until a separately reviewed approval adapter exists.
- Backend/Node test environments scrub inherited Spring/JVM/database routing and email overrides, then inject loopback-only fixture settings.
- Reuse existing JDK21 and native PG instead of installing tools or troubleshooting Docker again. No unreviewed dependency upgrades.

- Flyway history alone cannot prove an unchanged physical schema. Require an independently generated V11 metadata contract before any inserts; do not train expected fingerprints on the live target.
- Schema contract excludes owners/current sequence values/ICU library versions, but checks actual collation definition, RLS, user triggers, unique/FK/CHECK/index/column/sequence structure. This is a compatibility check, not privacy redaction.
- Management401 is not schema drift. Keep its original API guard, use independently pinned read-only SQL diagnostics only, and report management state as unverified.
- Recommend privacy B with C as current state under the existing strict policy. Do not choose on behalf of the owner; A cannot be described as zero-exposure compliance.

## Latest owner scope and results — 8 November2026

- Owner approved rootless Ubuntu packages, dedicated locked identity and subordinate mappings; setup/build/actual synthetic runtime now PASS. Earlier no-install/container-blocked notes describe prior scope and are superseded, not permission to reinstall.
- Owner explicitly selects Option C/no real production financial-data upload to PostgreSQL cloud; stronger provider error protection remains desired. No risk acceptance or logging experiment.
- Registry-qualified official base names fix the reproduced Podman build failure; no other application-source behavior changed.
- Execute public Linux verifier from its owned file, never shell-containing Python source in WSL command arguments; private payload uses stdin and ephemeral Podman secret references. Stop only owned fixtures and remove only their temporary secrets.
- Retain image/stopped synthetic fixtures and exact receipts; do not replay successful attempt. OCI PASS is separate from unexecuted Render deployment, cgroup differences and production approvals.
- A synthetic portfolio must prevent real visitor data collection/writes; fake seeds/disclaimers alone are insufficient. Plan only, no demo implementation/deployment approved yet.

## Synthetic portfolio M6/M7 complete — latest8 November2026

LOCAL DEMO IMPLEMENTATION PASS as an explicitly offline guided simulation, not real registration/JWT/API/DB operations. Reviewed code a24fca1ef046e390aca6665382c7f02de87b6387, earlier3e12d1a fixture+fb0039d UI/security commits. No backend application/auth/Flyway/API changes; financial fixtures verified by3 Java21 service contracts.11 React/model +4 HTTP/policy tests PASS;4 browser cases PASS locally and on actual nonroot/rootless image. Image454abbc3a94320676a7260b78dfbb50de3015f67dfd91c6d5f614f988ddc762f, actual read-only asset write rejected,0 running demo containers. Default/mixed-env startup rejected; artifact integrity/path, CSP connect/form-none, no API/network/storage/personal-input/admin operation, read/consent/expiry/revocation/duplicate/reset boundaries verified. New build public-directory exclusion probe PASS; rebuilt runtime image ID unchanged, local/executed-container asset hashes identical. Normal production frontend build/config retained, scoped lint clean, npm production audit0 known vulnerabilities; no dependency upgrades/new dependencies. Unaffected backend/PostgreSQL/broad suites retained, no claim of a new demo DB test because no demo DB exists.

Release package SYNTHETIC-PORTFOLIO-APPROVAL.md, docs/synthetic-demo-verification.md and asset-manifest JSON prepare separate Pages (preferred static) or optional Render static-container hosting; no deployment/push/service creation/config changes. Exact next owner approval: isolated demo host/project/preview URL, reviewed SHA/artifact and specific private push/integration/deployment; then actual public-header/privacy/network smoke review before promotion. Standard host request metadata still needs owner review. Demo publication is not financial-data logging acceptance. Actual production unchanged/NO-GO, Option C/no-real-data upload and email disabled remain. Existing backups/keyring/recovery DBs and unrelated edits preserved. Read receipts before any repeat; successful private container-attempt marker prevents blind rerun.
