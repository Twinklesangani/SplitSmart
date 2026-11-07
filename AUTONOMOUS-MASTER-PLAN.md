# Autonomous master plan

8 November 2026. Active worktree: `audit-output/m1a`, branch `codex/password-recovery-backend`. Baseline `408278cc9f378c0a1a4357160d46fc8ee54025bf`. Root worktree is NOT the release branch.

Only local/synthetic work is authorized. No real cloud import, production freeze/write, deployment, live config change, push/merge, spending or privacy acceptance. Existing runbooks are linked rather than duplicated.

## M0 â€” Recover authoritative state

- Status: PASS
- Dependencies: None
- Authorized actions: Read-only Git/docs/evidence
- Tests / acceptance: Locate correct worktree and preserve all edits
- Evidence / actual result: 408278c ancestry and existing verification receipts recovered
- Commit: baseline 408278c where applicable; new commits pending
- Blockers: None
- Next task: M1

## M1 â€” Review code and security

- Status: IN_PROGRESS
- Dependencies: M0
- Authorized actions: Focused local inspection/fixes
- Tests / acceptance: Concrete findings fixed with affected tests
- Evidence / actual result: Maintenance/TLS changes already tested; remaining API/loader/frontend review ongoing
- Commit: baseline 408278c where applicable; new commits pending
- Blockers: None
- Next task: Review transfer controller safety

## M2 â€” Finish migration tooling

- Status: PENDING
- Dependencies: M1
- Authorized actions: Local code and synthetic local fixtures only
- Tests / acceptance: Identity, empty-target, schema, validation, one-attempt ledger and redacted failures
- Evidence / actual result: Existing local controller PASS; cloud-specific controller incomplete
- Commit: baseline 408278c where applicable; new commits pending
- Blockers: Real execution requires privacy/import approval
- Next task: Build offline-testable controller

## M3 â€” Privacy decision

- Status: BLOCKED
- Dependencies: M0
- Authorized actions: Read-only documentation/research
- Tests / acceptance: Honest documented risk and owner choice
- Evidence / actual result: cloud-privacy-decision.md; observed synthetic primary-error exposure
- Commit: baseline 408278c where applicable; new commits pending
- Blockers: Owner acceptance or provider-supported protection
- Next task: Do not upload real data

## M4 â€” Render networking

- Status: PASS
- Dependencies: M0
- Authorized actions: Read-only metadata/proposal
- Tests / acceptance: Exact CIDRs, Free compatibility, administrative recovery
- Evidence / actual result: 74.220.52.0/24 and 74.220.60.0/24 verified; private proposal NOT applied
- Commit: baseline 408278c where applicable; new commits pending
- Blockers: Live allowlist change requires approval
- Next task: Reverify at approved window

## M5 â€” Backend verification

- Status: PENDING
- Dependencies: M1/M2
- Authorized actions: Focused isolated tests and local package
- Tests / acceptance: Affected tests, safe packaged artifact, honest JDK/runtime evidence
- Evidence / actual result: Prior 15 gate/security +26 cloud integration tests retained
- Commit: baseline 408278c where applicable; new commits pending
- Blockers: Only JDK22 located so far; exact Java21 runtime/container not yet verified
- Next task: Inspect available runtimes and run affected tests

## M6 â€” Frontend verification

- Status: PENDING
- Dependencies: M0
- Authorized actions: Local tests/build/lint and disposable synthetic browser flows
- Tests / acceptance: Authentication/routes/disabled email/accessibility compatible
- Evidence / actual result: Existing test suites and browser configs discovered
- Commit: baseline 408278c where applicable; new commits pending
- Blockers: None
- Next task: Run focused product tests, lint and build

## M7 â€” Release integration

- Status: PENDING
- Dependencies: M2/M5/M6
- Authorized actions: Local artifacts/configuration checks
- Tests / acceptance: V11 lineage, JWT and gated startup coordinated
- Evidence / actual result: cloud-final-migration-window.md; Docker TLS/gate prepared
- Commit: baseline 408278c where applicable; new commits pending
- Blockers: No image/deployment verification yet
- Next task: Verify packaging and release scripts

## M8 â€” Migration checklist

- Status: PENDING
- Dependencies: M2/M7
- Authorized actions: Plan/dry run only
- Tests / acceptance: Fresh frozen snapshot, import, reconciliation, recovery gates
- Evidence / actual result: Existing one-window runbook retained
- Commit: baseline 408278c where applicable; new commits pending
- Blockers: Actual production operations prohibited
- Next task: Integrate reviewed controller and evidence

## M9 â€” Final quality gate

- Status: PENDING
- Dependencies: M1-M8
- Authorized actions: Targeted evidence review
- Tests / acceptance: No critical unreported gaps; accurate GO/NO-GO
- Evidence / actual result: Production NO-GO until owner decisions
- Commit: baseline 408278c where applicable; new commits pending
- Blockers: Privacy/release boundaries
- Next task: Audit evidence and stage only reviewed files

## M10 â€” Engineering report and handoff

- Status: PENDING
- Dependencies: M9
- Authorized actions: Documentation and local commit
- Tests / acceptance: All 14 requested report fields and resumption point
- Evidence / actual result: Trackers created
- Commit: baseline 408278c where applicable; new commits pending
- Blockers: None
- Next task: Report after safe tasks complete or blocked
