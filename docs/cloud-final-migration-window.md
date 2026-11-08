# One-window PostgreSQL cutover — plan only, not execution approval

8 November 2026. Depends on the decision in cloud-privacy-decision.md.
No real-data upload, production freeze, configuration change or deployment was
performed. Existing MySQL, all recovery databases and encrypted backups remain.

## Verified networking and release controls

Existing Render backend `splitsmart-backend`: Singapore, Free, Docker, rootDir
`backend`; tracks `codex/product-completion`, autoDeploy=no. Readiness path is
`/actuator/health/readiness`. Release work is on `codex/password-recovery-backend`;
do not assume pushing that branch deploys the intended artifact. A reviewed
private merge or approved tracked-branch change and an exact SHA are necessary.

The official authenticated GET /services/{serviceId}/outbound-ips returned shared
CIDRs **74.220.52.0/24** and **74.220.60.0/24**. These default shared ranges are
usable without buying dedicated IPs. Prepare the target-only Aiven ip_filter as
the UNION of its existing reverified trusted administrator /32 and those TWO
CIDRs; remove neither current access nor add allow-all/IPv6 indiscriminately.
The exact current/proposed JSON is private at
`%LOCALAPPDATA%\SplitSmart\CloudReadiness\render-network-proposal.json`.
This proposal was NOT applied. Re-fetch ranges and desktop egress immediately
before the separately approved update; the IPs are not a permanent contract.

Any Render service in those shared regional ranges can reach the PostgreSQL
network listener. Authentication, TLS verify-full, role isolation and protected
secrets remain essential; CIDRs do not authenticate this specific backend.
Routing rotations can reset existing connections. Validate Hikari reconnection
and existing idempotency; never blindly replay unknown-outcome financial writes.
Future range changes require the same controlled allowlist update. Existing
authenticated Aiven HTTPS management access is independent of SQL ingress and
is the recovery path if desktop egress changes; no allow-all recovery shortcut.

Render built-in maintenance mode is PAID-ONLY. Do not enable it or upgrade. The
new local default-OFF APP_MAINTENANCE_ENABLED gate blocks ALL API requests, even
login/reset/GET routes and preflight, with 503/no-store/Retry-After. Only exact
readiness/liveness read probes continue through normal security. Notification
retention skips its database work while gated. No bypass token/header exists.
The guard was tested locally only; it is not active in production today.

Docker startup was also adapted locally: existing MySQL defaults retain the
PKCS12 path; the explicit `postgresql,postgres-prod` combination instead decodes
the provider's PUBLIC PEM CA from DB_TLS_CA_BASE64 into a mode-restricted /tmp
file and exports DB_TLS_CA_FILE. Both base64 inputs are removed before Java.
Missing/empty/invalid inputs and conflicting database profiles stop startup.
The entrypoint is included in Docker's narrow build context and forced to LF.
POSIX-shell tests use dummy CA/P12 and fake Java; a new Linux container build and
actual Render UID/file/TLS startup remain deployment-preflight checks, not a
claimed test result. No Docker daemon operation was attempted.

## Preconditions BEFORE scheduling the single window

- Owner resolves or explicitly accepts the documented privacy risk; approve the
  actual final upload, not a general statement of comfort. Keep email disabled.
- Prepare/review a cloud-specific, one-attempt data-only loader using the tested
  V7 mappings/validator. It must bind the exact target service/database/roles/CA,
  verify V11/checksums/empty business tables, refuse the synthetic/default DB,
  archive legacy history through the migration role and stop on any SQL error.
  Use explicit columns, parameterized writes, SQLSTATE-only failures and a notice
  sink, WITHOUT assuming managed SET privileges can reproduce local logging.
  Do not repurpose the local-only runner by disabling its safety checks.
- Authorize only harmless synthetic dry-run tests of that controller before the
  window. Previously passing 23 local/26 cloud tests need no broad replay.
- Identify ALL MySQL writers (backend, admins, scripts, jobs/integrations); agree
  that none write during the window. Stop if that cannot be established. The API
  guard alone does not freeze someone else's SQL client.
- Approve the exact code SHA/private push/merge or branch-selection step. Keep
  hosting autoDeploy OFF. Build the candidate artifact before the window; retain
  the previous code/config metadata privately. No secrets in Pages or source.
- Recheck actual quotas and private GPG recovery/storage. Approve ONE new final
  MySQL export/local restore and encrypted target backup. Existing successful
  export/restore allowances are consumed; previous snapshots are NOT final data.

## Single window — checkpoints and stop conditions

1. **Target-only network preparation:** after its own approval apply only the
   proposed IP-filter union. Validate authorized TLS connections from the actual
   Render deployment when available, and retain management recovery access.
   Confirm target schema V1–V11/empty data; no MySQL V8–V10 migration is needed
   or permitted as a side effect of this PostgreSQL cutover.
2. **Enter gated deployment:** deploy the approved PostgreSQL candidate to the
   existing Render service with APP_MAINTENANCE_ENABLED=true, profiles
   `postgresql,postgres-prod`, SPRING_FLYWAY_ENABLED=false, ddl-auto=validate,
   verified CA at DB_TLS_CA_FILE, runtime-only DB credentials and the unchanged
   JWT_SECRET/CORS/PORT/JVM settings. Keep RECOVERY_EMAIL_ENABLED=false and
   RECOVERY_EMAIL_DELIVERY_VERIFIED=false; do not configure/activate Brevo.
   Separate migration credentials stay private and are not runtime grants.
   Do not combine the old MySQL prod profile with these profiles.
   Provide DB_TLS_CA_BASE64 from the verified provider public CA (not a private
   key); the new entrypoint supplies /tmp/aiven-postgres-ca.pem automatically.
   Leave SPLITSMART_TLS_DIRECTORY unset in deployment. No paid secret mount or
   group/UID change is required. Stage configuration with Save only, then deploy
   the reviewed candidate explicitly; saving some hosting settings can otherwise
   trigger an unintended deployment even with Git autoDeploy disabled.
3. **Prove complete freeze:** old MySQL-backed instance may keep serving during
   Render's replacement startup. Do NOT snapshot yet. Wait for traffic to receive
   the new 503 gate, old instances/in-flight requests to drain and terminate,
   scheduled cleanup to be disabled and every other writer to stop. Confirm via
   approved read-only source metadata and stable reconciliation that no source
   writer remains. Health UP means DB connectivity, NOT completed migration. If
   old instances persist or app deployment fails, do not assume a freeze.
4. **Fresh full snapshot:** verify source identity/Flyway V7/checksums and object
   inventory again under the freeze. Take the separately approved transactional,
   non-locking encrypted snapshot, capture reconciliation/next IDs while writers
   remain frozen, and restore into a NEW private local database. Validate counts,
   all-column hashes, finance, constraints and source object coverage. The source
   remains unchanged; no guessed timestamp/ID delta replay or silent deduplication.
   Counts and data must describe the same frozen state. Any drift stops the window.
5. **Validate then load:** rerun the validator against this FINAL snapshot,
   including every collated key; non-ASCII/new schema/domain changes require
   explicit review. Only the specifically approved import may send real values
   to cloud. Use one data transaction with active FKs/CHECK/uniques, preserved
   IDs/password hashes/UTC times/exact decimals and legacy MySQL history archive.
   PostgreSQL active Flyway history is V1–V11, never replaced with MySQL V1–V7.
   Import original V7 fields only: auth_token_version defaults to 0, legacy email
   proof stays NULL, new secure token/invite/notification tables remain empty.
   Reject an unexpected V8+ source rather than resetting any token version.
6. **Before commit:** reconcile all nine source counts/canonical hashes, original
   finance/account ledgers, FK/unique/validated indexes, source AUTO_INCREMENT and
   MAX(id)+1 sequence floors, UTC/microseconds and legacy checksums. Unknown error
   aborts without retry or record logging. Sequence setval is not transactional;
   changed sequence floors affect only the isolated target, never the source.
   Never auto-delete/reset tables to retry. Keep the public gate ON.
7. **After commit, still gated:** validate through a new authenticated connection,
   verify runtime no-DDL/history rights, and take the newly approved direct-to-GPG
   target logical backup including all V11 application tables, active/legacy
   history and sequence state. Existing synthetic restore evidence does not
   certify this new artifact; verify its encryption/checksum/coverage. No paid
   provider-fork recovery dependency. Deploy matching Cloudflare frontend with
   exact VITE_API_BASE_URL and VITE_EMAIL_ACTIONS_ENABLED=false. No secrets there.
   Review JWT signature/issuer/expiry/version config against the prior release;
   default missing-version legacy tokens map to 0, new tokens carry current
   version, resets consume once/increment atomically. Reuse existing synthetic
   tests; do not log in as real users or request their passwords/tokens.
8. **Explicit final GO:** with all earlier checkpoints passing, separately approve
   disabling APP_MAINTENANCE_ENABLED on the PostgreSQL release. This restart/
   deployment is an approval step; it was not performed here. FIRST ungated target
   process/background mutation or public accepted mutation is the authority-change
   boundary. Perform authorized disposable login/authenticated API, invitation,
   notification/dashboard/financial smoke flows, plus owner voluntary normal
   login; no impersonation. Keep old-JWT revocation/versioning checks synthetic.
   Monitor cold starts, connection resets, quotas and redacted errors. No paid
   fallback, email activation, source deletion or automated cleanup follows.

## Recovery decisions

Before the frozen snapshot, the current V7 MySQL service remains authoritative;
an unsuccessful gated replacement might still leave it serving. Abort without
exporting until its exact state is known. Once the new gate holds and all writers
are drained, preserve that freeze through every validation/deployment decision.

Before ANY target application writes, the frozen MySQL remains the data authority.
If import/schema/validation fails, preserve both databases and ciphertext, keep
503, investigate the redacted failure and obtain approval for a forward fix/new
attempt. An operator may decide to abort the whole migration and restore the
previous source configuration ONLY after proving no target app/token changes and
receiving separate recovery approval. No automatic old-build rollback.

After ANY target application/token/financial mutation, NEVER switch to stale MySQL
or an unversioned V7 backend. There is no reverse replication. Re-enable the gate,
drain writers, preserve incident state and use a compatible forward fix or an
approved target recovery with full data/token-version reconciliation. An older
backup can resurrect tokens or lose money records; do not reopen authentication
until monotonic auth versions/records are reconciled. Failed post-GO smoke tests
are a maintenance/forward-recovery incident, not permission to roll back to V7.

Keep existing MySQL, both encrypted MySQL backups, local recoveries and GPG keys
until a separately approved retirement plan preserves them before 21 December
2026. No automatic deletion is part of this plan.

Sources:
- https://api-docs.render.com/reference/retrieve-service-outbound-ips
- https://render.com/docs/outbound-ip-addresses
- https://render.com/docs/outbound-connection-resets
- https://render.com/docs/maintenance-mode
- https://render.com/docs/deploys
- https://render.com/docs/free

## Updated gate evidence — 8 November2026

Actual Linux-native Temurin21.0.13+9 release-JAR startup against guarded synthetic PostgreSQL PASS: verify-full/identity and rejected bad CA, Flyway V11 current, readiness/liveness and maintenance. This does not validate Docker image, nonroot UID, /app entrypoint or live Render. Container remains mandatory and BLOCKED. Provider inquiry draft saved; no free support submission confirmed, privacy gate unmet. No change to required freeze/fresh snapshot/real import/release approvals or no-V7-rollback rule.
