# Production release handoff — 7 November 2026

Prepared locally; no release or production database operation is authorized. Work in
`audit-output/m1a`, branch `codex/password-recovery-backend`. Application candidate:
`afb782432bf08fdbca9b0eddf15d713a5e7b9025`. Later readiness commits add tests/docs only.
Use `git rev-parse HEAD` to identify the exact reviewed release commit before approval.

## Evidence and outstanding gates

The deployed baseline supplied by the owner is
`418d757855dd10965c2872176022f93ac5e35a8a`; it is an ancestor of HEAD.
M1A `19a0c04`, M1B `6fe4ae3`, M2 `025d521`, M3 `d16a097` and M4 `afb7824`
are all ancestors. Every migration present at the deployed baseline is byte-for-byte
unchanged (`git diff` using the baseline's exact migration path list is empty).
The original checkout's unrelated test edit and untracked progress file are preserved.

Reuse the existing focused verification rather than running the entire suite:

- MySQL V7→V8, V8→V9 and V9→V10 upgrade tests preserve prior Flyway checksums,
  user hashes, membership and financial fixtures. V10 schema test checks ten
  migrations, 21 foreign keys and established DECIMAL(19,2) amounts.
- Latest recovery evidence: 8 H2 recovery + 3 email composition tests and 8 real
  MySQL recovery/JWT tests passed (`backend/phase4-recovery.log`). Includes replay,
  concurrency, old JWT rejection, new login and generic unavailable-provider behavior.
- Frontend 50 relevant scenarios were verified across the final affected suites:
  initial combined run had a selector collision; corrected group-suite rerun passed
  all 16 affected tests (`frontend/phase4-groups.log`). Lint/build passed. Backend
  package passed (`backend/phase4-package.log`). These are prior verified results,
  not newly repeated tests or an assertion that the first combined run passed.
- Real isolated Edge product/responsive flows: 2 passed (`phase4-e2e.log`);
  final timestamp/product regression: 1 passed (`phase4-clock-e2e.log`). Existing
  invitation and notification flows also passed. Test containers/volumes removed,
  original MySQL listener preserved. No live email was involved.
- Release-specific permission test: `ReleaseMigrationPermissionsIT` exercises the
  direct V7→V10 upgrade with a schema-scoped migrator, old checksum/data preservation,
  new-table DML, and denial of runtime CREATE/ALTER. See the current progress entry
  for its result: 1 passed, no failures/errors/skips. This does not establish actual
  Aiven permissions. Flyway emits an existing warning that its formally tested MySQL
  support ends at 8.1; the actual MySQL 8.4.13 upgrade/validation succeeded. No dependency
  upgrade is part of this release; verify the live engine/version at preflight.

Live Aiven grants, current production Flyway history, backup recoverability and
actual deployed SHA have not been independently queried in this readiness pass.
They are mandatory release preflight gates, not assumptions established by local tests.

## Brevo account boundary

Signed-in account pages show **Free plan**, **300/day** shared marketing/transactional
emails, full remaining quota, and payment setup incomplete with no configured payment
method. No upgrade, billing setup, subscription, domain purchase or message was initiated.
The SplitSmart freemail sender is **Verified**, with default DKIM and a freemail/DMARC
warning. This is mailbox ownership verification, not proof of transactional activation
or permanent domain-free sending eligibility. Transactional onboarding has no send logs.

The homepage still requires phone verification. Its open phone dialog explicitly says
verification is needed to create an API key and send messages. The owner must complete
phone/OTP verification privately. If provider review requires account approval, the
owner handles that boundary. Stop this track if a purchased/authenticated domain or
payment becomes mandatory; do not bypass restrictions. Sender verification alone
does not establish that its current freemail warning is merely advisory for this account.

Keep `RECOVERY_EMAIL_ENABLED=false` and `RECOVERY_EMAIL_DELIVERY_VERIFIED=false` in
production. No API key has been read, generated, stored or requested in chat. After
eligibility, the owner configures `BREVO_API_KEY` privately in an ignored local secret
environment or separately approved host secret settings, never a shell argument,
build argument, frontend VITE variable, screenshot, log or Git file. Sender and exact
HTTPS `RECOVERY_FRONTEND_ORIGIN` use the existing adapter contract.

Before sending, obtain explicit approval naming ONE recipient inbox and one reset
message. No recipient has yet been authorized. Inspect actual delivery, provider
approval and safe unwrapped fragment link privately; no token screenshots or logs.
Use an isolated disposable account, not an unsuspecting existing account. The current
app also queues a password-change notice after successful reset: approval for one
reset message does **not** authorize that second message. Before completing a live
flow, obtain separate notice approval or use a reviewed test-only transport that
suppresses the notice without weakening production reset logic. Never toggle
production delivery flags just to test eligibility. HTTP 201/202 is not inbox proof.

## Additive schema and minimum permissions

V8 adds default-zero `users.auth_token_version`, nullable request metadata and hashed
reset-token storage. V9 adds nullable email-proof/revocation fields plus hashed private
invitation/email-proof tables. V10 adds recipient-owned notifications, foreign keys and
indexes. Existing users, balances, expenses, settlements and memberships are retained;
no applied checksum is repaired, no table is truncated, and no financial column is changed.
Old pending email invitations require the new mailbox proof; existing memberships remain.

Apply exactly 8, 9, 10 in order after validating successful history 1–7. Use the existing
schema and separate existing migration identity. The tested migration privileges are
schema-scoped SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES. They
include Flyway history access; no global grant, GRANT OPTION, DROP, clean, repair,
baseline or out-of-order execution is needed. Runtime needs schema-scoped
SELECT/INSERT/UPDATE/DELETE covering new tables; table-specific grants must explicitly
include these additions. It must be denied DDL. Do not provision a new database or user
automatically, replace credentials, or give Render the migration/admin login.

At approved preflight, inspect `SHOW GRANTS FOR CURRENT_USER` under each existing
identity privately, engine/version, schema identity, successful history/checksums,
available storage and long-running transactions. Stop for unsupported engine behavior
or insufficient privileges; obtain separate approval for exact grant adjustments.
Retain VERIFY_IDENTITY TLS and existing CA truststore for both clients. A schema-wide
DML grant covers new tables; a local test cannot prove production has that grant.

MySQL DDL implicitly commits; V8–V10 are not an all-or-nothing transaction. ALTER can
wait on metadata locks and consume storage. Set a short migration-session metadata
lock timeout and schedule a write-quiesced maintenance window; do not kill unrelated
transactions. On partial failure, keep traffic closed and inspect actual schema/history.
Prepare an exact reviewed forward reconciliation; never blindly rerun, DROP new data,
edit applied migration text or invoke Flyway repair to conceal partial state.

## Authentication and rollback security

New JWTs carry numeric `ver`. Every authenticated request checks the current user's
database version without a revocation cache. Missing legacy `ver` means zero and is
accepted only while the user's stored version is zero. Reset increments the version
atomically with BCrypt password update and token consumption; DB failure fails closed.
This preserves existing sessions at rollout until their account resets.

**Never roll the backend back to deployed V7 after a password reset.** V7 ignores the
version column and can revive a previously revoked JWT even when the database stays
at V10. Keeping email disabled does not make such a rollback safe: already issued
reset links, password changes and existing version fences still matter.

Preferred recovery is a forward fix based on this release, keeping version validation,
single-use reset transactions and invitation consent/ownership protections. Keep the
additive V10 schema. Static frontend rollback may retain this secure backend, provided
its old client flow/API behavior is checked first; the old direct-add-member action now
correctly returns conflict. Do not claim an untested old backend is a safe fallback.
If the secure backend cannot serve, suspend public authenticated traffic during repair
rather than route to an unversioned artifact. Render rollback to the pre-release backend
is explicitly prohibited; no automatic rollback is approved. Save the reviewed release
artifact/commit for reproducible forward fixes. No ready alternate backend artifact has
been certified in this pass.

Never decrement/restore auth versions as a routine rollback. A database restore can
revive old tokens AND lose newer financial writes. Before any separately approved
disaster restore: stop all writers and ingress; preserve the incident database and
post-backup changes securely; reconcile financial writes and monotonic per-user auth
version fences before reopening. If those fences cannot be recovered reliably, remain
closed until an explicitly approved JWT-signing-key rotation invalidates all sessions.
Rotation is a separate credential-changing emergency action, prohibited by the present
authorization; do not perform it automatically. Revoke restored reset/email-proof/link
tokens as part of the reviewed recovery. Validate restored schema/history and test old
JWT rejection before reopening. Never restore an old snapshot directly under live traffic.

## Approval-dependent release sequence

1. Approve the exact candidate SHA and private push/merge scope separately. Keep GitHub
   private and hosting auto-deploy off. No release/push/merge has been performed.
2. Approve private read-only production preflight and a maintenance window. Confirm the
   current backend/history are the expected V7 baseline. Record a private financial
   reconciliation baseline and pending-token/auth-version state. Confirm a usable
   encrypted logical backup of the existing schema and actual restore procedure, storage
   destination/access/retention and sufficiency of existing free backup options. Obtain
   approval for backup access/export; never put production data in Git or chat. No backup
   or restore was performed here. Stop if safe recovery would require spending/new service.
3. Quiesce application traffic/writes using an approved existing host control (do not
   invent a maintenance feature). With the private migration identity, run Flyway validate
   against unchanged V1–V7, then migrate only through V10. Verify history, checksums,
   preserved baseline rows/totals and defaults. Do not run migration via the runtime role.
4. Deploy the approved Java 21 Render backend to the existing Free service with automatic
   Flyway disabled (`SPRING_FLYWAY_ENABLED=false`), existing runtime credentials/TLS/CORS,
   existing JVM/PORT/start command, and both email flags false. Do not change JWT_SECRET.
   Wait for DB-backed `/actuator/health/readiness`; cold start may be slow. Ensure every
   old instance has stopped serving authenticated traffic before enabling reset delivery.
5. Deploy the approved static React build to the existing Cloudflare Pages Free project;
   preserve exact public `VITE_API_BASE_URL`, nested-route fallback and exact CORS origin.
   No backend secret belongs in Pages. Retain prior static artifact for a compatible
   frontend-only rollback; keep the secure backend running.
6. With explicitly authorized disposable accounts, verify existing login/expense/balances,
   consent-based invitation acceptance, recipient-only notifications, and disabled email's
   generic unavailable response. Reopen traffic after passing checks; avoid touching
   existing users' data. Delete only approved test-owned records using application rules.
7. Separately approve real-email configuration/test after Brevo prerequisites. Complete
   inbox/link/single-use reset and old-JWT/new-login verification privately, accounting for
   the second password-change notice. Only after that succeeds obtain approval to enable
   production email. If live verification must occur on production, authorize that narrow
   controlled window explicitly; delivery is otherwise kept off. No blanket enablement
   follows from account verification or an isolated test.

Remaining approvals are distinct: human phone/OTP/provider approval and private key
configuration; named inbox/message approval; production read-only preflight/backup;
any necessary exact privilege adjustment; private push/merge; production maintenance,
V8–V10 migration; Render deployment; Pages deployment; and final email enablement.
No paid service, new database/domain, public release or credential replacement is included.
