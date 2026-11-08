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

## Linux recovery and privacy inquiry — latest 8 November 2026

Owner explicitly approved an isolated official Linux JDK21 installation, synthetic Linux-native verification and one no-cost provider inquiry. Temurin21.0.13+9 installed under `/root/.local/share/splitsmart/java21` inside existing Ubuntu x86_64; official GitHub archive SHA256 verified. `java -version` and `javac -version` PASS. Adoptium API403 was bypassed only by using its official distribution repository, never by weakening TLS. No Windows Java/PATH, Ubuntu system packages, Docker/WSL configuration or existing database data changed.

LINUX-NATIVE PASS: actual release JAR started with `postgresql,postgres-prod` against the existing nonce-guarded cloud SYNTHETIC test DB and separate restricted roles. JDBC CA+hostname verify-full identity PASS; missing/invalid CA rejected; Flyway validated current V11/no pending migrations; readiness (includes DB), liveness and full maintenance gate PASS. Email disabled. Owned JVM stopped and actual listener absence verified. No real records or cloud real-data import. Successful logs stayed in bounded RAM; diagnostic failures are DPAPI encrypted only. Synthetic harness initially failed its socket check; fixed dynamic port selection and listener verification rather than treating TIME_WAIT as a live process. This is not a production application failure.

Runtime receipt: Ubuntu `/root/.local/share/splitsmart/native-verification/result.json`. Public JAR SHA256 `a2011578a7784b1fa7833720271a0dace75f0052e9551c3aea12dc054758abb5`. Final endpoint hardening matches the executed destination exactly;4 new synthetic destination-guard unit tests PASS. The runner refuses replay after its successful receipt. Do not delete existing fixtures or rerun imports. Existing recovery databases/backups/GPG keyring untouched.

CONTAINER BLOCKED: one Docker version call timed out after8s; no more Docker troubleshooting, restart/reset/prune/reinstall/config edits. Linux-native root-user startup does not verify image build, UID10001, /app layout or Render deployment.

Provider inquiry BLOCKED/NOT SUBMITTED: authenticated official Support Center opened; Create ticket offers only required Basic tier, which official terms associate with paid subscriptions. Free excludes support services. No tier selected or billing/trial used; no reference issued. Approved nonsensitive draft is docs/aiven-primary-error-inquiry.md. A no-cost official channel or provider clarification is needed; no support entitlement invented. Logging protection remains UNRESOLVED and owner policy B remains binding. No repeated fake leak probe or configuration experiment.

Aiven authentication remains COMPLETE; do not reauthenticate. Prior metadata-only preflight PASS and all existing Java/UI/browser/adapter evidence retained. Production NO-GO: verified privacy control, actual container execution, reviewed approval-bound real adapter and explicit coordinated release approvals remain required. No privacy acceptance, production write/freeze/upload/deploy/push/merge. ₹0; email disabled.

Focused Linux unit verification COMPLETE: existing Maven3.9.9 launcher with Linux Temurin21.0.13+9, new private Linux source copy, sanitized environment/no DB credentials, PostgresProfileTest1 + MaintenanceGateTest5 =6 PASS,0 failures/errors/skips; BUILD SUCCESS. Initial offline resolution failed due repository-origin cache metadata; normal official Maven Central resolution succeeded, no application fix needed. Prior broad suites were not repeated. Four new destination-guard tests PASS. The only new production release blocker resolved here is Linux-native execution; container and policy B remain unmet.

## Final infrastructure/privacy feasibility — 8 November2026 (authoritative latest)

Recovered actual HEAD4e1acc5 after reported c80b07a, preserved unrelated work. One Docker8s timeout only; no more troubleshooting. Ubuntu no existing OCI tools, no nonroot login identity/subuid/subgid/mapping helpers. Harmless unprivileged namespace probe PASS (kernel prerequisite only), cgroupv2, about6.7GiB available RAM/28GiB physical disk; private preparation directory0700. Rootless setup requires a separately scoped dedicated locked account, subordinate ID mappings and privileged mapping helpers beyond package-only/no-escalation permission. No packages/users/mappings/security changes made; no OCI build/start attempted. Linux-native PASS retained, container BLOCKED.

Official provider/control review: primary-error redaction UNRESOLVED; Free docs do not grant technical support, no new inquiry sent. Aiven/Neon/Supabase controls reviewed do not establish the strict guarantee. Recommend C (defer new cloud copy now), retaining B as required protection; A remains an explicit owner policy change, never inferred. This is not a claim existing MySQL is off-cloud or postponement preserves availability through21 December2026.

No new product defect found in scoped adapter/entrypoint/TLS/credential/email review. Real/cloud transfer gate remains hard-disabled; commit/rollback ambiguity never retries. Existing local real rehearsal, synthetic adapter and Linux/UI/backend evidence retained, not rerun. This continuation executed infrastructure/namespace probes only; no new application/integration tests or cloud queries/imports. All release actions still separately gated; production NO-GO/email disabled. Detailed feasibility/options and exact rootless security scope: docs/final-infrastructure-privacy-assessment.md. Existing draft retained; no paid support/account/login action.

## Rootless OCI milestone — latest8 November2026

Owner approved locked Ubuntu identity/subordinate mappings and verified package installation. Setup completed: Podman5.7.0/crun1.21, rootless overlay, new nologin/no-sudo UID/GID2001, unused subordinate UID/GID100000–165535, private home/runtime storage;0 upgrades/removals,13 new signed Ubuntu packages. No Docker Desktop/WSL registration/Windows account/security or unrelated identity/database changes. Existing backups/keyring/local recovery untouched.

Minimal actual rootless nonroot container PASS. Actual production multi-stage Dockerfile BUILD PASS after the reproduced short-name resolution failure was fixed by qualifying only both official docker.io/library base images. Image ID sha256:9d9db0ab4fc478fa22c92a55bf75da66b06dbfe93fbdb63f5e2a2465ff101e28; source-only context/no build secrets. Four actual synthetic application-container scenarios PASS: maintenance/readiness/liveness, anonymous protected API401 plus email-disabled recovery503, invalid-CA rejection, mapped hostname-mismatch rejection. Java21, exact V1–V11 metadata before/after, UID/GID10001, CA0600, cap-drop/no-new-privileges, read-only/private tmpfs and loopback-only ports verified. Temporary Podman secrets removed;0 running task containers; stopped fixtures/images retained.

New harness issues (NUL escape and WSL expansion of shell syntax in executable source arguments) were diagnosed and fixed with a file-based public verifier/private stdin split; failed attempts retained, owned cleanup performed. Final successful attempt ssverify-20261108-oci4/result.json in dedicated user's home; private Windows rootless-check-result.json. No blind rerun.10 focused guard/transport tests PASS; previous unaffected backend/frontend/browser/rehearsal suites not repeated. Actual Render deployment/kernel/cgroups remain unverified: rootless test used cgroups disabled/JVM heap256MiB. No deployment/push/merge/production operation occurred.

Owner now explicitly selects Option C: no real production financial records uploaded to PostgreSQL cloud, stronger protection remains desired. No new logging probe/support attempt. A separate synthetic-only portfolio is viable to PLAN, but current app accepts visitor identity/free-text inputs; fake seeds/disclaimer alone do not prevent real data. Safe demo safeguards/resource/quota plan need separate review/approval, not automatic use of live/recovery/reserved application DB. See docs/rootless-container-verification.md and docs/synthetic-portfolio-release-plan.md. Production remains NO-GO/email disabled. No more rootless setup approval is needed; next owner decision is local-only demo implementation versus continued hold, with deployment/real migration still separate.
