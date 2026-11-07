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
