# Local PostgreSQL real-data rehearsal — 8 November 2026

PASS locally. No cloud connection, production access, deployment or upload was
performed. This is a recovery-snapshot rehearsal, not current production data
or approval to cut over. Previous MySQL/PostgreSQL recovery databases, GPG keys
and encrypted backups are retained unchanged.

## Destination and evidence

- Existing protected PostgreSQL 16.11 cluster: 127.0.0.1:25432; a single new
  database `ss_pg_local_real_20261107t213930z` is retained for inspection.
- Source: verified fresh Stage 3 MySQL recovery database on 127.0.0.1:23307.
  Its private preservation receipt, ciphertext checksum and public GPG recipient
  fingerprint were checked before use. No backup decryption/export was needed.
- Existing machine receipt confirms Protection ON, full C: encryption and TPM/
  recovery protectors. Owner subsequently confirmed independent recovery escrow.
  Current non-elevated status query requires Administrator access; it was not
  misrepresented as a new successful encryption measurement. Existing verified
  protection evidence was reused as requested; no Windows setting changed.
- More than 8 GiB capacity available (approximately 30 GiB at preflight).
  PostgreSQL data/WAL/temp/logs, MySQL recovery data, backup and GPG directories
  passed owner/SYSTEM-only recursive ACL and reparse-point checks on C:.
- A fresh restricted login owns only the new database. No superuser, CREATEDB,
  CREATEROLE, replication or RLS bypass; PUBLIC database access revoked.
  Credentials exist only in RAM and a current-Windows-user DPAPI file in private
  storage. No passwords, source rows, counts or financial totals are in Git.
- Private result and credential: `%LOCALAPPDATA%\SplitSmart\LocalPgRealDataRehearsal`.
  Record counts, canonical hashes, checks and preparation history are there.

## Validation before writes

`scripts/migration_validation.py` checks each imported field against actual V11
metadata: exact mapping, supported types, nullability, signed integer ranges,
NUMERIC precision/scale without rounding, UTF-8/NUL, character limits and fixed
CHAR representation, calendar dates, microsecond timestamps and UTC conversion.
It checks PK/unique keys, FKs, dependency order, known CHECK conditions, positive
amounts, distinct settlement endpoints, expense/split equality and source identity
floors. Unknown types/check definitions/expression indexes fail closed.

`scripts/local_pg_rehearsal.py` additionally validates all relevant ICU unique
keys with parameterized read-only queries on this local server's actual collation;
Python casefold is not used as an ICU substitute. Fake case/accent duplicates
were rejected before real records were read. Financial ledgers conserve each
group/currency; settlement ledgers are compared separately because settlements
have no currency column. Every original field is later hashed, including password
hashes and timestamps, without displaying or copying records into documentation.

Twenty-three focused Python tests PASS. Invalid integer, duplicate key, FK,
excessive text and malformed timestamp cases prove the writer is never called.
Additional tests cover ranges, scale, Unicode, dates, nullability, enum/amount/
settlement checks, sequences, ICU UUID typing and restricted identity guards.
Failure output contains fixed codes only, not synthetic input markers.

## Local privacy boundary

Only the new migration role/database and local admin sessions used for this
operation receive a targeted privacy policy: primary SQL errors and statements
are not persisted for these sessions, including transaction/slow-query sampling
and bind values. No global security/audit policy was disabled. Other sessions
retain their existing settings; cluster `log_min_messages` remains WARNING.
Server extensions that could introduce unreviewed logging are absent. Errors
still abort through SQLSTATE-only handling; libpq NOTICE payloads are discarded
in this controller rather than printed. PANIC-level diagnostics remain enabled.

A fake parameterized integer-conversion error produced expected 22P02, and its
random marker was absent from the protected server log before and after import.
Credential scan PASS; no plaintext SQL files, raw record reports or dump files
were created. This is not a universal guarantee against every PostgreSQL primary
error or provider diagnostic. Local logs/data remain on encrypted private storage.

## One-attempt results and preparation correction

Exactly ONE new database and role were created. Preparation initially stopped
before any schema/data import: the restricted role could not read data_directory
(42501). Read-only inspection confirmed zero tables and zero real-data attempts.
The guard was corrected to verify the directory via existing admin access, then
match every restricted session to the same loopback endpoint and postmaster
incarnation. No extra grants were given. Only this verified empty preparation
was resumed; no real-data import was retried. An earlier read-only query-builder
type error for text request_id was also corrected and regression-tested before
creation/import.

Flyway V1–V11 migration/validation test: 1 PASS, zero failures/errors/skips. All
original MySQL migration files are unchanged. Active PostgreSQL history remains
independent; all seven original MySQL history rows/checksums are preserved in
legacy_mysql_flyway_history. No Flyway repair or history replacement.

The real-data attempt marker was persisted before BEGIN. All nine source table
counts and every-column canonical hashes PASS, including archived history.
Independent fresh snapshot manifest counts/financial totals match the local
source. Exact amounts, account ledgers, FKs, unique constraints, validated indexes
and source AUTO_INCREMENT floors PASS. A new authenticated connection confirmed
committed counts/hashes. Legacy auth version zero/unverified-email defaults PASS;
new recovery/invite-link/notification tables stay empty. No application, email,
notification worker or outbound integration was started.

Do not run this import again. Its private attempt marker refuses repetition.
Do not delete the retained database or rotate the key automatically.

## Remaining cloud/release gates

Local ICU version/actual version: 153.14 / 153.14. Recorded managed PostgreSQL
16.15 catalog versions include 153.128 / 153.128.47. Differences include OS/ICU
libraries, not merely PostgreSQL patch number. Local uniqueness success does not
prove managed Unicode equivalence; a later managed check needs explicit approval
and a resolved/reviewed logging privacy boundary. Current Aiven primary-message
leak remains BLOCKED; no provider policy or IP filter was changed here.

Official 16.15 release notes require no dump/restore for an existing 16.x
installation, but include security and correctness fixes absent from 16.11.
This rehearsal uses no logical decoding, pgcrypto or psql inline COPY scripts;
it does not certify all patch-level behavior or make 16.11 a production baseline.
No existing installation was upgraded. Exact managed-version behavior remains
a distinct release gate despite prior synthetic V11 application tests passing.

Render's current desktop-only /32 does not authorize the backend. Before any
approved cutover obtain the actual service Connect > Outbound CIDR list, review
shared regional-source trust, and test verified TLS/restricted roles/reconnects.
Routing rotates within those ranges and can reset connections; do not assume a
single static egress IP or open all addresses. No live Render check/change needed
to repeat the already verified MySQL configuration this phase.

Final migration must separately approve: all-writer maintenance/freeze and drain;
a NEW full consistent encrypted source snapshot/export/restore with reconciliation;
then an explicitly approved real-data target import. No CDC/delete-aware delta
replay exists. Frozen source totals/counts and snapshot data must agree; any live
writer or drift is a stop. Recheck schema/object scope and actual source history.
Use explicit V7 field mappings into the verified empty V11 schema with constraints
enabled, archive legacy history separately, validate all hashes/finance/sequences
before commit, and independently secure/rehearse target backup recovery. Keep
MySQL and all prior recovery material. Backend/frontend deployment and opening
writes require separate approval; email stays disabled. After token-version/new
financial writes, use monotonic forward recovery; never revert to V7/stale MySQL.

Next approval: provider clarification of primary-error logging/redaction and
retention/access, or a specifically reviewed residual-risk decision BEFORE any
managed real-data rehearsal. This local PASS does not make that decision for the
owner. No cloud import, production freeze or release approval is inferred.

Official references reviewed for this assessment:
- https://www.postgresql.org/docs/16/runtime-config-logging.html
- https://www.postgresql.org/docs/release/16.15/
- https://aiven.io/docs/products/postgresql/reference/advanced-params
- https://render.com/docs/outbound-ip-addresses
- https://render.com/docs/outbound-connection-resets
