# SPLITSMART AUTONOMOUS ENGINEERING REPORT

Latest resumed A–D gate evidence: code `4373ac57926caa178d11f77132b97cfcbfca6d75`. Linux remains BLOCKED; adapter review/synthetic scope PASS; C/D owner package complete.20 affected Python tests, one empty-reference Flyway test and one changed-code synthetic transfer PASS. Physical schema contract matches pinned cloud SQL metadata; management API/CLI401 is a new separate blocker. No real data uploaded. See the latest handoff and owner decision package; historical evidence below remains valid.

8 November 2026. **Final assessment: NO-GO for production.** Safe local and
synthetic engineering work completed; privacy, execution and container gates
remain. Nothing was deployed, pushed, merged, uploaded as real cloud data or
changed in production. Spending: ₹0.

## 1. Initial project state — VERIFIED

Recovered release worktree `audit-output/m1a`, branch
`codex/password-recovery-backend`, baseline
`408278cc9f378c0a1a4357160d46fc8ee54025bf`. Root workspace is on a different
branch with unrelated edits and was preserved. Earlier cee75ef/9af155e and
preservation/role/ICU/real-local-rehearsal evidence were reused. Production MySQL
V7 is the previously verified baseline; no fresh production SQL was required
in this execution. Cloud V1–V11 was checked by new record-free metadata queries.

## 2. Milestones completed

- M0 state recovery: PASS.
- M1 scoped code/security review and fixes: PASS, not a security certification.
- M2: synthetic transfer core/preflight PASS; production adapter BLOCKED pending
  policy/explicit release scope. Real/cloud write execution is hard-disabled.
- M3 privacy: BLOCKED, owner decision absent.
- M4 networking proposal: PASS, not applied; reverify at an approved window.
- M5 Java21 backend and M6 frontend verification: PASS.
- M7 artifacts PARTIALLY VERIFIED; Linux container/live hosting BLOCKED.
- M8 migration checklist, M9 honest quality gate and M10 handoff: PASS as plans
  and evidence, not permission to release.

Every milestone's dependencies, authorization, acceptance, results and next task
are in AUTONOMOUS-MASTER-PLAN.md. Persistent progress, decisions, blockers and
handoff files are maintained beside this report.

## 3. Code changes

Added a provider-independent synthetic transfer engine, cloud metadata-only
preflight, native synthetic fixture/browser runners, release artifact auditor and
focused failure/isolation tests. Added responsive email-disabled/maintenance
browser checks without production API access. Existing business logic, entities,
financial calculations and original migrations were not rewritten.

## 4. Concrete bugs/safety gaps fixed

- Native libpq's default NOTICE handler could print values outside SQLSTATE-only
  reporting; the shared connection wrapper now retains a discard callback.
- V7 field mapping could accept newer auth columns; exact field subtraction now
  rejects them rather than silently risking token-version loss.
- New transfer controller uses durable exclusive no-replay ledger, advisory and
  NOWAIT table locks, SERIALIZABLE transaction and transactional sequence RESTART.
- COMMIT_UNKNOWN, COMMITTED_UNVERIFIED and ROLLBACK_UNCONFIRMED are distinguished;
  connection failure cannot be presented as successful rollback or safe retry.
- Native test runner scrubs inherited Spring/JVM/database/email configuration.
  Streaming credential scans cover chunk boundaries and buffer eviction; success
  is published after process cleanup and port checks. Each run preserves its own
  report directory, preventing stale PASS from standing in for a failed attempt.
- Windows text-encoding damage in new tracker updates was caught and corrected
  to explicit UTF-8. No application or database effect.

## 5. Actual tests executed

- **42 Java21 tests PASS**, zero failures/errors/skips; `package` PASS. Includes
  recovery/JWT8, invitations8, notifications6, financial4, maintenance7, security8
  and PostgreSQL profile1. Used a new SYNTHETIC local PostgreSQL database only.
- **11 new transfer unit cases PASS**, including denial before real/cloud
  connection, identity/checksum drift, no replay and commit/rollback uncertainty.
- **2 native-runner isolation cases PASS**.
- **1 new nine-table synthetic local transfer PASS**, with canonical hashes,
  finance, legacy history and identity-floor verification. No real-data rehearsal
  repeated. Native NOTICE sink and all-table lock permission/rollback also PASS.
- **Cloud metadata-only preflight PASS**: TLS, correct empty application database,
  role, schema and actual migration checksums. No source record parameter exists.
- **20 focused React tests PASS**, lint PASS, production build PASS.
- **2 mocked responsive browser scenarios PASS** at375/1280px: keyboard focus,
  disabled email, maintenance errors, no mutation retry and protected routes.
- **2 real browser product scenarios PASS** on Java21/native PostgreSQL with fake
  accounts: invitation acceptance, notifications/deep links, dashboard balances
  and four-person ₹50 circle with zero suggestions and four original obligations.
  Reran only those two after runner hardening; do not count reruns as new cases.
- **Local artifact audit PASS**: class major65, packaged PostgreSQL migrations,
  original MySQL V1–V7/production ancestry and explicitly email-disabled HTTPS
  frontend configuration. Secret/index/whitespace review PASS before commits.

Retained valid earlier evidence: 23 validator tests; 26 cloud synthetic integration
tests; 13 Unicode cases and9,025 ASCII comparisons; seven POSIX TLS-startup tests.
These were not indiscriminately rerun. Initial sandbox Maven/Temp failures were
environment access issues; bounded execution using the normal user cache/Temp
resolved them. No final failing tests remain.

## 6. Database migration readiness — PARTIALLY VERIFIED

New core verified with fake records on local PostgreSQL, all nine source tables,
independent active V1–V11 versus archived V1–V7 history and sequence floors.
Cloud preflight confirms empty V11 schema/checksums without uploads. It does not
prove an eventual real cloud load. Existing engine deliberately rejects REAL
classification and cloud-write targets. A reviewed final execution adapter must
bind explicit owner approval, exact target/release and a NEW frozen snapshot.
Do not remove isolation guards or substitute a stale rehearsal copy.

## 7. Security and privacy — BLOCKED for real cloud data

Unexpected PostgreSQL primary error messages can expose values despite validated
parameterized input, TERSE and zero error-bind logging. Possible framework logs
are a separate surface. Existing provider controls do not establish universal
redaction/all-copy deletion. Owner privacy decision is still required; the agent
did not accept it. Finite ICU agreement does not prove all Unicode equivalence.
No new real-data query/upload/error probe was used to test cloud behavior.

Manual review and existing compiler/lint/security tests were used. No configured
PMD/SpotBugs executable workflow was found and no new tools/dependencies were
installed merely to claim a scan. Full penetration/CVE certification is NOT TESTED.

## 8. Frontend/backend readiness

Backend VERIFIED on existing Temurin21.0.12.1 with Java21 package/startup and
synthetic PostgreSQL API/browser behavior. Frontend VERIFIED for focused tests,
build, local responsive behavior and actual synthetic API integration. Recovery
email remains unavailable accurately; private invitation links still work.
Neither component has been validated after a real production deployment.

## 9. Deployment artifacts — PARTIALLY VERIFIED

Backend JAR retained under backend/target; class major65. Frontend dist retained
with the existing public HTTPS Render API URL and email actions false. Private
SHA-256 manifest: LOCALAPPDATA/SplitSmart/CloudReadiness/release-artifacts.json.
Actual Render-tracked branch differs from this local branch; no push/branch
selection was performed. Reproducible procedure/source/locked dependencies and
artifact identification are documented; bit-for-bit container reproducibility
is not claimed. Docker Engine check timed out after6 seconds; no reset/restart/
update or WSL/Windows security action. Linux image/UID/TLS startup is NOT TESTED.

## 10. Local commits

- Baseline: `408278cc9f378c0a1a4357160d46fc8ee54025bf`.
- Verified tooling/code: `b7fb3560af897865e09206350df84e6aaa603cfc`.
- Final tracker/report commit is a documentation-only descendant. Read current
  `git log -2` for its SHA; this document cannot embed its own changing commit ID.

Earlier pending preservation docs/helpers and unrelated root edits remain
intentionally uncommitted; do not stage or discard them wholesale.

## 11. Remaining blockers

1. Owner privacy choice or verified provider mitigation.
2. Reviewed approval-bound real-import adapter (currently intentionally disabled).
3. Working existing Linux container build capability and actual image/TLS check.
4. Fresh final frozen source snapshot and real import/deployment verification,
   all prohibited until explicitly approved.
5. Brevo phone/transactional approval; email stays disabled independently.

## 12. Exact approval boundaries

AUTONOMOUS-RELEASE-APPROVAL.md prepares one coordinated request. It is NOT a claim
that prerequisites pass or that any operation is authorized. Privacy choice,
private publication/branch selection, target allowlist, maintenance/window, new
backup/restore, real import/target backup, backend/Pages deployment and final GO
remain explicit owner decisions. No billing or paid fallback is permitted.

## 13. Final GO/NO-GO

**NO-GO for production.** Local and synthetic evidence is strong, but passing
tests do not remove privacy or container/execution gates. No unsafe rollback to
V7/stale MySQL after target authentication/financial writes; preserve monotonic
token versions and use controlled forward recovery.

## 14. Next safe execution step

Read AUTONOMOUS-HANDOFF.md and actual Git status. Owner chooses the privacy policy;
existing Docker capability must become safely available before image verification.
Do not repeat successful rehearsals or infer upload/release permission. Complete
the reviewed production adapter only under a resolved policy and explicit scope,
then request the coordinated window. All production and recovery data, encrypted
backups, GPG keys and unrelated edits remain preserved.
