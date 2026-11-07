# Migration controller safety and verification

8 November 2026. Development/synthetic verification only. No real cloud import,
production freeze, source export, cloud write, deployment or privacy acceptance.

## Current executable scope

- `scripts/cloud_loader_preflight.py --metadata-only` accepts NO record input.
  It pins the existing named Free service/endpoint via protected configuration,
  uses CA+hostname TLS verification and the existing migration role, then opens
  a read-only transaction. Actual database/role/listener, V1–V11 Flyway checksums,
  expected empty business tables and validated constraints must match. This ran
  successfully. Managed endpoint NAT port and SQL listener port are distinguished.
- `scripts/migration_transfer.py` is the tested engine, not a production CLI.
  It rejects REAL classification and ALL cloud-write targets before connecting
  or creating an attempt. This is intentional pending owner privacy choice and a
  separately reviewed release-specific execution adapter. There is no hidden
  `--force` or environment switch. Classification is an operator declaration,
  not a claim that arbitrary caller data can be automatically recognized as fake.
- `scripts/verify_transfer_synthetic.py` creates one new uniquely named local
  synthetic database/role and inline fake records. No MySQL/cloud client is used.
  Successful run is recorded privately; it refuses blind reruns. Retain the DB.
- `scripts/run_native_postgres_e2e.py` starts only its own Java21/Vite processes
  on 127.0.0.1:18081/15173 using that synthetic identity. It runs the two existing
  product/circular-debt browser flows; no Docker or production/recovery cleanup.

## Engine gates

Identity/loopback scope, exact active migration checksums and empty target precede
any write. Input row widths/field names are exact; seven legacy MySQL versions,
checksums and successful status must match V7. Newer authentication fields cannot
be silently omitted under a V7 label. Every field is validated by the existing
typed validator before INSERT; ICU uniqueness uses actual local semantics.

An exclusive private attempt file prevents automatic replay. Within SERIALIZABLE
transaction: nonblocking advisory lock, NOWAIT table locks for all business tables
and active Flyway history, second empty/schema check, transactional legacy-history
archive, parameterized inserts, nine-table canonical hashes/counts and financial
ledgers, version-zero/unverified legacy auth defaults and identity floors.
Identifiers are constrained; source values never become SQL strings. ALTER
SEQUENCE RESTART uses validated numeric metadata and is transactional, unlike
setval. No constraints are disabled, data rounded or records deduplicated.

COMMIT is followed by a NEW authenticated connection and hash/count verification.
These states are intentionally distinct: ROLLED_BACK, ROLLBACK_UNCONFIRMED,
COMMIT_UNKNOWN, COMMITTED_UNVERIFIED, VERIFIED. A commit/connection ambiguity is
not proof of rollback; retain the target and ledger, reconcile read-only, and
obtain a new scoped decision before another attempt. No automatic reset/delete.

The shared native libpq factory now discards NOTICE messages rather than letting
the default C handler bypass SQLSTATE-only client error reporting. Verified using
a fake notice on the local synthetic DB. This does NOT sanitize provider-side
primary errors. The privacy NO-GO remains unchanged.

## Actual evidence

- 11 new controller unit cases PASS: wrong identity/checksum, forbidden real/cloud
  execution, exclusive no-replay, rollback failure, unknown commit/reconnect,
  redacted driver error, identifier injection and JWT-field mapping.
- New local nine-table synthetic transfer/reconciliation PASS; client Flyway
  CRC32 calculation matches actual V1–V11 history, independently checked against
  the empty cloud target through read-only preflight.
- Java21.0.12.1 package plus42 selected backend cases PASS, zero failures/skips.
  Only the new synthetic local database received fixtures. Includes auth/reset/
  JWT8, invitations8, notifications6, finance4, maintenance7, security8, profile1.
- Frontend20 focused tests, lint/build, two mocked mobile/desktop browser flows
  and two real Java21/local-PG product browser flows PASS. The real flows were
  rerun after runner isolation/output-capture hardening; count two UNIQUE flows.
- Runner environments scrub inherited database/Spring/JVM/email routing; a
  streaming secret detector checks cross-chunk values even after buffer eviction.
  Success is published only after its process cleanup, credential scan and free
  port verification. Two runner-isolation unit cases PASS.

Detailed receipts are private under LOCALAPPDATA/SplitSmart:
AutonomousSyntheticTransfer (including browser-latest.json) and CloudReadiness.
Records, credentials, plaintext logs and dumps are absent from Git. Failure logs,
if needed, are DPAPI ciphertext only. Reports contain no user-level records.

## Release implications

Use the existing one-window plan in cloud-final-migration-window.md. Required
before actual migration: owner privacy decision, approved real-execution adapter
bound to an approved release SHA and NEW frozen snapshot hash/source identity,
explicit import/backup/window/deployment approvals, fresh Free/network/TLS
preflight and container startup verification. This prototype's source fixtures
are not a final production snapshot or permission to upload recovered records.
Do not delete safety gates to repurpose the local runner.
