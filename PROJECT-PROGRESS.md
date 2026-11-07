# SplitSmart project progress

## Final Brevo-independent candidate — 7 November 2026: READY locally with email OFF

- Worktree `audit-output/m1a`, branch `codex/password-recovery-backend`; started at clean implementation/readiness baseline `daa1f54671f400d0a33b7490cc03088c8abc0618` plus the already authorized uncommitted support-ticket record, retained below. The final candidate is the local commit containing this entry (`git log -1 --format=%H`); no push/merge/deployment. Original checkout's unrelated MySQL test edit and untracked M0 progress file remain untouched.
- Dependency decision: M2 private links, M3 persisted notifications and M4 dashboard work without Brevo, **but require the combined backend and V8–V10**. V8's mapped auth-version fields cannot be omitted with email off; V9 supports consent/proof/link metadata; V10 is required by synchronous financial/membership notification writes. M1A–M4 and supplied deployed V7 SHA remain ancestors. No migration or backend production logic changed in this pass.
- Fixed actual disabled-mode UX gaps: new public build flag `VITE_EMAIL_ACTIONS_ENABLED` defaults false; forgot/check-email routes explain unavailability without collecting an address/claiming delivery, email-invitation creation form hidden, proof-request button disabled. Existing pending invitation management and proof-authorized acceptance remain intact. Private links, signup/acceptance, notifications and finance continue normally. This flag controls UI only; both server delivery flags independently stay false, so bypassing UI cannot send mail. Existing direct API email-invitation creation can persist an in-app record but does not deliver email. No bypass of mailbox proof or JWT version checks.
- Render cold-start UX: timeout/network/502/503/504 errors now explain temporary startup and manual retry. No automatic mutation replay, token deletion, artificial uptime polling or idempotency changes. Existing expense/repayment uncertainty handling is preserved; users are told to check history before resubmission. Does not eliminate free-host cold starts.
- Circular debt **PASS** using unchanged net-balance algorithm: A→B→C→D→A, ₹50 each, zero recommendations/₹0 total, all four original expenses and splits retained. H2 API test checks every member, persistence counts and IDs; real Edge/MySQL test checks All settled, no repayment action, unchanged four-record history and zero dashboard owed/receivable. No fabricated settlements or deleted obligations.
- Executed backend focused verification: **13 passed**, zero failures/errors/skips — 2 disabled-provider configuration, 3 JWT version validator, 7 settlement-suggestion/authorization/paise/circular scenarios, 1 disabled-email/private-link/authenticated proof scenario (`backend/email-disabled-backend.log`). Real MySQL new disabled-email/private-link scenario: **1 passed**, zero failures/errors/skips (`backend/email-disabled-mysql.log`). Cached dependencies only, no provider contact.
- Executed frontend verification: initial six affected files **66 passed** (`frontend/email-disabled-ui.log`); after adding stale check-email-state protection, reran only its two affected files **21 passed** (`frontend/email-disabled-final-ui.log`). Final unique aggregate **67 scenarios**, not 87. Includes enabled-mode regression, default-off no-request UI, copy-link capability, cold-start errors without mutation replay/session loss, login compatibility and focus. Final lint and production build passed after the last UI change; no dependencies/lockfile changes.
- Executed real local Edge suite: **2 passed** (`email-disabled-e2e.log`), email forcibly off in backend AND frontend. Invitation-before-registration→explicit acceptance→member/expense notification deep-link→balances and separate four-user cycle passed. V1–V10 schema present. Cleanup confirmed test container/volume removed, test ports free, original MySQL listener preserved and log credential scan PASS.
- Reused still-valid evidence rather than rerunning broad suites: V7→V8/V8→V9/V9→V10 checksum/financial-data preservation; direct V7→V10 schema-scoped migration and DML-only runtime CREATE/ALTER denial (`ReleaseMigrationPermissionsIT`, 1 passed at daa1f54); invitation expiration/revocation/replay/concurrency and notification recipient/read/unread/retention tests; real MySQL password-reset/JWT invalidation/new-login tests; prior mobile/responsive keyboard checks; backend package. No backend runtime source or migration changed since those checks.
- Local documentation updated: README distinguishes historical deployment evidence from owner-supplied production baseline; release runbook now explicitly allows email-OFF release independently of Brevo, defines combined deployment order, backup/grant/DDL preflight, no-V7 rollback, initial smoke/abort criteria, and later three-flag email enablement. No GitHub publication implied. Ticket #5661752 remains pending; email eligibility/delivery not claimed.
- Release gate: READY as a local email-disabled candidate, **not production clearance**. Need approval of exact candidate/private push/merge scope, private production preflight and usable backup/recovery confirmation, any exact schema privilege changes, maintenance/migrations V8–V10, Render deployment then Pages deployment. Current server `RECOVERY_EMAIL_ENABLED=false`, `RECOVERY_EMAIL_DELIVERY_VERIFIED=false`; Pages `VITE_EMAIL_ACTIONS_ENABLED=false`. No key or Brevo approval needed for this release. Never route to unversioned V7 after resets or restore older auth versions under live traffic. Actual live grants/history/engine/storage/backup recovery remain unverified; keep traffic closed on mismatch/partial DDL and forward-fix. Email enablement/test messages remain a separate later approval.

## Brevo support submitted — 7 November 2026, approximately 02:33 IST

- Owner explicitly approved submission through the existing signed-in official support session. Brevo confirmed **"Your ticket has been successfully submitted."** and **"A ticket has been created for this conversation."** Reference: **5661752**, [view request](https://help.brevo.com/hc/requests/5661752).
- Subject: "Help completing phone verification and enabling transactional email on Free plan". Category: Transactional Email & SMTP; topic: Sending & Delivery. Included missing Indian +91 OTP SMS, verified sender, legitimate SplitSmart reset/invitation use, request for verification assistance and eligible Free transactional API activation without subscription, payment method or purchased domain.
- Last actual verification-attempt time was unavailable and was explicitly described as unknown, not invented. No specific error had been visible in the inspected phone dialog; the account still required verification. No phone number beyond +91, passwords, OTPs or API keys included. No attachments, billing/paid-plan activation, SplitSmart code or production changes.
- Next action: await Brevo's reply in the existing ticket/account inbox, then follow any human verification privately. Do not create a duplicate ticket. Sending eligibility remains unconfirmed and application delivery remains disabled; ticket submission is not sending approval. Real test email still requires the owner's named-inbox/message approval.

## Release readiness — 7 November 2026: local PASS, external gates BLOCKED

- Continue in `audit-output/m1a`, branch `codex/password-recovery-backend`. Verified deployed `418d757855dd10965c2872176022f93ac5e35a8a` and M1A/M1B/M2/M3/M4 are all ancestors; application candidate `afb782432bf08fdbca9b0eddf15d713a5e7b9025`. Every baseline migration file is unchanged. Original checkout's unrelated MySQL-test edit and untracked progress file are preserved. No production query/migration/deployment, push/merge, credentials/billing change, new service/database/domain or email send.
- Brevo account-specific check: actual **Free plan**, 300/day shared marketing/transactional quota, payment setup incomplete/no configured payment method. SplitSmart sender is **Verified**, but freemail/default-DKIM/DMARC warning remains. Transactional onboarding has no logs; approval and permanent domain-free sending eligibility are unproven. Phone verification is explicitly required to create an API key and send. Browser handed to owner at phone/OTP dialog. No key read/generated/configured. Asked owner to complete verification privately and explicitly approve one reset message to a named inbox; awaiting response. Stop email track if domain purchase/payment becomes mandatory.
- Delivery and real-inbox password recovery remain BLOCKED; both production email flags remain false. One-message permission must account separately for the password-change security notice automatically queued after a real reset; do not silently send that second message. Existing local reset/JWT/UI verification is reused, not misreported as inbox verification.
- Added one focused `ReleaseMigrationPermissionsIT`: direct V7→V10 using separate schema-scoped migrator preserves old checksums/account/financial fixtures; DML-only runtime can use new tables and increment auth version, but CREATE/ALTER are denied. **1 passed, zero failures/errors/skips**, cached MySQL 8.4.13, disposable localhost container removed by try-with-resources. Log `backend/release-readiness-permissions.log`; command `./mvnw.cmd -B -o -Pmysql-it '-Dit.test=ReleaseMigrationPermissionsIT' test-compile failsafe:integration-test failsafe:verify`. First command had an unquoted PowerShell Maven-property parsing failure before test execution; corrected invocation passed. No broad suites repeated. Existing frontend build/lint, affected frontend tests, recovery/MySQL tests and real Edge product/responsive evidence inspected and reused.
- Flyway warns that the bundled version's formally tested MySQL support stops at 8.1; actual cached MySQL 8.4.13 upgrade/validation tests pass. No dependency upgrade introduced. Confirm live engine/version and validate history before any approved production migration. Actual Aiven grants/storage/backup recoverability have not been verified and remain release gates.
- [Production release runbook](docs/production-release-readiness.md) covers exact approval gates, private backup/reconciliation, nontransactional MySQL DDL/partial-failure handling, separate migrator/runtime rights, Aiven→Render→Pages sequence and email enablement. V8–V10 readiness PASS locally; live preflight pending.
- JWT security strategy: database version checked on every bearer request; legacy zero-version JWTs work until reset. **No rollback to V7/unversioned backend after resets**, and no routine DB/auth-version rollback. Prefer a forward fix retaining security checks and consent; keep expanded schema. Frontend-only rollback requires compatibility checks while secure backend remains. If security-preserving backend is unavailable, close traffic during repair. Snapshot disaster restore requires financial/auth-fence reconciliation; unrecoverable fences require separately approved key rotation before reopening, currently prohibited. No alternate rollback backend artifact is certified.
- Next human action: complete open Brevo phone/OTP verification; confirm domain-free transactional eligibility/approval; authorize one reset email to an exact inbox; configure key privately only after eligibility. Separate approvals are still required for private production preflight/backup, any exact grant adjustment, push/merge, migrations, backend/frontend deployments and final production email activation. Read runbook before resuming; do not rebuild M1B–M4.

## Current handoff — 7 November 2026: safe local product work complete, live email blocked

- Worktree: `C:\Twinklesangani portfolio\SplitSmart\audit-output\m1a`; branch `codex/password-recovery-backend`. Continue here, not the original older checkout. Original root MySQL-test edit and untracked M0 progress file remain unchanged.
- Baseline M1B: `6fe4ae3afa5e0b832bcda1eb51977f26907b0638`; phase-1 blocker record `405b24a`; verified M2 `025d521`; verified M3 `d16a097`; final M4/local recovery closure is the current branch's last commit (`git log -1`). No push, merge, production migration/deployment, credentials/billing changes, domain purchase or paid service.
- Phase 1: BLOCKED on external account prerequisites. User signed in; Brevo showed 300 daily credits and required phone verification before sending. Sender verification/transactional approval, durable domain-free/no-card eligibility, an explicitly authorized test inbox and secure private API configuration remain unverified. No real email sent. Delivery remains disabled. Do not assume account approval from signup or API acceptance.
- M2, M3 and M4: PASS locally. Private-link invitations work without email; email-bound invitations need independent mailbox proof. Notifications and dashboard use persisted authorized application data. Real-delivery password-reset end-to-end testing is still blocked; local backend/UI/fake-delivery flows passed.

## Autonomous completion — phase 4 PASS (dashboard, accessibility and recovery closure)

- Reused authoritative group balance calculations to return per-group positions and net position. Owed/receivable remain separate across groups; partial repayments remain exact BigDecimal/paise amounts. No financial schema, arithmetic, history or applied migration was rewritten.
- Dashboard: real net and per-group balances, explicit expense-group selection, direct Create Group action, first-group onboarding, settlement-suggestion links, useful empty/error/retry states and activity dates. Existing dark theme/components preserved. Group actions and invitation entry are clearer; actual archiving is labelled Archive rather than Delete.
- Mobile navigation: modal semantics, focus containment/return, background inertness, scroll lock, resize recovery and skip navigation. Notification panel and dashboard inspected on mobile/desktop; real keyboard/overflow checks passed.
- Visual review caught ambiguity in naive activity timestamps. Dashboard now reads the native MySQL TIMESTAMP epoch for only the existing ten candidate IDs per activity type/group and emits explicit UTC instants. Bounded queries use existing group/id indexes; no timezone/connection configuration or stored records changed. Both H2 compatibility mode and real MySQL verify the actual recent instant; final browser screenshots show the correct local time. First JVM-zone guess failed the meaningful test and was replaced, not released.
- Renewing an expired email invitation now retains its EXPIRED presentation instead of falsely recording DECLINED. Mailbox proof is invalidated if the stored address changes. Focused regressions cover both.
- Closed recovery guidance: password-change security mail is queued only after the reset transaction commits; provider/queue failure cannot undo the reset or restore old JWTs. No password/reset token appears in that notice. Actual delivery stays behind the existing disabled configuration/approval gate.

### Final focused verification

- 26 backend unit/H2 scenarios across exact-money/settlement, seven invitation cases, six notification/date cases, eight recovery cases and three email-adapter cases passed. Runs were focused; no full backend suite was repeated. Final async-notice observation is asserted on the test thread, not lost in a worker.
- Ten real MySQL scenarios across dashboard/archiving and eight recovery cases passed. Timestamp-specific and after-commit notice checks were rerun after their final changes. No failures/skips remain in the final affected checks.
- 50 frontend scenarios across dashboard UX, product behavior, groups, authentication and notifications verified. Legacy selector expectations were updated for relocated active-group count, clearer group metadata and repeated net/owed amounts; the affected 16-case suite then passed. Lint and production build passed.
- Two real isolated Edge UX scenarios passed: full product/settlement/archive flow and responsive pages/mobile modal keyboard behavior. Product scenario rerun after UTC correction passed and asserts the actual recorded instant. Screenshots inspected under ignored frontend/test-results. Logs confirm test-owned containers/volumes removed, local ports freed, original MySQL listener preserved and credential check passed. Email explicitly forced off in isolated runners.
- Key commands (backend): `./mvnw.cmd -B -o -Pmysql-it -Dtest=InvitationSecurityTest -Dit.test=SplitSmartMySqlIT#dashboardUsesExactScopedTotalsAndExpenseMetadataSurvivesUpdatesAndRetries+groupManagementProtectsHistoryPermissionsAndArchivedGroups test failsafe:integration-test failsafe:verify`; focused NotificationSecurityTest/date and PasswordRecoveryIntegrationTest/RecoveryEmailTest/MySqlIT follow-up runs are recorded in ignored phase4 logs.
- Key commands (frontend/root): `npm run test -- --pool=threads --maxWorkers=1 src/test/dashboardUx.test.jsx src/test/productFeatures.test.jsx src/test/groupManagement.test.jsx src/test/authentication.test.jsx src/test/notifications.test.jsx`; `npm run lint`; `npm run build`; `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-final-release-e2e.ps1 -Suite ux` and final `-Suite product`. Execution-policy override applied only to these reviewed test processes, never system-wide.

### Exact next action / release risks

1. Owner completes Brevo phone and sender verification/transactional approval and confirms zero-cost sending with no purchased domain, card or billing. Explicitly authorize a test inbox. Never send passwords, codes or API keys in chat. Use the existing browser handoff and [email setup instructions](docs/password-recovery-email.md); credentials belong only in ignored local/private settings. No further local feature implementation is needed to reach that boundary.
2. Verify a received plain-text test message/sender/link tracking before declaring delivery verified; then run a separately authorized, isolated actual reset-delivery flow. Current test success does not establish deliverability. Keep both email flags false until that controlled verification/configuration step is authorized and complete.
3. Separately review/approve any Git push/merge and production release. Production is still at the supplied V7 baseline for this work; V8–V10 are local only. Verify real deployed SHA and Flyway history, apply additive migrations using the authorized migration identity, check new-table DML grants and roll out version-aware backend before enabling recovery. Never reset production or edit applied checksums. Old backend rollback after resets can revive old signed JWTs until expiry; do not cross that boundary without an approved revocation plan.
4. Legacy email invitations now require proof and direct-add no longer bypasses acceptance; existing memberships/history remain intact. Private links are the available no-email path. Existing free-host sleep/quotas, best-effort mail queue/restart behavior and bounded notification cleanup remain operational limits, not paid-service guarantees. No provider changes are implied by the local commits.

## Autonomous completion — phase 3 (local verification)

- V10 adds recipient-owned persistent notifications with unique per-recipient event keys, read timestamps and recipient/unread indexes. V1–V9 are unchanged. Events are created synchronously inside the existing expense, settlement, invitation, mailbox-proof and membership transactions; retries are deduplicated and rollbacks leave no ghost updates.
- Generic invitation notifications are created only for proven mailbox owners (or on first verification for pending invitations), avoiding disclosure through self-declared addresses. Expense/settlement/membership notifications are limited to current members, suppress actor noise, and recheck group membership on list/count/read. Removed users receive only a generic membership-change notice linking to My groups.
- Recipient-only paginated list/count/read/unread APIs. Read requires an explicit Boolean. Controlled relative navigation targets, no tokens or user-supplied descriptions in notification payloads.
- Retention: cap to latest 200 on activity/read, delete older than 90 days; bounded hourly metadata cleanup also covers inactive recipients. No financial rows are removed. Free hosting uptime governs scheduled work; read-side cleanup always enforces visible retention.
- Accessible responsive bell/panel with real unread count after first open, read/unread controls, timestamps, resource links, keyboard Escape/focus return, outside-click dismissal, loading/empty/error/retry and pagination. Refetch-on-open; once used, visibility-aware 60-second/focus unread refresh. Unknown initial count is not presented as zero. No paid realtime infrastructure.
- PASS: 36 backend unit/H2/controller scenarios (35 initial plus the added inactive-retention case), six real MySQL notification scenarios, 40 frontend tests, lint and build. Real MySQL V9→V10 checksum/data-preservation and schema checks passed. A schema test-only precision expectation accidentally changed during count updates was corrected to the established DECIMAL(19,2) and its rerun passed. One real isolated Edge flow verified membership and expense notifications, authorized deep navigation and unchanged balances. Disposable containers/volumes were removed; existing MySQL listener preserved; log credential check passed.
- Existing invitation test fixtures now delete only their own disposable records after each scenario, preventing cross-context fixture leakage; no production cleanup/reset. Original checkout edits remain untouched.

## Autonomous completion — phase 2 (local verification)

- V9 adds nullable mailbox-verification/revocation metadata plus hashed single-use private group links and email-verification tokens. Existing V1–V8 migration text/checksums and financial records remain unchanged. Original checkout's unrelated test edit remains untouched.
- Private links: creator-only generation/list/revocation, 256-bit random bearer secret, hash-only storage, seven-day expiry, one recipient acceptance, 20 creations/group/day. Link possession deliberately authorizes any signed-in recipient; UI warns owners to share privately. No membership changes on GET or mere link opening.
- Existing email invitations reused, with pending/accepted/declined/expired/revoked management, duplicate protection, owner-only revocation and active-group checks. Email-bound inbox and acceptance now require mailbox proof. Verification emails use separate 30-minute single-use hashed tokens, authenticated account binding and persistent 60-second/three-per-hour issue budgets. No owner-copyable verification secret exists.
- Legacy direct enrollment now returns conflict after existing creator/member/active checks, preventing consent bypass. No existing memberships are removed. Updated related fixtures/tests and the shared isolated browser helper to use recipient-authorized link acceptance.
- Invitation and mailbox-verification messages reuse the disabled-by-default HTTPS adapter; no real email sent. Signup/login preserve link continuation. Invitation/verification fragments are captured in memory and removed before React starts; never stored in browser storage or logged.
- PASS: 30 backend unit/H2/controller tests; 6 MySQL invitation/schema tests plus 3 V8→V9/legacy invitation regressions; 35 + 26 frontend checks across six relevant files. All passed, with overlapping reruns excluded from unique totals. Lint and frontend build pass. One real isolated Edge invitation→registration→acceptance→expense/balance flow passed with disposable MySQL/users and email forced off. Script guard updated to exact V1–V9 while preserving baseline checksum checks; system execution policy unchanged (test-process override only).
- V8-only password-reset migration test is explicitly pinned to V8 so future additive migrations do not invalidate its preservation assertions. V9 upgrade has a separate real MySQL checksum/data-preservation regression.
- Brevo handoff update: user signed in; account shows 300 remaining daily credits and a phone-verification prerequisite before sending. Transactional onboarding waits for a successful log; sender approval and an authorized test inbox remain outstanding. Requested private phone/sender verification and an authorized inbox, never keys/codes in chat. Delivery flags remain false.

## Autonomous completion — phase 1 (6 November 2026)

- Verified clean worktree on codex/password-recovery-backend at M1B 6fe4ae3afa5e0b832bcda1eb51977f26907b0638; original root edits remain untouched.
- Opened official Brevo homepage and signup in agent-accessible browser. Signup visibly states "Free to use – no credit card required" and requires email/password plus acceptance of terms; no signed-in account available.
- BLOCKED: user must complete private login/signup/verification and explicitly authorize a test inbox. Asked asynchronously; no credentials requested in chat. Sender ownership, transactional activation, domain-free eligibility and actual message/link tracking remain unverified. No API call or email sent, settings remain disabled.
- No code changes in this phase; prior M1B tests remain the last verified baseline. Browser left at signup for user handoff. Continue independent phases as expressly authorized; no deployment/migration/push/billing actions.

## Current status: M1B prepared; email disabled (6 November 2026)

- Continued in the same isolated worktree and branch from verified M1A commit `19a0c04462e2911a92c67d5c40757ca94cfe588c`. Original checkout's MySQL test edit and M0 progress document remain untouched. No migrations, financial features, invitations or notifications were changed.
- Added Forgot Password, Check Your Email, Reset Password and Reset Success routes using existing React components/styles. Login links to recovery; reset also works while signed in. Includes accessible field errors/focus, pending/duplicate-submit protection, password visibility, UTF-8 length/confirmation validation, expired/used link handling, safe API errors and sign-in navigation.
- Browser bootstrap captures a fragment token in memory and removes the URL fragment/query before React/authentication requests. Query-string tokens are rejected; no reset secrets enter browser storage, router state, displayed UI or application logs. Added no-referrer policy. Reset success clears the old local session; M1A server-side JWT revocation is unchanged.
- Added provider-independent transactional transport and recovery message composition, with a Brevo HTTPS implementation. Fixed endpoint, finite connection/read timeouts, no redirects/retries, plain-text content and sanitized response/error handling. No new mail dependency or paid SDK. Any transport/non-201 failure disables sending until restart/review.
- Delivery remains unavailable unless both server-side enablement and verified-delivery flags are explicitly true and complete private configuration is present. Disabled recovery now returns the same generic 503/no-store response for all valid email addresses; UI shows unavailable rather than implying mail was sent. Enabled 202 means request received, never confirmed delivery.
- Public configuration availability is separate from private circuit-breaker health: a recipient's delivery failure must not change public responses into an account-existence signal. Configured requests retain conditional 202 responses even while the private breaker stops sending. Tests verify this separation.
- Brevo public docs confirm Free = 300/day, no card/time limit. Sender verification and separate transactional activation are required. Temporary brevosend.com sender rewriting does not establish permanent domain-free eligibility for this account; tracking behavior also needs a received-message check. No account setup, credential collection, approval request, real email or provider activation was performed.
- Configuration names (disabled defaults, no secrets) are in backend/.env.example. Full eligibility evidence, configuration contract and exact owner steps: [password recovery email handoff](docs/password-recovery-email.md).

### M1B focused verification: all passed

- Backend unit/H2/controller: **41 passed**, zero failures/errors/skips. `./mvnw.cmd -B -o -Dtest=PasswordRecoveryIntegrationTest,UserTokenVersionValidatorTest,RecoveryEmailTest,RecoveryEmailConfigTest,AuthControllerTest,SecurityConfigTest test`.
- Disposable real MySQL recovery/JWT: **7 passed**, zero failures/errors/skips. `./mvnw.cmd -B -o -Pmysql-it -Dit.test=PasswordRecoveryMySqlIT test-compile failsafe:integration-test failsafe:verify`.
- Frontend: **35 passed** across recovery and existing auth suites. `npm run test -- --pool=threads --maxWorkers=1 src/test/passwordRecovery.test.jsx src/test/authentication.test.jsx`.
- Local Edge browser: **3 passed**, mobile 375px and desktop 1280px recovery flow, keyboard/focus/visibility, unavailable email and expired links. `npx --no-install playwright test --config=playwright.recovery.config.js`. Uses mocked localhost API responses, no real users/provider/database; this is not a deliverability test. Separate .browser.js naming keeps it out of the existing real-backend *.spec.js runner.
- Inspected mobile reset and desktop request screenshots; corrected error text to the existing accessible red theme token and increased button/link touch targets. Artifacts remain ignored under frontend/test-results/recovery. Frontend lint and production build passed. Locked dependencies installed from local npm cache; no new dependency or lockfile changes.
- Old/new JWT, reset replay/concurrency/rollback and signed-in reset tests remain passing. No schema files changed; V8 remains the latest migration. Reviewed focused diff and staged scope before local commit; local identity is available from `git log -1` on this branch.
- Final public/private availability regression was rerun on both H2 and real MySQL: seven scenarios passed on each, with zero failures/errors/skips. Command: `./mvnw.cmd -B -o -Pmysql-it -Dtest=PasswordRecoveryIntegrationTest -Dit.test=PasswordRecoveryMySqlIT test failsafe:integration-test failsafe:verify`.

### Account boundary and next action

Stop here. Live delivery is BLOCKED on owner-controlled sender eligibility/activation and private configuration; UI/adapter implementation and local verification are complete. Owner should manually confirm a Brevo Free sender can send these messages without a purchased domain, card or billing; stop if any is required. Never paste an API key in chat or Git. Keep delivery off until account approval and a separate private actual-message/link-tracking verification are complete. See the handoff document for precise steps. No production migration/deploy, push, billing or credential changes occurred. Do not begin invitations/notifications automatically.

## Previous milestone: M1A backend (6 November 2026)

This section supersedes baseline assumptions and proposed version numbers in the historical M0 notes below. M1B's current status is above. Do not deploy, migrate production, push or activate email without separate approval.

### Baseline and preservation

- Original checkout remains on `codex/java-warning-remediation` at `cea12847f3935606e4c229cfbc29e19ac04b8a12`, with its pre-existing MySQL test edit and untracked M0 progress document preserved.
- M1A worktree: `C:\Twinklesangani portfolio\SplitSmart\audit-output\m1a`, branch `codex/password-recovery-backend`, based exactly on supplied deployed SHA `418d757855dd10965c2872176022f93ac5e35a8a`.
- Verified that deployed SHA includes V5 invitations, V6 expense metadata, V7 group archiving, real dashboard implementation, and health HEAD/security fixes. The original M0 inspected an older branch; its missing-invitation/dashboard findings do not describe this production baseline. Do not reimplement those features from the old plan.
- No reset, blind cherry-pick, production access, credentials change, provider registration/activation, push or deployment occurred.

### Implemented

- Additive `V8__password_recovery.sql`: users.auth_token_version defaults to zero; nullable users.password_reset_requested_at; password_reset_tokens with hash PK, user FK, created/expiry/consumption UTC DATETIME values and user/time + expiry indexes. V1-V7 are unchanged.
- `POST /api/auth/forgot-password`: validated/normalized email, generic HTTP 202/no-store message. All lookup and delivery occur on a dedicated one-thread, 64-job bounded queue. Saturation/provider failures never change the public account response. Rejected jobs are not run on the request thread.
- Server-side delivery interface has no provider implementation or external calls. Default adapter reports unavailable and issues no token. Tokens are capturable only by test fake delivery, never production API responses. No recovery UI was added.
- When delivery is available: SecureRandom 32-byte URL-safe tokens; database stores only SHA-256 digests; 30-minute expiry. Persistent account cooldown of 60 seconds and three issues/hour under a user row lock, sent only to stored account email. MySQL locking reads avoid stale repeatable-read snapshots.
- `POST /api/auth/reset-password`: token + password + confirmPassword, existing 8-character/72 UTF-8 byte password rules, existing BCrypt encoder. User lock, conditional single-use consumption, password update, token-version increment and sibling-token invalidation commit or roll back together.
- JWTs now include numeric `ver`. The decoder checks the database version on every bearer-authenticated API request, without a cache; errors fail closed. Legacy JWTs without `ver` mean version zero and continue to work only until that user's first reset. Existing signing credentials, issuer and one-hour lifetime are unchanged.
- Existing trusted-proxy/body-size protection extended to both recovery endpoints, with independent per-source endpoint limits of ten attempts per configured limiter window (default 60 seconds). Source counters remain in-memory/restart-local; account send budgets persist in MySQL.
- DTO string representations are redacted. Processing logs contain only a fixed warning, without exceptions, email, token, password or message body. No raw-token persistence or debug endpoint exists.

### Validation

- Java 21 from existing local installation; focused unit/H2/controller checks: 60 passed, zero failures/errors/skips.
- Command (backend): `./mvnw.cmd -B -o -Dtest=PasswordRecoveryIntegrationTest,UserTokenVersionValidatorTest,AuthServiceTest,AuthControllerTest,SecurityConfigTest,SplitSmartApplicationTests,RequestProtectionFilterTest,GroupControllerTest test`.
- Real MySQL verification: 10 passed, zero failures/errors/skips (seven inherited recovery scenarios, V7-to-V8 preservation test, existing expense-metadata migration test, existing isolated schema/FK test).
- MySQL command: `./mvnw.cmd -B -o -Pmysql-it -Dit.test=PasswordRecoveryMySqlIT,PasswordRecoveryMigrationIT,ExpenseMetadataMigrationIT,SplitSmartMySqlIT#flywayAndHibernateUseTheIsolatedMySqlSchema test-compile failsafe:integration-test failsafe:verify`.
- New recovery scenarios cover valid/invalid/expired/reused tokens, concurrent single use, sibling invalidation, transactional rollback, old and legacy JWT rejection, replacement login, generic responses under unavailable/failing delivery, account/source limits and password validation. Additional unit checks cover malformed versions, missing users and database outage denial.
- Disposable MySQL migration test upgrades V7 to V8, validates old checksums, and checks existing hashes, balances/splits, expense metadata, membership and archived groups remain intact. Existing migration/schema assertions were updated only for the additional migration/table/FK; the original checkout's unrelated MySQL test edit was not copied or staged.
- First H2 attempt found unsupported multi-column ALTER syntax; V8 was split into two additive statements before any non-test use. Existing JWT claim expectation was updated for ver. Subsequent focused H2 run passed. No applied production migration was changed.
- Frontend unchanged; no frontend suite or broad backend suite run. Local Docker Desktop was started to use already-cached disposable mysql:8.4.13; no production database or paid service was used.
- Final diff review: only recovery implementation, directly affected auth/schema test expectations and this progress document. V1-V7 checksum inputs unchanged. All checks passed before staging the focused local commit; commit identity is available from `git log -1` on this branch.

### Rollout limits, blockers and M1B handoff

- No live recovery email can be sent with the default disabled adapter. M1A verifies backend behavior using fake delivery only; real delivery readiness is not claimed.
- Bounded in-memory request queue is best effort and loses pending jobs on restart; a queued HTTP 202 is not a delivery receipt. Provider failure consumes the account issuance budget until the cooldown/window expires. Pending token cleanup/retention and operational delivery reporting need review before live enablement.
- A later delivery adapter must use a configured trusted HTTPS frontend origin, no Host-derived URLs, finite network timeouts, no link tracking/token logging, and the stored expiry. Reset success email notification and UI belong to the separately reviewed follow-up.
- Rollout requires V8 first, then this backend, then verification before enabling recovery delivery. Do not enable resets while old backend instances can still authenticate JWTs without version checks. Rollback to old authentication code after resets would revive old signed JWTs until expiry; do not roll back across that boundary without an approved revocation strategy. No production credentials are changed by this implementation.
- Database lookup on each authenticated request is deliberate for immediate revocation; database unavailability produces unauthorized rather than granting stale access. Already-authorized in-flight requests are not cancelled.
- M1B next, only after review: implement recovery forms and validate an explicitly approved zero-cost sender/HTTPS adapter. Re-audit existing V7 invitation/dashboard functionality before planning later milestones. New service registration/activation and production release remain separately gated.

## Historical M0 notes (older branch; retained for handoff)

Updated: 6 November 2026 (Asia/Calcutta).

## Scope and constraints

- M0 source/configuration audit completed; M1-M5 await milestone review.
- Preserve Java/Spring services, React/plain CSS, MySQL, existing features and data.
- Zero additional spending; no payment method, purchases, registrations or provider activation.
- No pushes, merges, publication, deployment, credential changes or production database writes without separate authorization.
- This audit adds documentation only. No implementation changes, new tests or Git commits were made.

## Audit evidence and limits

- Checkout: `codex/java-warning-remediation`, HEAD `cea1284` at inspection.
- Pre-existing modified file: `backend/src/test/java/com/splitsmart/SplitSmartMySqlIT.java`; leave it untouched and exclude it from future milestone commits.
- README identifies production release `bc59921336f148784317550a5f29202edbeda825`; this is recorded documentation, not a newly verified deployment identity.
- Inspected controllers, auth/security, group/financial services, entities, all four migrations, frontend routes/pages/shared components/styles, test inventory and CI/test configuration.
- No applicable AGENTS.md found at workspace or ancestor locations checked.
- Public production frontend could not be accessed by the web reader. No authenticated production flows, screenshots, live database or provider account settings were inspected.
- Existing test coverage was inspected, not executed. Historical test results in README are not fresh audit results. No production disposable users were created.

## Existing implementation to preserve

- Registration, normalized emails, login, `/api/auth/me`, protected routes and tab-scoped sessionStorage bearer tokens; one-hour HS256 JWTs with issuer/signature/expiry validation. Delegating password encoder uses BCrypt by default; registration enforces 8-character minimum and 72 UTF-8 byte maximum.
- Generic login credential errors; bounded in-memory source/target auth rate limiting, request-size protection, explicit trusted proxy handling, exact-origin CORS and sanitized API errors.
- Create/list groups, creator automatically enrolled; only creator can directly add an existing account by email. Backend membership checks and unique membership constraints.
- Expense creation/detail/edit/delete, equal/exact INR splits, deterministic paise allocation, transactional group locking, creation idempotency and paginated history.
- Real group balances account for expenses, shares and repayments; settlement suggestions already use those balances. Sender-only repayment records validate membership and outstanding amounts, lock the group and prevent duplicate request IDs. These are bookkeeping records, not money transfers.
- Shared React UI components, group navigation, responsive CSS, mobile drawer, form validation, loading/error/retry states and empty states.
- Unit/controller/security tests, Vitest tests, isolated Playwright flows and disposable MySQL Testcontainers infrastructure exist. Playwright guards require localhost test services.
- Flyway V1 users/groups/members, V2 expenses/splits, V3 settlements, V4 expense creation operations. Hibernate validates rather than creates schema. Production config separates runtime and Flyway database identities and verifies TLS.

## Missing or defective behavior

- No forgot-password link/routes/API, reset token persistence/delivery, reset rate limits or password-change revocation. User entity has no token version or email-verification field.
- JWT decoder performs no per-user revocation check. Sign-out clears the browser token only; a copied valid JWT remains usable until expiry.
- Login skips BCrypt comparison for nonexistent users: generic text alone does not remove timing enumeration. Registration explicitly reports duplicate emails. Existing add-member success/not-found behavior can disclose account existence to group creators.
- No email dependency/adapter/configuration found in application source or pom. No invitation entity/API/acceptance/expiry/revocation/pending status. Current add-member immediately enrolls existing users without their acceptance.
- No persisted notifications, bell, unread state, event generation or retention.
- Dashboard fetches only groups. Expense/owed/receivable cards display dashes and outdated integration copy; recent activity is an unconditional placeholder. No cross-group summary/activity endpoint exists.
- Group overview emphasizes IDs and explanatory implementation text instead of balances/actions; no invite action. Add Expense requires navigating to Expenses. No guided create/invite/expense onboarding.
- Mobile drawer supports initial focus and Escape, but lacks focus trapping and background inertness. No skip link found in the shell. Responsive tests exist but do not establish full accessibility or visual quality.
- API client automatically attaches tokens and handles session-expired events only for `/api/groups...`; new authenticated notification/dashboard paths must extend this safely.
- Fixed 10-second frontend request timeout can conflict with Render Free cold starts; improve recovery without automatically retrying financial writes.

## Zero-cost email evaluation (official documentation checked 6 November 2026)

- Render Free blocks outbound SMTP ports 25/465/587. Use a backend HTTPS transactional API, not Gmail/provider SMTP.
- Brevo Free: 300 emails/day, unused allowance does not roll over, no credit card or time limit. Sender verification and transactional activation required; account acceptance is not established by public plan terms. Brevo documents temporary sender rewriting to brevosend.com for unauthenticated/free-mail senders; do not assume this guarantees durable domain-free production eligibility. Free branding/queue limits and delivery delays must be accepted; expired reset links must never become usable because mail was delayed.
- Resend Free: 3,000 transactional emails/month and 100/day (sent/received share quota), one domain. Official product page advertises no card to start. resend.dev can send only to the account owner's email; real recipients require an owned, DNS-verified domain. Account-specific anti-abuse/card requirements remain unverified. Not an unconditional zero-cost answer when no domain ownership is established.
- Mailgun Free alternative: official support lists no credit card, 100 messages/day and one custom sending domain, with one-day logs. Real sending/domain/account eligibility still needs verification; its sandbox is not a general invitation solution.
- Recommendation: conditional Brevo HTTPS adapter, enabled only after owner-approved no-card sender/activation verification. If an existing owned domain is available, evaluate authenticated Brevo/Resend without buying one. No provider is confirmed usable for this account today.
- Always implement secure copy-link invitations independently of email. Do not offer copyable reset tokens or admin password-reset shortcuts as a recovery fallback. End-to-end recovery needs working verified email delivery; keep production recovery unavailable until verified.

Sources:

- https://render.com/docs/free
- https://help.brevo.com/hc/en-us/articles/208589409-About-Brevo-s-pricing-plans
- https://help.brevo.com/hc/en-us/articles/16045394674066-Troubleshooting-Domain-authentication
- https://help.brevo.com/hc/en-us/articles/115000188150-Troubleshooting-Issues-with-Brevo-SMTP
- https://developers.brevo.com/docs/send-a-transactional-email
- https://resend.com/products/transactional-emails
- https://resend.com/docs/knowledge-base/account-quotas-and-limits
- https://resend.com/docs/knowledge-base/403-error-resend-dev-domain
- https://help.mailgun.com/hc/en-us/articles/203068914-What-does-the-Free-plan-offer
- https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html

## Milestone implementation plan (pending)

### M1: secure recovery

1. Add the next unused additive migration (expected V5): reset token table with unique SHA-256 digest, user FK, expiry and consumption state; user token_version defaulting to zero. Never edit V1-V4.
2. Reuse existing encoder and limiter, add cryptographically random 256-bit reset tokens, 30-minute proposed lifetime, account/source send budgets and generic request responses with equivalent processing paths. Add durable account send cooldown to bound restart abuse; retain bounded in-memory source budgets with documented restart limitation. No account lockout from reset requests.
3. Consume token, change hash, increment token version and invalidate outstanding resets atomically under transaction/locking. Check account token version on every authenticated JWT request; reject legacy JWTs lacking a version after rollout. Do not rotate production JWT credentials.
4. Add minimal HTTPS email adapter shared with M2; secrets server-side only, fixed trusted frontend origin, no token/body logging, tracking disabled for security links, no-store/referrer protection. No provider activation or live configuration as part of local work.
5. Add request/reset pages, password confirmation, safe expired/used token states and explicit sign-in after success. Reset route must work for a currently signed-in user too; existing PublicOnlyRoute would otherwise redirect it away.
6. Focused tests: known/unknown email equivalence, rate limits/aliases, hashed storage, expiry, replay/concurrent consumption, password rules, rollback, old JWT rejection across protected endpoints, UI validation and generic errors; isolated disposable-user recovery flow with captured fake mail, never logged tokens. Live deliverability remains a separate verification dependency.

### M2: consent-based invitations

1. Expected V6: invitation table with group/inviter/email, unique token digest, expiry, accepted/revoked state and appropriate lookup indexes. Use transactions and uniqueness/locking to prevent duplicate pending invitations and memberships.
2. Creator-only create/revoke/resend; proposed seven-day expiry, bounded requests. Copy-link works without email. Reuse M1 mail adapter only when configured and verified.
3. Acceptance requires authenticated recipient and proof of invited email ownership. Existing registration does not verify email: introduce verification evidence for invitees (including existing unverified accounts), rather than relying solely on matching a self-declared email. Decide explicit policy for forwarded links; default email-bound invitations must reject another account. No membership change on GET/link preview.
4. Preserve login/signup invitation destination; render pending/accepted/expired/revoked states. Keep existing direct-add behavior until explicit review of consent transition; no silent deletion of existing memberships.
5. Focused tests: creator/outsider permissions, wrong recipient/unverified account, forwarding, expiry, revocation, duplicate/racing acceptance and signup/login continuation.

### M3: notification center

1. Expected V7: recipient-owned notification records, event type, group/resource identifiers, timestamp and read_at; index recipient/time/unread queries. Proposed 90-day retention with bounded cleanup, no financial record deletion.
2. Create notifications transactionally for invites, new expenses, settlements and membership changes; suppress self-noise and duplicate retried events. Persist no invitation/reset secrets in notification payloads.
3. Authenticated own-list/unread-count/read/unread APIs, bounded pagination; recheck resource authorization before navigating or returning details. Extend shared API token handling to new paths.
4. Accessible responsive bell/panel with unread count, timestamps, empty/error/loading states, keyboard/focus behavior and refetch-on-open plus conservative visibility-aware polling.
5. Focused tests: cross-user IDs/count/read denial, resource authorization, rollback/deduplication, retention, keyboard behavior and event-to-panel disposable-user flow.

### M4: real-data dashboard and UX

1. Add bounded authenticated summary/activity queries using existing financial definitions, without duplicating settlement algorithms. Owed = sum of negative per-group user nets as positive amounts; receivable = positive nets; net = receivable minus owed. Do not erase debts by netting unrelated groups.
2. Replace dashboard placeholders with real totals, per-group balances, recent activity and links to existing settlement suggestions. Label group spend versus personal expense share clearly.
3. Reuse existing tokens/components; improve create/invite/add-expense actions, onboarding, hierarchy and human-facing copy. Fix drawer focus/background behavior, add skip navigation and review form/status semantics, contrast, mobile overflow and cold-start recovery.
4. Focused tests: aggregate accuracy with partially settled groups, membership isolation, no-data vs failed-data states, responsive/keyboard behavior and existing expense/repayment flows.

### M5 and the gate for every implementation milestone

- Review minimal diff; run only affected backend/frontend suites, lint/build where relevant and targeted isolated Playwright flows with disposable data.
- Use disposable MySQL tests for new migrations, locks, uniqueness and race-sensitive writes; H2 alone cannot establish MySQL correctness. Inspect the existing integration-test modification before touching adjacent tests.
- Check clean upgrade from existing V1-V4 and backend/frontend compatibility; never clean/reset production or edit applied checksums. Production DML-only runtime credentials cannot apply new DDL; migration identity/permissions require a separately approved release step.
- Record actual commands/results, remaining limitations and handoff here. Commit only verified milestone-owned files locally; exclude pre-existing edits. Stop for review at milestone boundaries. No automatic push/deploy.

## Security and migration risks / decisions

- Account ownership is unverified today; invitation acceptance must address pre-registration/forwarding risks without bulk changing existing user emails or memberships.
- Reset atomicity and JWT invalidation are required together. Intentional one-time re-login for legacy tokens is safer than keeping unversioned JWTs valid after reset; review this user impact before implementation.
- Reset timing, IP proxy configuration, quota exhaustion, retries, provider logs/link tracking and mail delays can defeat otherwise secure tokens. Do not expose tokens in errors, analytics or progress files.
- Additive migrations may briefly lock MySQL tables. Check indexes/default/backfill compatibility on disposable MySQL first. Production migration version state is unknown; V5/V6/V7 are provisional until checked in an approved release process.
- Existing balances/history remain authoritative; notifications and dashboard summaries must not rewrite financial records. Large group lists and notification fan-out must be bounded on free resources.
- README documents existing production/development drift, free-service sleep/suspension and a prepared health HEAD monitoring fix. Do not bundle unrelated local work into a future release or claim monitoring/provider recovery from this audit.

## First step after M0 approval / handoff

Start M1 with the additive reset/token-version migration and atomic backend reset tests, preserving the existing encoder, service architecture and user data. Implement local fake-mail verification before any provider configuration. Resolve zero-cost sender eligibility before claiming production recovery is ready. Stop here for M0 review; do not begin M1 automatically.


PostgreSQL compatibility commit — 7 November 2026
Completed separate PostgreSQL V1-V10 migrations plus PostgreSQL-only V11 UUID comparison compatibility, opt-in runtime/verified-TLS profiles, portable JDBC mappings and dashboard/notification SQL. Original MySQL V1-V10 checksums unchanged. Local data copy/count/hash/financial/identity checks PASS; 26 PostgreSQL plus 14 focused regression/configuration tests PASS. See docs/postgresql-migration-readiness.md. No production access, upload, cloud provisioning, migration or deployment during compatibility work. Earlier uncommitted preservation/restore notes and unrelated edits deliberately excluded from this focused commit.
