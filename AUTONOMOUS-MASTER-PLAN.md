# Autonomous master plan

8 November 2026. Active worktree: `audit-output/m1a`, branch `codex/password-recovery-backend`. Baseline `408278cc9f378c0a1a4357160d46fc8ee54025bf`. Root worktree is NOT the release branch.

Only local/synthetic work is authorized. No real cloud import, production freeze/write, deployment, live config change, push/merge, spending or privacy acceptance. Existing runbooks are linked rather than duplicated.

## M0 — Recover authoritative state

- Objective: Recover authoritative state
- Status: PASS
- Dependencies: None
- Authorized actions: Read-only Git/docs/evidence
- Tests / acceptance: Locate correct worktree and preserve all edits
- Evidence / actual result: Verified root and release worktrees; preserved unrelated edits; recovered 408278c/cee75ef/9af155e receipts without repeating real rehearsals.
- Commit: b7fb3560af897865e09206350df84e6aaa603cfc for this milestone evidence/tooling; earlier baseline408278c retained
- Blockers: None
- Next task: Read final handoff

## M1 — Review code and security

- Objective: Review code and security
- Status: PASS
- Dependencies: M0
- Authorized actions: Focused local inspection/fixes
- Tests / acceptance: Concrete findings fixed with affected tests
- Evidence / actual result: Scoped review fixed native NOTICE leakage, strict V7 source mapping, runner environment inheritance and false-success/log-buffer risks. No full penetration-test or CVE certification claimed.
- Commit: b7fb3560af897865e09206350df84e6aaa603cfc for this milestone evidence/tooling; earlier baseline408278c retained
- Blockers: No remaining authorized concrete finding
- Next task: Retain focused test evidence

## M2 — Finish migration tooling

- Objective: Finish migration tooling
- Status: BLOCKED
- Dependencies: M1
- Authorized actions: Local code and synthetic local fixtures only
- Tests / acceptance: Identity, empty-target, schema, validation, one-attempt ledger and redacted failures
- Evidence / actual result: Synthetic core and metadata-only cloud preflight verified; 11 controller tests and one new local nine-table fake transfer PASS. Exclusive durable ledger, table/advisory locks, transactional sequence restart and commit-uncertainty handling verified.
- Commit: b7fb3560af897865e09206350df84e6aaa603cfc for this milestone evidence/tooling; earlier baseline408278c retained
- Blockers: Real-cloud execution adapter remains deliberately disabled pending owner privacy choice/import approval
- Next task: Review policy before enabling any production adapter

## M3 — Privacy decision

- Objective: Privacy decision
- Status: BLOCKED
- Dependencies: M0
- Authorized actions: Read-only documentation/research
- Tests / acceptance: Honest documented risk and owner choice
- Evidence / actual result: Existing primary-error exposure findings and finite cloud ICU evidence remain valid; no real data sent.
- Commit: b7fb3560af897865e09206350df84e6aaa603cfc for this milestone evidence/tooling; earlier baseline408278c retained
- Blockers: Owner privacy choice or supported verifiable mitigation
- Next task: Review cloud-privacy-decision.md

## M4 — Render networking

- Objective: Render networking
- Status: PASS
- Dependencies: M0
- Authorized actions: Read-only metadata/proposal
- Tests / acceptance: Exact CIDRs, Free compatibility, administrative recovery
- Evidence / actual result: Render Singapore Free shared CIDRs and private additive proposal/recovery verified earlier this release period; no change applied.
- Commit: b7fb3560af897865e09206350df84e6aaa603cfc for this milestone evidence/tooling; earlier baseline408278c retained
- Blockers: Actual change needs approval and immediate recheck
- Next task: Reverify only at authorized window

## M5 — Backend verification

- Objective: Backend verification
- Status: PASS
- Dependencies: M1 and M2 synthetic core (not production adapter)
- Authorized actions: Focused isolated tests and local package
- Tests / acceptance: Affected tests, safe packaged artifact, honest JDK/runtime evidence
- Evidence / actual result: Existing Temurin21.0.12.1 used; 42 selected Java21 tests and package PASS, zero failures/errors/skips. New synthetic local PostgreSQL DB only. JAR class major65.
- Commit: b7fb3560af897865e09206350df84e6aaa603cfc for this milestone evidence/tooling; earlier baseline408278c retained
- Blockers: Linux container belongs to M7
- Next task: Use private artifact manifest

## M6 — Frontend verification

- Objective: Frontend verification
- Status: PASS
- Dependencies: M0
- Authorized actions: Local tests/build/lint and disposable synthetic browser flows
- Tests / acceptance: Authentication/routes/disabled email/accessibility compatible
- Evidence / actual result: Lint/build PASS, 20 React cases PASS, two mocked mobile/desktop flows and two actual Java21/native-PG product/circular-debt browser flows PASS. Configured local dist uses current public HTTPS API and email false.
- Commit: b7fb3560af897865e09206350df84e6aaa603cfc for this milestone evidence/tooling; earlier baseline408278c retained
- Blockers: No production browser smoke authorized
- Next task: Retain current artifacts

## M7 — Release integration

- Objective: Release integration
- Status: BLOCKED
- Dependencies: M2 core, M5 and M6
- Authorized actions: Local artifacts/configuration checks
- Tests / acceptance: V11 lineage, JWT and gated startup coordinated
- Evidence / actual result: Local artifact audit verifies JAR migrations, original MySQL V1–V7 ancestry/preservation and frontend config. Native Java21 application startup PASS.
- Commit: b7fb3560af897865e09206350df84e6aaa603cfc for this milestone evidence/tooling; earlier baseline408278c retained
- Blockers: Docker timeout; Linux image/UID/container TLS and live Render/Pages NOT TESTED. Privacy/release adapter approvals absent.
- Next task: No Docker reset; resolve capability safely before release

## M8 — Migration checklist

- Objective: Migration checklist
- Status: PASS
- Dependencies: M2 safety evidence and M7 local artifacts; planning may finish before actual image
- Authorized actions: Plan/dry run only
- Tests / acceptance: Fresh frozen snapshot, import, reconciliation, recovery gates
- Evidence / actual result: Existing one-window checklist coordinated with new safety engine; final-source freeze/fresh snapshot, validation, backup, JWT and forward recovery gates documented. No execution performed.
- Commit: b7fb3560af897865e09206350df84e6aaa603cfc for this milestone evidence/tooling; earlier baseline408278c retained
- Blockers: Actual window needs explicit scoped approval
- Next task: Read coordinated approval document

## M9 — Final quality gate

- Objective: Final quality gate
- Status: PASS
- Dependencies: M1-M8
- Authorized actions: Targeted evidence review
- Tests / acceptance: No critical unreported gaps; accurate GO/NO-GO
- Evidence / actual result: Targeted tests/artifact/secret/index checks PASS; engineering decision is production NO-GO, not a release certification.
- Commit: b7fb3560af897865e09206350df84e6aaa603cfc for this milestone evidence/tooling; earlier baseline408278c retained
- Blockers: Critical privacy/adapter/container gates remain
- Next task: Do not deploy or upload

## M10 — Engineering report and handoff

- Objective: Engineering report and handoff
- Status: PASS
- Dependencies: M9
- Authorized actions: Documentation and local commit
- Tests / acceptance: All 14 requested report fields and resumption point
- Evidence / actual result: Persistent trackers, report and resumption instructions complete; local verified code commit b7fb3560af897865e09206350df84e6aaa603cfc.
- Commit: b7fb3560af897865e09206350df84e6aaa603cfc for this milestone evidence/tooling; earlier baseline408278c retained
- Blockers: Owner decisions/external capability
- Next task: Read AUTONOMOUS-HANDOFF.md and blockers
