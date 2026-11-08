# SplitSmart — owner release decision

**Production release operations in this document are not approved or executed. Production is NO-GO.**
The owner approved isolated Linux JDK21 preparation, synthetic verification and one no-cost provider inquiry; these do not authorize production operations.
Earlier reviewed adapter candidate: `4373ac57926caa178d11f77132b97cfcbfca6d75`. Linux verification tooling and evidence were added in `c80b07abe2efcff78aa9072df01c1bd82402d31e`, which is not a documentation-only descendant. A future release must review and approve its exact final candidate SHA.

The local data-integrity method is verified; real cloud import, Linux-container
startup and the final frozen snapshot are not. Email remains disabled.

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

## Current policy — stronger verified protection required

The latest owner instruction selects B. A is an unapproved policy change, not an active release path. C describes the current operational hold.

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

**Recommendation: B, with C as the immediate operating state.**
The present strict no-sensitive-log requirement and missing container verification
make release inappropriate today. One bounded provider inquiry is reasonable,
but is not a promise of a free solution. If B cannot be met, explicitly choosing
A is a policy change the owner may consider; the engineer cannot choose it or
label the risk eliminated. Do not postpone indefinitely without a preservation
and availability decision. Avoid an extra real-data rehearsal copy; a final
approved frozen import would still need every check below.

## Safe before release approval

Local code review, fake-data tests, artifact preparation and record-free metadata
checks may continue. Completed this gate: strengthened schema contract matches
local reference and the pinned cloud SQL target;15 adapter +3 schema +2 reporting
unit tests PASS; one changed-code synthetic transfer PASS. Prior42 Java21,
20 frontend and4 browser scenarios remain valid and were not repeated.

Linux-native execution now PASS on isolated Ubuntu Temurin21.0.13+9: verified synthetic PostgreSQL TLS, rejected invalid CA, current V11 Flyway, readiness/liveness and maintenance gate. Container execution remains BLOCKED by Docker timeout; production UID/image/layout are unverified. No destructive repair authorized. Six focused Linux Java unit tests and four new destination safety tests PASS.

The approved support inquiry was NOT submitted: official form requires Basic tier, no confirmed Free entitlement. Draft: docs/aiven-primary-error-inquiry.md. No support reference or privacy guarantee exists.
Aiven authentication is COMPLETE; both services were verified RUNNING/Free and the refreshed record-free TLS/schema preflight PASS. No further login is needed. Never send credentials in chat.

## Explicit authorization required for the coordinated run

1. **Policy:** satisfy the selected B protection gate before upload. A later change to A would require explicit acknowledgement of potential sensitive
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
