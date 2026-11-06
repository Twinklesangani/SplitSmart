# SplitSmart project progress

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
