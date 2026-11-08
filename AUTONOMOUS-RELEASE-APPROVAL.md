# Coordinated production-run approval request — PREPARED, NOT AUTHORIZED

This is the exact proposed scope for a later owner decision. It is not executable
today: privacy is unresolved, real execution is disabled and the Linux container
has not been verified. Do not send/execute provider mutations based on this file.
No payment, billing, paid fallback or service creation is included.

Verified code candidate: `b7fb3560af897865e09206350df84e6aaa603cfc` on
`codex/password-recovery-backend`. Any code change requires affected verification
and a newly identified exact SHA. A documentation-only descendant may be included
only after its diff is reviewed as such; never silently substitute another build.

## First: owner privacy policy decision

Choose either strict protection (keep upload BLOCKED until supported verifiable
mitigation exists) or explicit informed acceptance of the residual risk described
in docs/cloud-privacy-decision.md. Acceptance must specifically acknowledge:

- Unexpected database/application errors may persist user/financial values or
  password hashes in provider/operator logs. Four-day service visibility does
  not prove all-provider-copy deletion or universal redaction.
- Shared regional Render egress permits network reachability by other tenants;
  authentication/CA+hostname TLS/restricted roles still protect access.
- ICU versions differ despite synthetic agreement. Free limits/cold starts,
  downtime and forward-only recovery after target writes remain constraints.

The agent cannot make this choice. A privacy choice alone does NOT authorize a
real upload or any item below. Recommend avoiding a duplicate real-data cloud
rehearsal; use one approved final frozen snapshot when all gates pass.

## Conditional coordinated scope to approve only after prerequisites pass

A. Private Git push/integration or tracked-branch selection for the reviewed exact
candidate. Keep repository private and host autoDeploy OFF. No public release.

B. Target-only allowlist union: reverified administrative /32 plus current actual
Render ranges (last verified74.220.52.0/24 and74.220.60.0/24). Recheck before use;
preserve independent Aiven HTTPS recovery. No allow-all, billing or new service.

C. One scheduled maintenance window: gated PostgreSQL deployment with both email
flags false, verified TLS, separate runtime role and unchanged JWT signing secret.
Drain/stop old source app instances and ALL other writers before any final
snapshot. A healthy replacement is not proof that old writes have stopped.

D. ONE new consistent encrypted MySQL export plus ONE new isolated restore under
the verified freeze, with current object inventory/history/financial checks. No
plaintext SQL/logs, original overwrite or automatic failed-export retry.

E. ONE approval-bound transactional real-data import into the exact empty target
application DB, after the adapter is reviewed and synthetic-tested. Verify every
source column/hash/relationship/amount/sequence and legacy history before commit;
verify again through a new connection. Preserve all constraints. Unknown commit
status means stop/reconcile, not replay/truncate/reset.

F. One encrypted logical PostgreSQL recovery backup including V11 schema, data,
both histories and sequence state. Keep it local/private/GPG protected; no paid
provider fork, external upload, GPG rotation or recovery deletion.

G. Approved Render/Cloudflare artifacts/configuration under the existing Free
services, with CA verification, exact API/CORS and email-disabled flags. No
credential reset, provider activation or paid migration. No secret in Pages/Git.

H. Approved disposable smoke fixtures and owner voluntary login only. Do not
impersonate users, request their credentials/tokens or alter real financial data.

## Separate final GO checkpoint

After actual results A–H are reviewed, obtain explicit permission to disable the
maintenance gate/open target writes. This file does not pre-authorize GO. Treat
the first ungated target/background mutation as the authority-change boundary.
After it, no stale-source/V7 rollback: gate traffic, preserve evidence and use
compatible forward recovery with monotonic token-version/data reconciliation.

If any prerequisite fails, keep the window stopped/gated and request a concrete
new decision. Never spend money or weaken privacy/security controls to proceed.
Keep old MySQL, encrypted backups and keys until separately approved retirement.
