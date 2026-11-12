# Master CTO execution status — 13 November 2026

Production: **NO-GO**. No production write, migration, deployment, new email, cloud real-data import or push was performed. Offline portfolio demo unchanged.

## M1 — Audit complete; owner decision required

Authorized read-only consistent-snapshot aggregate audit: Aiven `twinklesangani-9b5e / splitsmart-mysql`, database `splitsmart`, RUNNING, Free-1-1gb, Flyway V7. Counts: users 8; groups 4; memberships 6; expenses 3; splits 5; settlements 1; invitations 0. Notifications table is absent at V7, not an observed zero. See `docs/production-aggregate-audit.json`.

No production creation-provenance ledger proves any account synthetic: 0 confirmed synthetic users, 8 unknown users. No personal records were retrieved to infer identity from names. Preserve all records by default. A clean launch would remove existing accounts, memberships and balances from the active application even if encrypted archives survive. Owner must explicitly approve that disposition; it has not been selected.

The November 12 encrypted backup checksum still matches `a70bf90feaaf251e51b07b047e0e45e8abee84fb62fdeb9d2d8b33ca11890f1a`. Its prior one-export/one-restore reconciliation remains valid for that snapshot, not later production writes. Independent protected copy remains unverified; do not copy externally without an approved destination.

## M2 — Compatibility PASS; production decision BLOCKED

Recommend existing Aiven PostgreSQL Free conditionally for the long-term architecture: no card, 1 CPU/1GB RAM/1GB storage, 20 connections, single node, no SLA. Existing separate schema V1–V11, 26 synthetic integration tests, local real-data nine-table hashes/financial/FK/sequence validation and synthetic backup/restore evidence are reused. No new database/schema was created or historical-data upload attempted.

Official MySQL retirement documentation confirms deletion of remaining Free services, data and backups on **21 December 2026**. MySQL is a temporary bridge, not a long-term free option. Neon Free is a potential no-card alternative, but switching providers does not establish primary-error redaction or eliminate privacy exposure. No new provider provisioned.

Privacy Option C remains binding. Parameterization, local prevalidation, restricted logs/roles/IPs and TLS reduce risk but cannot guarantee unexpected PostgreSQL primary errors omit values. Clean initialization avoids historical import but still exposes future application records to this risk. Owner must choose stronger verified protection, explicit informed risk acceptance, or continued postponement. Do not silently redefine a clean launch as acceptance.

Official references checked: [retirement](https://aiven.io/docs/products/mysql/howto/mysql-free-tier-migration-options), [PostgreSQL Free](https://aiven.io/docs/products/postgresql/concepts/pg-free-tier), [Render Free](https://render.com/docs/free), [Neon Free announcement](https://neon.com/blog/neon-free-plan-1-gb-per-project).

## M3 — Local compatibility PASS; live changes not authorized

Last deployment-metadata verification: November 12. Active Cloudflare frontend `bc59921336f148784317550a5f29202edbeda825`, deployment `0294158a-95e6-4b55-9757-237afb3bfb28`; Render backend `418d757855dd10965c2872176022f93ac5e35a8a`, deployment `dep-dbm583k9v7es738tqjng`. These historical metadata observations were not refreshed this session. November 13 read-only HTTPS checks returned 200 with certificate validation for both public apps, offline `/demo`, and backend `/api/health`; health is not a new deployment or schema-compatibility proof.

CORS, environment flags, TLS and frontend/API pairing remain covered by the existing pinned manifest and tests. Backend email enablement/delivery-verified and frontend email-actions flags stay false. Brevo HTTPS authentication and the exactly two approved recovery emails passed previously; no messages resent. Public recovery origin and sender must be confirmed against the selected frontend before any later email enablement. Render Free sleeps after 15 idle minutes and is best-effort, with shared quotas and ephemeral local storage.

## M4 — Release preparation PASS; execution BLOCKED

Compatible backend/frontend source: `a1813f80d9c22e06ae89e2dd7f8b2ba6a0f02c16`; later verification commits leave `backend/src/main` and `frontend/src` unchanged. Existing JAR/frontend hashes and rootless container digest are in `docs/release-candidate-artifacts.json`. Container startup/TLS/security evidence is synthetic PostgreSQL evidence, not proof of a completed Render or MySQL deployment.

Preserve MySQL V1–V7 checksums; additive V8–V10 rehearsal passed, including approved real-backup local rehearsal. PostgreSQL uses its own V1–V11 history. Never copy MySQL history into PostgreSQL active Flyway history. Never deploy an unversioned V7 backend after resets or roll authentication versions backward.

Final approved release window must: establish maintenance/write-freeze; take fresh encrypted consistent snapshot; verify isolated restore; execute only the chosen approved schema/import path; validate counts/hashes/financials/sequences and least privileges; deploy compatible backend while guarded; verify readiness/auth; deploy matching frontend; smoke-test; explicitly enable writes. Preserve MySQL and recovery material. On failure before new writes, keep maintenance and recover forward. After new writes, do not point traffic at a stale database without reconciled recovery.

A clean database also needs separately approved complete JWT revocation, such as signing-key rotation, to prevent old tokens authenticating newly reused numeric user IDs. No secret rotation performed. Independently protected backup destination, data disposition/privacy, network allowlist, exact database/credentials and release window require owner decisions.

## M5 — Existing execution evidence reconciled

Reuse unchanged batches: 34 Java authorization/settlement; 16 Java recovery/configuration/guard; 9 actual database recovery cases; 39 React dashboard/auth/recovery; real-backend responsive and Settings browser journeys; MySQL synthetic and approved restored-data V7→V10; rootless container health/TLS rejection scenarios; exactly two delivered recovery emails with password/JWT/token-reuse checks. These are separate overlapping batches, not an additive universal test total. No application code changed this milestone; unrelated tests were not rerun. No new claim of exhaustive security coverage.

## M6 — Documentation and portfolio preparation complete

README now separates the published fictional offline simulation, older live full-stack application and enhanced local candidate. Added actual API inventory, plaintext architecture, verified synthetic desktop/mobile dashboard screenshots and accurate limitations; updated existing recruiter/resume/LinkedIn kit. Screenshots came from generated `example.test` product fixtures and were visually reviewed; they are not fresh production screenshots. Public links returned 200 over validated HTTPS. No resume/LinkedIn account, GitHub publication or offline-demo artifact changed.

## Latest owner decision — preservation mandatory

Owner now requires preserving all eight accounts and associated records; clean launch is not authorized. Read `docs/preserved-account-release-gate.md` for the narrowed current database/release decision and independent-copy workflow. Earlier pending clean-launch alternatives below are historical, not an active authorization.

## Next owner decisions / authorization boundary

1. Preserve all existing accounts and records (owner decision confirmed). No clean launch, deletion or abandonment authorized.
2. Retain Privacy Option C, require stronger provider-side guarantees, or expressly accept documented residual PostgreSQL logging risk for future records and separately authorize any historical import.
3. Confirm an exact independent encrypted-backup copy destination before copying; recovery keys stay separate.
4. Only after those decisions, approve one exact release scope: any push, database changes, final snapshot/write-freeze, network/secret changes, coordinated deployments and optional production email activation. These remain separate from this read-only/local milestone.

Handoff: read this status, existing release artifact manifest and actual Git status first. Preserve unrelated dirty operational scripts/documents and all backups. Do not replay the exhausted email harness. Do not create a new backup, restore real data or change production based solely on this document.
