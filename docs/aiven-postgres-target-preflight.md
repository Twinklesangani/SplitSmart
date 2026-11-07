# Stage 2 — Aiven PostgreSQL Free target preflight

7 November 2026. Explicit approval covers ONE new Free service and metadata-only
checks; it does not cover application schema, roles, data import or deployment.

## Local work preserved

Compatibility implementation committed locally as
`6888d48faccfa7693f04ea136bb397f80f6149c9` on
`codex/password-recovery-backend`. Commit includes only reviewed PostgreSQL code,
separate migrations/tests/runbook, a small shared local database support module
and a selected progress-summary hunk. Earlier preservation/restore documents,
the previous GPG/MySQL restore utility, pending progress notes and the original
workspace MySQL test edit remain unstaged/unmodified. No push or merge.

Staged paths were allowlisted and scanned privately against actual Aiven/local
database credential values. No export, private key, passphrase, record data,
credential file or personal Windows path was committed. Original MySQL V1–V10
files were not changed. Existing 40 passing tests were not repeated; extracting
the portable support module was verified with read-only local copy validation.
Cosmetic blank EOF lines in already-applied PostgreSQL migration files were kept
rather than rewriting verified SQL merely to silence a Git whitespace warning.

## Creation receipt

- Project: `twinklesangani-9b5e`
- ONE service: `splitsmart-postgres`; type `pg`
- Plan: `free-1-1gb`; authenticated catalog price **$0.000/hour / $0/month**
- Cloud/region requested and assigned: `do-sgp`, Singapore, matching the existing
  Render backend region. This was explicitly advertised at zero cost by the API;
  no paid geography or upgrade was used. The console documentation says Free
  region selection may be automatic; actual assignment was checked separately.
- Requested PostgreSQL major: 16 (present in the provider's supported enum)
- 1 GiB disk, 1 GiB RAM, 1 CPU, single node
- Termination protection enabled on the NEW service only
- No trial/payment/card/billing API call, MySQL update, data upload or Flyway call

An exclusive private creation marker prevents another creation request. Creation
was accepted; initial state REBUILDING. Final SQL/readiness evidence is recorded
below after provisioning completes. Never repeat creation to fix startup delays.

## Completed target checks

Final service state **RUNNING**, still `free-1-1gb`, Singapore, $0.000/hour.
Actual SQL engine **PostgreSQL 16.15**, a newer patch of the locally tested major
16 (local 16.11). No SQL application migrations or production data were sent.

- Authenticated libpq connection PASS with `sslmode=verify-full` and the project
  CA retrieved over verified HTTPS. Server hostname/CA validation retained;
  session TLSv1.3, 256-bit. No unverified-TLS fallback.
- Aiven's current API redacts passwords by default as `<redacted>`. The old CLI
  wrapper did not expose its documented `include_secrets=true` option. Initial
  placeholder authentication failed; resolved by a permitted GET for only this
  new service's existing default account. No password reset/user creation.
  Retrieved credential kept only in RAM and Windows DPAPI ciphertext. API secret
  redaction documentation: https://aiven.io/docs/tools/api/secret-redaction .
- Default database `defaultdb`. Managed default account: not superuser; has
  CREATEROLE, CREATEDB, LOGIN, REPLICATION and BYPASSRLS. Database CONNECT/CREATE
  and public schema USAGE/CREATE are available. This supports later separate
  migration/runtime roles; no role, permission grant or application database was
  created. Do not use this broad default identity as the application runtime.
- `max_connections=20`, superuser-reserved=3, reserved_connections=0. Plan for
  17 ordinary slots before provider/application occupancy; small Hikari pools
  and controlled deployment overlap are required. Six login-enabled catalog
  accounts were counted; that is NOT a count of active connections.
- Server timezone GMT (zero UTC offset). Synthetic timestamp literals retain
  `.123456` and explicit UTC conversion unchanged. NUMERIC(19,2) preserves its
  17-digit integer maximum and decimal `0.1+0.2=0.30` exactly.
- 883 ICU collations available; catalog ICU versions 153.128 / 153.128.47.
  No pre-existing nondeterministic ICU collation. PostgreSQL 16 supports the
  application's intended nondeterministic ICU definition, and schema CREATE is
  allowed, but actual collation creation and exact managed-target application
  integration tests await explicit schema/DDL approval. Local 40-test evidence
  does not claim that cloud migrations/tests have already run.
- **Zero public application base tables**. No Flyway history or SplitSmart
  schema/data created. All SQL was inside `BEGIN READ ONLY` / `COMMIT`.
- Actual provider backup inventory: **one backup**. It was listed, not restored
  or downloaded. Plan metadata's `max_count=0` is not interpreted as proof that
  the inventory is empty or that a particular retention period is guaranteed.
  User-restorable/PITR recovery at zero cost remains unverified (see below).

Harmless target metadata preflight PASS. Independent recovery readiness and
least-privilege role/schema execution remain gates for the next approved phase.
Existing MySQL service remains RUNNING on its existing Free plan; no updates.
Five offline provisioning/placeholder guards PASS; existing 40 compatibility
tests were not unnecessarily rerun. No Render/Cloudflare settings changed.

Private API/SQL helper: `scripts/aiven_postgres_preflight.py`. Default operation
only inspects this target. `--create-free-once` is the explicitly guarded mutating
operation; `--sql-metadata` uses an authenticated READ ONLY transaction. Raw API
responses and credentials are not printed. Provider hostname/password are kept
private; connection material, if available, is stored only as Windows DPAPI
ciphertext under the restricted private preflight directory. Provider project CA
is retrieved over verified HTTPS and used with libpq `sslmode=verify-full`.

## Free recovery and operational constraints

Official Free tier: one service per type per organization, no VPC/static IP,
no connection pooling, max_connections 20, no HA/SLA/support. It may be powered
off for lack of initial use within the first hours, or later inactivity with
notification. No keepalive abuse, paid upgrade or automatic service changes.

The actual Free catalog advertises backup metadata interval 24 hours, max_count 0,
recovery_mode pitr. These fields alone DO NOT prove a retained, user-restorable
recovery point. Aiven's documented manual restore creates a fork, while Free
cannot be a fork destination and only one Free PostgreSQL is allowed. There is
no verified zero-cost provider-fork recovery workflow. Do not trigger a paid fork.
Independent encrypted logical PostgreSQL backup plus protected local restore
rehearsal is a release requirement; it must be separately approved. Existing GPG
MySQL backup/recovery copies remain preserved.

Official sources checked:
- https://aiven.io/docs/products/postgresql/concepts/pg-free-tier
- https://aiven.io/docs/products/postgresql/concepts/pg-backups
- https://aiven.io/docs/products/postgresql/howto/restore-backup
- https://aiven.io/docs/products/postgresql/concepts/dba-tasks-pg
- https://aiven.io/docs/products/postgresql/get-started

## Next approval boundary

After metadata/TLS gates pass, separately approve target-only setup of migration
and runtime roles, a private application/test database, PostgreSQL Flyway
V1–V11, and disposable SYNTHETIC integration fixtures/permission tests. Do not
upload production data at that stage without an additional explicit approval.
Also approve a synthetic encrypted PostgreSQL backup/local restore rehearsal to
prove independent recovery at zero cost. Cloud schema/role writes are not part
of this Stage 2 approval. Fresh source snapshot, authoritative excluded-object
inventory, maintenance freeze, production import/cutover and deployment remain
later separately scoped approvals. Email stays disabled; no V7 rollback after
token-version rollout. See postgresql-migration-readiness.md.
