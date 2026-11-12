# M8 final production release authorization — 13 November 2026

**Recommendation: NO-GO until the bounded scope below is explicitly approved and all execution-time gates pass. Read-only preflight/local preparation complete; no production changes made.** This section supersedes historical privacy/release requests below. Preserve all eight accounts and every associated record. Email remains disabled; PostgreSQL Option C and the offline demo remain untouched.

## 1. Exact affected resources and current state

- Aiven project `twinklesangani-9b5e`, existing MySQL service `splitsmart-mysql`, database `splitsmart`: RUNNING on Free-1-1gb, MySQL 8.4.11. Fresh TLS SQL verification confirms V1–V7 all successful with original checksums and nine InnoDB base tables. No data rows retrieved during this preflight.
- Render `splitsmart-backend`, `srv-dbkvpt6gekts73fa6gug`: Singapore/Free, root `backend`, readiness `/actuator/health/readiness`, autoDeploy=no. Live deploy `dep-dbm583k9v7es738tqjng`, source `418d757855dd10965c2872176022f93ac5e35a8a`. Current branch `codex/product-completion`.
- Cloudflare Pages project `splitsmart`, public `https://splitsmart-92b.pages.dev`: active deploy `0294158a-95e6-4b55-9757-237afb3bfb28`, source `bc59921336f148784317550a5f29202edbeda825`. Production branch `codex/product-completion`, automatic deployments paused. The newer 418d757 entry is SKIPPED, not live. Build root `frontend`, command `npm run build`, output `dist`, Node24, existing API origin `https://splitsmart-backend-ydj2.onrender.com`.
- GitHub `Twinklesangani/SplitSmart`: propose publishing ONLY candidate commit to a NEW release ref `codex/release-mysql-v10-a1813f8`; no merge, force push, visibility change or main-branch modification. Keep unrelated local edits out. Existing branch CI does not auto-deploy this new release ref; both hosts must remain manual.
- Excluded: Aiven PostgreSQL, Brevo account/key changes, `splitsmart-offline-demo`, existing recovery databases/backups and all unrelated services.

## 2. Exact source and local artifact references

Backend AND frontend source: `a1813f80d9c22e06ae89e2dd7f8b2ba6a0f02c16`.

- Backend JAR: SHA-256 `e5e8dff1a23b7f409b89709180605796bd5ca99cb2bb5b93ef24af298d09e1b3`.
- Frontend `index.html`: `5e33d0479622f3e0c46d058f5b15d33c51a2b4223ce54b5d8ff5a3bb46495255`.
- `assets/index-l1jTLbfL.js`: `daef039e325063cf582d9917b4924d5140871188264c47c83d932f8d737fff48`.
- `assets/index-BxL7R7ob.css`: `e7ff738c31e1548c3edda363ba0d6f0cccbcd116d2a348854e162a9aa6992b63`.
- Existing verified rootless test image: `sha256:3da30b2eb46b0f5e7eb135561c70b9fa17ce0b672a53116b5b9558d9f7a9e3d2` (synthetic PostgreSQL container evidence, not an already deployed Render image).

All four artifact hashes rechecked; build/application inputs unchanged since the tested candidate, including pom/Dockerfile/entrypoint/frontend lockfile. Packaged MySQL migrations match source; V1–V7 preserved. Java21/container, MySQL V7→V10 and real-backup rehearsal evidence reused, not rerun. No private key/truststore/test-profile files found in application archive; targeted frontend private-key/Brevo-pattern/source-map checks passed (not exhaustive secret discovery).

**Important artifact limitation:** current Render Dockerfile rebuilds Maven source and uses mutable Java21 base tags; no reproducible-build timestamp is set. Thus these are verified LOCAL binary hashes, not a claim that a future Render rebuild is byte-identical. Approved deployment must use the exact commit, record actual build/image provenance and verify Java21/startup/TLS/health and migrated-schema behavior while fenced. Unexpected build/runtime/dependency drift stops release; a changed binary must not be called the original tested binary. No registry publication or new image-hosting scope is implied.

## 3. Write-freeze and privilege prerequisites

Read-only runtime grant check: `splitsmart_app` has SELECT/INSERT/UPDATE/DELETE on `splitsmart.*`, no DDL. Existing `splitsmart_migrate` authenticates privately: SELECT/INSERT/CREATE/REFERENCES/INDEX on this schema and UPDATE on Flyway history, but **no ALTER**. Existing `avnadmin` credential is accessible privately and has GRANT OPTION and transaction visibility. No credential reset or new user is needed. No Flyway credential is configured in Render; retain that separation and keep `SPRING_FLYWAY_ENABLED=false` there. Migration runs from the private local TLS client only.

Explicitly approve temporary `ALTER` on `splitsmart.*` for the EXISTING migration identity, revoked after successful migration/validation. Do not grant ALL/DROP/global privileges or install administrator credentials in Render. V8 and V9 require ALTER; without this grant, STOP before DDL.

Freeze mechanism proposed for explicit approval:

1. Confirm all writers/jobs/admin scripts are stopped. The owner must not make manual writes during the window. Current metadata shows zero transactions and zero admin-visible events, but this is not proof of future quiet or every external writer.
2. Suspend Render through its supported service-suspend operation. Confirm suspension and disappearance of runtime sessions; drain active transactions to zero. Never kill or roll back unknown customer transactions merely to meet a deadline.
3. As a second fence, temporarily REVOKE INSERT/UPDATE/DELETE on `splitsmart.*` from the exact existing `splitsmart_app` SQL account. Record its current SHOW GRANTS privately for precise restoration; verify reconnect/session state and effective grants. No credential changes. SELECT remains. Do not revoke unrelated identities. If any runtime write grant survives through another grant/role, STOP.
4. Keep that fence across backup, migration and any Render resume/rebuild. Old V7 code does NOT implement APP_MAINTENANCE_ENABLED; the flag alone is NOT a freeze. Render resume may restart the old revision; do not assume it can safely run V10 and never restore DML merely because it starts. Verify the exact NEW deploy is live with maintenance ON before restoring the runtime grants. If the host cannot stage the new artifact safely under this fence, keep suspended and STOP.

[Render suspend](https://api-docs.render.com/reference/suspend-service-1), [resume](https://api-docs.render.com/reference/resume-service-1), and [commit-pinned deploy](https://api-docs.render.com/reference/create-deploy) are documented. No suspend/resume/grant change was attempted in this read-only milestone. DML fencing is a proposed production operation, not a newly executed synthetic test.

## 4. Final backup checkpoint and migration order

The November12 snapshot and its private Drive download hash still match `a70bf90feaaf251e51b07b047e0e45e8abee84fb62fdeb9d2d8b33ca11890f1a`; independent-copy/restore evidence PASS. It does not include subsequent writes.

After all writers are frozen and transactions drained, authorize ONE NEW timestamped V7 logical snapshot streamed from verified-TLS mysqldump `--single-transaction --quick --no-tablespaces --set-gtid-purged=OFF` into AES-256 GPG. No locks/plaintext SQL; owner/SYSTEM-only backup directory. Reinventory objects/engines; nine-table-only export must explicitly retain unverified/excluded trigger/routine/event coverage. Stop if new objects/engines require a different scope. Keep all previous ciphertexts unchanged. Pin the new hash, restore ONCE into a new unique loopback-only local MySQL database, verify V1–V7/counts/constraints/financial aggregates privately. With writers frozen, before/snapshot/after reconciliation must agree. No migration until restore PASS. Native GPG Pinentry may require private owner interaction.

Authorize ONE upload/download verification of that final ciphertext to the already verified owner-only Drive backup folder (exact private ID in DriveBackupCopy receipt); no other file/key transfers. If this additional transfer is not approved, stop before that transfer and do not infer the previous single-file approval covers it.

Then privately connect as existing migration role with TLS VERIFY_IDENTITY and the exact `splitsmart` identity; inspect Flyway state. Validate known history with pending migrations expected (do not repair history or treat expected pending as corruption); execute only V8 → V9 → V10; validate afterward. V8 adds zero-default authentication versions/reset table; V9 adds verification/invite state; V10 creates notification persistence. No table drop/truncate/data deletion or account/hash replacement. Existing financial rows, IDs, membership and constraints remain intact. Require all migration successes plus before/after preserved-record hashes/counts/financial invariants and new-schema/privilege checks. MySQL DDL can partially commit; see recovery below.

## 5. Exact host actions/configuration and sequence

Before outage, after separate authorization: verify release ref does not exist or already resolves to the exact same SHA; push `a1813f80d9c22e06ae89e2dd7f8b2ba6a0f02c16` to `refs/heads/codex/release-mysql-v10-a1813f8` only. Keep autodeploy off. Bind Render and Pages production source to that reviewed ref with manual deployment. If a branch update would trigger deployment despite disabled settings, STOP; no unapproved deploy ordering.

Existing runtime URL/credentials/JWT signing secret/truststore remain unchanged. Explicit production flags: `SPRING_PROFILES_ACTIVE=prod`, `SPRING_FLYWAY_ENABLED=false`, `APP_MAINTENANCE_ENABLED=true`, `RECOVERY_EMAIL_ENABLED=false`, `RECOVERY_EMAIL_DELIVERY_VERIFIED=false`. Preserve exact CORS `https://splitsmart-92b.pages.dev`, MySQL VERIFY_IDENTITY and existing private PKCS12 material. No local development certificate or synthetic credential is uploaded. Do not add Brevo key to Render or frontend. API key exists only in private local DPAPI storage; prior two-email HTTPS proof stands, no new send/credential probe needed.

After final backup and successful migrations: deploy Render with exact `commitId` and guard ON, runtime DML still fenced; verify source/build metadata, Java21, TLS/readiness and maintenance 503/no-store/Retry-After. Restore EXACT runtime DML grants only after this identity is proven, while maintenance remains ON. Revoke migration ALTER after validation. Deploy only matching frontend to Pages `splitsmart`; build command/root/output/API unchanged, `VITE_EMAIL_ACTIONS_ENABLED=false`, Node24. Capture new deployment/source hashes; validate refresh paths. Do not touch offline demo.

When infrastructure/schema/privileges/reconciliation pass, explicitly reopen by setting maintenance OFF on the same pinned backend and verifying the resulting restart/deploy identity. Then execute the bounded approved synthetic live tests below. No built-in paid maintenance feature, paid resource, credential rotation or silent auto-upgrade. The new gate blocks all routes except exact liveness/readiness while ON; full user workflows cannot be tested through a secret bypass while gated.

## 6. Bounded live test authorization requested

Approve at most TWO new disposable synthetic accounts using reserved `example.test` addresses and private generated passwords; ONE synthetic group; TWO ₹1 test expenses (one equal, one custom split); ONE nominal synthetic settlement no greater than ₹2 (app ledger only, no money movement); at most TWO invite links to exercise acceptance/revocation. Notifications arise only from those approved actions; no external messages. Retain these identifiable synthetic records; no destructive cleanup authorized.

Check registration/login, invalid login, dashboard/recent activity, groups/members, expense split precision/idempotency, owed/receivable/net/suggestions/settlement history, Settings/navigation, cross-account group/expense/notification denial, notification read/unread, desktop/mobile keyboard layout. Reconcile the eight preexisting accounts and all original financial records separately from the bounded new synthetic records. Existing-owner real account login only if the owner chooses to authenticate privately; never change their passwords or reuse their account for mutations.

Recovery checks are UI/configuration-only: disabled truthful availability and enumeration-safe behavior; no live reset request that could send, no real-user password change. Prior old-password/JWT/token-reuse proof reused. Exactly zero emails authorized; production mail stays OFF.

Read-only checks today: readiness200, unauthenticated `/api/auth/me`401, approved-origin CORS present, unapproved-origin403. A scripted `/settings` GET received403; real browser loaded the SPA and displayed the old revision's not-found page, consistent with Settings not yet deployed. Matching candidate Settings has existing local browser evidence; verify again after the approved deploy. No current live Settings PASS claimed.

## 7. Impact, stop conditions and recovery

Plan 30–60 minutes of full API outage; frontend may show its current cold-start/maintenance feedback. **60 minutes is a decision checkpoint, not a guaranteed maximum outage** on Free infrastructure. If any gate fails, do not reopen unsafe writes to meet the clock; notify owner and keep the safest fenced state. Longer recovery needs explicit informed owner direction where outside this scope.

Stop on wrong source/service/SQL identity; missing privileges; unexpected writers/transactions; backup/decryption/restore failure; non-InnoDB/new objects; checksum/schema/hash/financial mismatch; failed/partially applied DDL; unclear deployment commit/build; missing TLS/JWT controls; inability to sustain the fence; unapproved billing/quota prompt; or unapproved external request/email. No automatic failed dump/restore/migration retries.

Before DDL, abort can restore exact runtime grants and resume the unchanged V7 backend only after proving schema is still V7 and no authentication-reset revocation fence has been introduced. After any V8–V10 DDL, retain suspension/DML fence/maintenance and inspect actual schema/history; prefer reviewed forward fixes. Do not blindly rerun, repair Flyway, drop partial tables or roll back to V7. A restore would need a separately approved new isolated destination and reconciled switch, never overwrite production. After any V8 resets/version increments, never restore lower auth versions or deploy unversioned JWT validation. After new writes, a stale snapshot is not a safe rollback. Application failure should use a compatible version-aware forward build with mail OFF, not the historical V7 image.

Known limits: MySQL Free services/data/backups are deleted December21,2026; this is only a bridge. PostgreSQL real-data import remains prohibited by Option C. Free Render cold starts/no SLA/quotas remain, and official docs discourage Free for production workloads. No guaranteed long-term zero-cost, privacy-compliant account availability has been established.

## ONE owner authorization request

Approve the exact interim scope above: publish only the pinned release ref; planned outage and writer freeze including temporary runtime DML revoke/regrant; ONE final encrypted V7 snapshot and ONE isolated restore; ONE final-ciphertext private Drive upload/download verification; temporary schema ALTER for the existing migration role and V8–V10 only; manual exact-source Render/Pages branch/config/deploy/reopen operations; and the bounded synthetic live tests. No production email, real-user reset, PostgreSQL import, deletion, merge/force-push, paid feature or offline-demo change.

Approval permits attempting this bounded sequence; it does not turn unverified execution gates into PASS. Any failed safety gate stops before its next consequential operation. Until that explicit approval: **NO-GO; read-only/local preparation only.**

---

# Historical approval material (superseded by M8 above)

# SplitSmart — owner release decision

**Production release operations in this document are not approved or executed. Production is NO-GO.**
The owner approved isolated Linux JDK21/rootless identity and package preparation, OCI building, synthetic verification and one no-cost provider inquiry; these do not authorize production operations.
Earlier reviewed adapter candidate: `4373ac57926caa178d11f77132b97cfcbfca6d75`. Linux verification tooling and evidence were added in `c80b07abe2efcff78aa9072df01c1bd82402d31e`, which is not a documentation-only descendant. A future release must review and approve its exact final candidate SHA.

The local data-integrity method is verified; real cloud import, actual Render deployment
and the final frozen snapshot are not. Email remains disabled.

## Privacy in plain English

A database can include an input value in its own error message, such as a failed
number conversion. Our application can hide its copy while the database still
stores another copy in provider logs. Parameters, validation and TERSE reduce
exposure but do not guarantee removal of every primary-error value. The observed
failure used fake text; no real-data leak was demonstrated. Password hashes,
user details and expense notes would be sensitive if an unforeseen error logged
them. Log access is permission-controlled, not necessarily public. Aiven documents
four-day service-log retention; that is not proof of deletion from every internal
copy. The likelihood is not quantified; successful data tests cannot measure it.

## Current owner policy — Option C, no new real-data cloud upload

The latest owner instruction explicitly selects C. Stronger provider-side protection remains desired; A is an unapproved policy change. Real-data upload remains prohibited even though local OCI verification now passes.

**A — Accept residual risk for a later controlled migration.**
Advantage: permits a path to continued service on the existing ₹0 target.
Limit: unforeseen database or application errors could expose sensitive values
to authorized log readers; exact ICU differences and Free availability remain.
Consequence: explicit acceptance plus separate import/release approval is needed.
This does NOT satisfy a strict guarantee of zero sensitive values in provider logs.

**B — Require stronger provider-side protection.**
Advantage: preserves the strict privacy requirement if the provider can establish
an effective supported control. Ask about primary-error redaction or narrowly
scoped loader controls, log-reader access and retention/deletion without disabling
security auditing. Limit: no suitable supported ₹0 guarantee is currently verified;
Free support response/availability is not promised. No upgrade or new service.
Consequence: import stays blocked until the control is documented and tested.

**C — Continue postponing real-data cloud migration.**
Advantage: no new cloud copy or import-time exposure now; existing MySQL/recovery
material remains. Limit: postponement does not remove the logging risk, and the
owner-verified MySQL retirement date is21 December2026. Existing backups are stale
relative to ongoing writes; more preservation requires separate approval.
Consequence: plan a decision before retirement or accept possible app downtime.

**Recommendation: C now (defer a new real-data cloud copy), with B retained as the required protection gate.**

This is an engineering recommendation for the current hold, not acceptance of privacy risk or downtime on the owner's behalf. No reviewed Free provider demonstrates the required primary-message protection. Aiven Free documentation-based support is not entitlement to Basic technical tickets; no new inquiry was submitted. Neon Free/TLS and Supabase Free/logging documentation do not establish the guarantee, so switching providers is not a verified fix. Full practical/availability comparison and sources: docs/final-infrastructure-privacy-assessment.md. Existing MySQL is already cloud-hosted; C avoids another copy rather than moving production local. Plan preservation/availability before the owner-verified21 December2026 retirement; existing snapshots become stale.

## Isolated demo publication — completed under separate approval

The owner approved and the agent completed one Cloudflare Pages Free Direct Upload of `frontend/dist-demo` from SHA `d58b0c7925266ef7fe7804dd7cc89c07fc66adb4` to new project `splitsmart-offline-demo`, URL https://splitsmart-offline-demo.pages.dev. A live HTTPS HEAD confirmed deployed CSP, Referrer-Policy, nosniff and no-store. Cloudflare also returns `Access-Control-Allow-Origin: *` and standard `Report-To`/`NEL` headers for this public static site. Result and remaining privacy notes are recorded in `docs/synthetic-demo-verification.md`. This does not authorize redeployment, replacing production, or importing real records. Production MySQL/PostgreSQL migration approval below remains separate and blocked by Option C.
## Safe before release approval

Local code review, fake-data tests, artifact preparation and record-free metadata
checks may continue. Completed this gate: strengthened schema contract matches
local reference and the pinned cloud SQL target;15 adapter +3 schema +2 reporting
unit tests PASS; one changed-code synthetic transfer PASS. Prior42 Java21,
20 frontend and4 browser scenarios remain valid and were not repeated.

Linux-native PASS retained. Rootless OCI setup, actual production image build and four synthetic app-container scenarios now PASS. Small Dockerfile fix qualifies both official base registry names. Ten new guard/transport tests PASS; exact evidence, limits, cleanup and image ID: docs/rootless-container-verification.md. Render kernel/cgroup/deployment behavior remains unverified; no production GO follows from local execution.

The approved support inquiry was NOT submitted: official form requires Basic tier, no confirmed Free entitlement. Draft: docs/aiven-primary-error-inquiry.md. No support reference or privacy guarantee exists.
Aiven authentication is COMPLETE; both services were verified RUNNING/Free and the refreshed record-free TLS/schema preflight PASS. No further login is needed. Never send credentials in chat.

## Explicit authorization required for the coordinated run

1. **Policy:** C currently prohibits real-data upload. A future policy/scope change must establish the desired stronger protection, or explicitly acknowledge residual risk under A, including potential sensitive
   provider/application logs, shared Render egress, remaining ICU/Free risks.
   The separately approved synthetic-only inquiry remains authorized through a confirmed no-cost official channel; no paid tier is authorized.
2. **Code/network:** approve the exact reviewed SHA/private push or integration,
   Render tracked-branch selection and additive PostgreSQL allowlist. Last known
   shared ranges74.220.52.0/24 and74.220.60.0/24 MUST be reverified; preserve trusted
   administrative access and independent recovery. No allow-all or billing.
3. **TLS/credentials:** configure existing restricted PostgreSQL roles and verified
   provider CA; keep JWT_SECRET unchanged. Never put backend secrets in Pages/Git.
   Management authentication is restored; no database credential resets.
4. **Window:** approve the gated backend deployment, complete MySQL writer freeze
   and draining of old instances. Then approve ONE fresh encrypted consistent
   MySQL export and ONE new isolated restore with current object/history checks.
5. **Import:** only after a reviewed approval-bound real adapter and Linux image
   test pass, authorize ONE data-only import into the exact empty V11 application
   DB. Preserve IDs/hashes/timestamps/amounts, archive MySQL history separately,
   check all hashes/balances/constraints/sequences before and after commit. No
   retries/reset on ambiguity. Approve an encrypted target recovery backup.
6. **Release:** separately approve coordinated Render/Cloudflare deployment,
   synthetic smoke fixtures and actual verification. Both backend email flags
   and VITE_EMAIL_ACTIONS_ENABLED stay false; copy-link invitations remain usable.
7. **Final GO:** after actual results are reviewed, explicitly authorize opening
   target writes. Before that, maintain the gate. After any target auth/financial
   writes, never revert to stale MySQL/V7; gate traffic and use forward recovery.

The detailed sequence remains docs/cloud-final-migration-window.md. No approval
for one item silently grants the others. If a prerequisite fails, stop that
operation. ₹0 only; preserve old MySQL, encrypted backups and keys. No paid fallback.

Sources: PostgreSQL16 runtime-config-logging; Aiven list-monitoring and permissions:
- https://www.postgresql.org/docs/16/runtime-config-logging.html
- https://aiven.io/docs/platform/howto/list-monitoring
- https://aiven.io/docs/platform/concepts/permissions
Latest coordinated MySQL release preparation: frontend source d9a2992e8965db576c86edf137b2b93501fed17e, documentation descendant914582756bdbe0e693e70cb84506d0a5c72c1d4e; backend source unchanged fromc42be751f21e5b00209292902dc186d11bc8dce3. Existing MySQL splitsmart-mysql/splitsmartV7, existing Render splitsmart-backend-ydj2 and Cloudflare splitsmart-92b only affected by future approval. No PostgreSQL real-data migration or offline-demo operation. Ordered additiveV8–V10; preserve originalV1–V7. Exact current deployed SHAs and newly pinned backend JAR/container digests remain unverified. Prior OCI evidence cannot certify a newly rebuilt candidate automatically. Fresh encrypted production snapshot + isolated restore must be separately approved/reverified before migration. Maintenance gate, no old unversioned backend, forward-only auth-version-safe recovery and smoke checks per UX-RELEASE-PLAN remain mandatory. New authorization covers ONLY isolated two-email test to approved inbox; no production email activation/deploy/push/migration.
