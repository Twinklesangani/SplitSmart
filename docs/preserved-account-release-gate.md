# Preserved-account release gate — 13 November 2026

**Owner decision: preserve all eight existing production accounts and every associated record. No clean launch, abandonment of account access or destructive disposition is authorized. Production release remains BLOCKED.** This narrowly scoped gate supersedes the pending clean-launch choice in earlier reports.

## Independent backup

Source ciphertext: `C:\Users\TWINKLE SANGANI\AppData\Local\SplitSmart\Backups\splitsmart-v7-fresh-20261112T163528Z.sql.gpg`.
SHA-256: `a70bf90feaaf251e51b07b047e0e45e8abee84fb62fdeb9d2d8b33ca11890f1a`; 3818 bytes, rehashed PASS. Original unchanged; prior isolated restore/financial verification reused. The snapshot does not cover later writes.

Read-only Windows disk/volume inspection finds one physical NVMe disk and C: plus its recovery volume; **no USB detected**. C: has approximately 15.1 GiB free. A OneDrive directory exists, but that proves neither an authenticated account nor capacity, privacy approval or independence from local sync. No personal cloud files were inspected and no upload was performed.

Recommend an owner-controlled, physically separate USB kept securely offline. After it is detected, propose `<verified USB drive>:\SplitSmart\EncryptedBackups\2026-11-12\` and obtain approval of the exact drive identity/path. No drive letter is invented. Only this ciphertext is eligible; never copy keyring, credentials or passphrases. Preserve all previous files. Keep recovery material separately recoverable; do not transfer it under the ciphertext-copy approval.

Prepared `scripts/protect_independent_backup.py`: default plan-only; requires USB bus metadata, absolute local path, sufficient capacity, source hash, no reparse redirection and unused output. Actual copy requires the explicit approved-copy option plus matching reverified disk UniqueId. Exclusive file creation prevents overwrites; streaming copy is flushed and both files rehashed. Failure stops, keeps the source and any suspect partial output, never deletes/retries. Eight synthetic tests PASS. It has not run against a real USB. After verified copy, safely eject and disconnect the USB manually. Ciphertext is still sensitive: physical custody and passphrase strength matter.

## PostgreSQL privacy — BLOCKED

Read-only TLS connection to the existing guarded synthetic database via the test migration role confirms: `log_error_verbosity=terse`, `log_min_error_statement=error`, `log_parameter_max_length_on_error=0`, `log_statement=none`, `log_min_duration_statement=-1`, `log_min_messages=warning`. Runtime-role guard metadata was denied (42501); no privileges were broadened. The permitted migration-role read succeeded. No application rows, new failure probe, schema write or settings change.

Existing synthetic parameterized integer conversion (22P02) placed its synthetic input in the provider-accessible primary error message despite these controls. No new supported control justifies replaying that known failure. PostgreSQL documentation confirms TERSE omits supplemental details, not the primary message. Aiven `service_log` describes Console/API log storage, not guaranteed redaction before all provider copies. Account log-access controls/retention reduce exposure, not collection. TLS protects transit; parameterization and prevalidation reduce failure probability, not server error-payload exposure. Sanitized application error responses cannot remove provider-side copies. No guarantee is made for all JDBC/Hibernate/platform logging paths.

Privacy Option C remains: **no real-data PostgreSQL cloud import**. Existing integrity/ICU/validator successes do not resolve this policy. No paid option, provider switch or logging suppression is inferred as a solution.

Official sources rechecked: [PostgreSQL logging](https://www.postgresql.org/docs/16/runtime-config-logging.html), [Aiven supported parameters](https://aiven.io/docs/products/postgresql/reference/advanced-params), [MySQL retirement](https://aiven.io/docs/products/mysql/howto/mysql-free-tier-migration-options).

## Database decision

A. **Recommended interim release path: retain existing Aiven MySQL `twinklesangani-9b5e / splitsmart-mysql / splitsmart`, upgrade additively from V7 to V10 during a separately approved window.** Accounts/password hashes, IDs, group relationships, obligations and settlements remain in place. Rehearsals already passed; no new data transfer required. This is temporary, not a final sustainable architecture: provider deletion on 21 December 2026 includes backups.

B. **Preferred technical long-term candidate, blocked by privacy policy:** existing Aiven PostgreSQL Free, with all records migrated after a frozen final snapshot; separate PG V1–V11 schema/history, preserved IDs/hashes/financials, legacy history archival and sequence reconciliation. Requires verified stronger logging protection or an explicit informed policy change plus separate real-data import approval. Neither exists. Do not perform a clean initialization that discards accounts.

C. **No technically/privacy-verified sustainable alternative currently established.** Neon or other managed PostgreSQL is not a demonstrated primary-error redaction fix. Local encrypted PostgreSQL is a proven rehearsal/recovery environment, not an approved continuously available public production host. Do not expose the laptop publicly as a shortcut. Under unchanged zero-cost and strict privacy constraints, continued live account availability after retirement cannot yet be guaranteed. Escalate this incompatibility before the deadline; an archive preserves records but does not by itself preserve live account access. No abandonment is authorized.

## Version-pinned interim release package — approval only

- Backend and frontend source: `a1813f80d9c22e06ae89e2dd7f8b2ba6a0f02c16`. Later local commits change verification/docs only. JAR and all three frontend hashes rechecked PASS against `release-candidate-artifacts.json`. Existing tests/container/recovery evidence reused; no emails sent.
- Database: existing MySQL only. Preserve byte-for-byte V1–V7; apply V8 password/token versions, V9 invitation/proof and V10 notifications, in order. No PostgreSQL or clean-schema action in this package. Flyway validation/failed-DDL recovery must be inspected before any retry: MySQL DDL is not universally transactionally reversible.
- Production configuration: retain verified-identity MySQL TLS and separate runtime/migration identities; production CORS only intended Pages origin; strong existing JWT signing secret with version validation; `RECOVERY_EMAIL_ENABLED=false`, `RECOVERY_EMAIL_DELIVERY_VERIFIED=false`, frontend `VITE_EMAIL_ACTIONS_ENABLED=false`; no Brevo production secret needed. No credentials/CA secrets stored in package. Later email activation is a distinct approval; test budget remains exhausted.
- Write protection: inventory all writers/admin jobs first. The current V7 backend does not implement the new maintenance gate. Setting its new flag is NOT a write-freeze. The approved execution window must positively stop/isolate the old backend and all writers, verify no writer remains, then take the final consistent encrypted snapshot and isolated restore. Never rely on the new flag before the new artifact is running. If Render cannot maintain that stop through migration/deployment, STOP and obtain a verified alternative before DDL.
- GitHub: separately approve the exact release source/branch publication or reviewed merge strategy. Render tracks `codex/product-completion`, not this local development branch; do not assume a push deploys the pinned artifact. No auto-merge or unreviewed branch pointer changes. Cloudflare project `splitsmart` only; do not touch `splitsmart-offline-demo`.
- Deploy order once separately approved: stop verified writers; final backup/restore; Flyway validate/migrate/validate; pinned backend with maintenance ON/mail OFF and database readiness; verify artifact/config/TLS/auth boundary; matching frontend with email UI OFF; gated smoke checks; explicit write reopening. Capture actual deployed source/artifact metadata and preserve backups before declaring success.
- Smoke scope: health/readiness and HTTPS/CORS; existing-owner approved account access without credential disclosure; protected Settings/navigation; authorized group/balance/history views; separate disposable synthetic workflows for registration/group/expense/settlement/invite acceptance/notifications. Check amounts/record reconciliation before and after; no real-user resets, emails or replayed uncertain financial writes.
- Recovery: on migration failure keep all writers stopped, inspect actual Flyway/schema state and forward-fix with reviewed additive corrections. Do not blindly rerun failed MySQL DDL, repair history or restore over production. If a restore is needed, it requires a separately approved isolated destination and reconciled switchover. After V8/reset rollout, no unversioned V7 backend rollback and no decrease in authentication versions. After writes reopen, a stale snapshot is not an automatic rollback target.
- ₹0: use existing Free services only, no subscriptions/card/upgrades. Recheck eligibility/quotas immediately before window; unavailable Free capacity means stop, not paid fallback.

## Exact outstanding approvals / next executable action

1. Connect a physical USB. Read-only metadata inspection can identify it; owner then approves its exact path/identity and ONE ciphertext copy. No copy approval is inferred from this plan.
2. Owner may separately authorize the exact temporary MySQL release above (including final backup/isolated restore, outage/write protection, additive migrations, necessary GitHub/Render/Cloudflare actions and reopening). No production action is authorized now.
3. Before any long-term cloud migration, resolve privacy policy/protection and explicitly authorize full-data import/cutover preserving accounts. Option C remains until changed. No request to accept risk silently.

Safest immediate operation is the independent encrypted USB copy after exact-destination approval. Production NO-GO; all existing data, source, backups, GPG keyring and public offline demo remain unchanged.


## M7–M10 owner update — Google Drive destination prepared

USB troubleshooting stopped at owner direction. Created ONE empty folder named **SplitSmart Encrypted Backups** in the owner's authenticated personal Google Drive. Sharing dialog verifies only the owner has access; general access Restricted. UI shows 22.05 GB used of 5 TB, sufficient for the 3818-byte ciphertext. This verifies current capacity, not that the 5 TB entitlement itself is a free plan; no subscription, payment, trial or billing change was made. Use existing permitted capacity only; any payment requirement stops the workflow. Exact account/folder URL and owner-only receipt are in protected local `DriveBackupCopy/destination-preflight.json`, outside Git. A precise upload/download approval request is pending. No backup uploaded.

Original SHA-256 rechecked PASS on 13 November. Exact file is the already verified AES-256 GPG ciphertext; no new decryption or encryption-key operation. After approval: recheck account/folder owner-only access and source hash; upload only that one `.sql.gpg` without conversion/preview/sharing or replacing any existing object; record the Drive object ID; download that exact object into a new private local verification path; compare size and SHA-256 with the source; preserve both originals and recovery keys. No automatic upload retries on ambiguous outcomes: inspect exact folder/object first. A successful upload alone is NOT independently verified backup protection. If any unexpected permission/quota/payment state appears, stop. Google sees ciphertext plus filename/size/upload metadata; key recovery stays separately protected.

M8: smallest coordinated release remains the pinned MySQL package above, **BLOCKED pending independent-copy verification and explicit production authorization**. No unrelated feature, new email test, PostgreSQL operation or broad suite rerun. Existing checks remain valid; no deployed-code change in this continuation. Release approval must explicitly cover source publication/branch choice, planned outage and stopping ALL writers, final backup/isolated verification, V8–V10, exact Render/Pages deployments, config changes and gated reopening. It does not authorize production email activation. Do not assume setting maintenance on old V7 code freezes writes; confirm a platform-level stop before the fresh snapshot or DDL.

## M9 — Short live checklist (prepared, not executed)

Use a disposable synthetic account only after release/write reopening is approved. Preserve real accounts and record all test IDs for later separately approved cleanup.

1. HTTPS, liveness/readiness, intended CORS origin; confirm actual backend/frontend source metadata.
2. Register/login synthetic account; invalid login generic; existing owner's account access only with private owner authentication. Old token/version rejection remains enforced.
3. Dashboard totals agree with known synthetic obligations; empty/loading/error states remain usable.
4. Create synthetic group; members visible only to authorized users; copy-link invitation requires acceptance, rejects expired/revoked/reused links and unauthorized access.
5. Add equal/custom splits with exact totals; refresh history before retrying any uncertain mutation; idempotency behavior intact.
6. Verify owed/receivable/net balances, settlement suggestions and settlement recording against the synthetic fixture. Circular obligations do not delete source expenses.
7. Notifications recipient isolation/unread status; Settings read-only account information; keyboard focus/mobile navigation at narrow and desktop widths.
8. Cross-account attempts against groups/expenses/invites/notifications denied; no secret-bearing response/error output.
9. Recovery WITHOUT sending: email flags/UI remain disabled, honest temporary-unavailability state, no enumeration, no Brevo attempt. Reuse completed two-email proof for enabled-mode behavior; do not trigger live reset or change real-user passwords. Production email activation requires separate approval.
10. Compare pre/post production financial reconciliation privately; any unexplained change means stop/maintenance and forward investigation. Retain backup and preserve authentication revocation fences.

## M10 — Sustainable database check

Official current [Aiven PostgreSQL Free](https://aiven.io/docs/products/postgresql/concepts/pg-free-tier) supports no-card persistent relational storage (1 GB disk, 20 connections, no SLA), but does not establish the required primary-error payload protection. [Neon plans](https://neon.com/docs/introduction/plans) show a $0 tier, 1 GB/project and 100 CU-hours/project; plan availability does not demonstrate guaranteed sensitive-primary-error redaction. No real records were sent to either provider.

[Render Free documentation](https://render.com/docs/free) confirms local SQLite/files are lost on restart/spindown/redeploy, Free services cannot attach persistent disks, and Free Render PostgreSQL expires after 30 days. Therefore neither local Render SQLite nor expiring Render PostgreSQL is an acceptable long-term preservation substitute. Local encrypted recovery remains a safety copy, not a verified public hosting replacement. No sustainable end-to-end ₹0 architecture meeting the unchanged privacy policy and continued-account-access requirement is verified. Keep Option C, preserve MySQL/data and escalate before its December 21 deletion deadline; do not disguise archival as preserved live access.


## M7 complete — approved independent Drive copy verified

13 November 2026: owner explicitly approved the exact private destination and ciphertext upload/download scope. ONE successful ciphertext upload and ONE verification download completed. An initial malformed Windows path was rejected as File unreadable before upload; the folder remained empty, and the corrected selection produced the sole uploaded object. No ambiguous upload was retried.

Original, downloaded object and protected local verification copy all match SHA-256 `a70bf90feaaf251e51b07b047e0e45e8abee84fb62fdeb9d2d8b33ca11890f1a`, size 3818 bytes. File sharing confirms owner-only and Restricted. Original backup preserved; no plaintext, credentials, passphrases or GPG keys uploaded. Private receipt contains exact Drive object ID and download paths outside Git. No billing changes, production operation, email or deployment.

Independent backup blocker is resolved for the November 12 snapshot only. Later source writes remain outside its coverage. Temporary MySQL release still requires explicit outage/write-stop, final snapshot/restore, migration and coordinated publication/deployment authorization. PostgreSQL privacy Option C remains unchanged.
