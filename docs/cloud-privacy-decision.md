# Cloud privacy decision — policy B; protection unresolved

8 November 2026. **NO-GO for real-data import today.** Investigation is complete;
verified protection under selected policy B, final-loader review and scoped release approvals are
still required. Residual-risk acceptance is not the selected policy. No production record was uploaded, no cloud setting changed.

## What is established

The local V7→V11 rehearsal reconciled nine source tables, every original column,
financial ledgers and sequence floors. Its 23 validator tests and one Flyway test
passed. Existing cloud V11 schema/restricted roles and 26 synthetic application
tests already passed; these were not rerun. New SELECT-only cloud comparisons
passed all 13 Unicode cases and 9,025 printable single-character ASCII pairs,
including equality/order and duplicate grouping under the actual ICU collation.
No cloud INSERT, schema change or deliberate new error probe was needed.

Local release hardening added a default-OFF full API/scheduler maintenance gate
and PostgreSQL PEM startup support without changing the MySQL default. Fifteen
focused backend tests, seven synthetic POSIX startup tests and two collation
reporting failure-path unit tests PASS. The startup tests caught and fixed a
POSIX pipeline issue: required inputs are validated before the pipeline and
zero decoded bytes are rejected before Java. No new Docker image or actual
Render startup was executed; those remain release-preflight evidence gaps.

Actual application collation on both: ICU (`provider=i`), `und-u-ks-level1`,
nondeterministic. Cloud 16.15 version/actual version **153.128/153.128**; local
16.11 **153.14/153.14**. No stale-version mismatch within either environment.
Email and settlement request-ID columns use this collation and their unique
constraints remain present. Case, accents, NFC/NFD, sharp-S, Turkish variants,
Greek sigma, width, ligatures, Hangul, emoji and trailing-space cases matched.
Local ICU cannot be declared identical to the managed OS/library from this
finite suite. Tests of GROUP BY/DISTINCT establish comparison behavior, not a
new physical unique-index insertion test; previous target integration evidence
remains the evidence for actual constraint enforcement.

The existing recovery snapshot is small (under 1 MiB of logical field values,
fewer than 1,000 source rows), with printable ASCII in the ICU-sensitive keys.
Those category flags were computed LOCAL-ONLY, without displaying values. This
reduces known collation uncertainty for this snapshot; it neither desensitizes
password hashes/financial records nor proves a later snapshot has the same
domain. Names/free text remain UTF-8 data and must retain exact column hashes.
Never upload real keys in a supposedly harmless SELECT to test uniqueness.

## Unresolved privacy exposure

Observed earlier: a parameterized integer cast failed with SQLSTATE 22P02 and
its SYNTHETIC input appeared in the provider's PRIMARY error message. Current
settings remain TERSE, log_statement=none, log_min_duration_statement=-1,
log_parameter_max_length_on_error=0, log_min_error_statement=error and
log_min_messages=warning. None redacts a primary error message. Existing managed
roles lack the relevant session SET permissions. No verified supported general
redaction or per-import error-payload suppression is established.

Unexpected conversion/date/encoding errors, function/trigger messages and some
other data-dependent failures can echo input in primary messages. DETAIL-bearing
constraint errors are reduced by TERSE, but not all error classes use DETAIL.
Do not claim every unique/FK error exposes a whole row, or that they never can.
Catalog drift, future source fields, ICU collisions, extension/runtime failures
and future application requests are not comprehensively eliminated by validation.
SQLSTATE-only client handling and suppressed libpq notices reduce client leakage;
Hibernate/framework error logging may separately retain primary database messages
in Render logs. The risk is ongoing production behavior, not just the import.

Aiven documents service-log retention of **four days** and the separate
`service:logs:read` permission. This is distinct from 30-day organization EVENT
logs and 24-hour Aiven Runtime application logs. Limit log access to required
operators; SQL runtime permissions do not themselves grant management log access.
Provider/internal copy retention, guaranteed per-entry deletion and universal
redaction are not established. `service_log=false` describes availability in the
API/Console; it is not evidence that all provider copies disappear. No logging,
auditing, retention or access configuration was changed in this investigation.

Sensitive scope includes names/emails, BCrypt password hashes (valuable for
offline guessing if disclosed), financial values/notes, relationships and
invitation metadata. V7 has no new raw reset tokens; future tokens stay hashed
under V8+, but that does not make unrelated data safe to log. A small dataset
limits volume, not impact. No real-data leakage is known; the verified leak used
fake values only. No numeric probability or zero-risk claim is justified.

## Recommendation and choices requiring the owner

Skip a separate managed real-data rehearsal. Local all-column reconciliation,
cloud synthetic application tests and the new collation comparisons provide
enough evidence to PLAN one final transactional import, subject to these gates.
An extra managed copy adds grants, backup/retention exposure and cleanup duties
without removing the need for a fresh frozen final snapshot.

Choose one policy before any real-value upload:

1. **Strict no-sensitive-error-log policy:** keep NO-GO until the provider supplies
   a supported, verifiable control that meets it, or a separately reviewed
   alternative environment is approved. Do not merely turn off auditing or switch
   to another Free provider and assume the PostgreSQL mechanism disappears.
2. **Explicit residual-risk acceptance:** accept that unforeseen database or
   application failures may persist sensitive values in provider/operator logs;
   four-day documented service visibility does not prove all-copy deletion. Keep
   validation/parameterization, restricted log access/roles, TLS and limited
   networking, and approve ONLY the final frozen import rather than a duplicate
   rehearsal. This is an owner/privacy decision, not an engineering guarantee.

Also accept shared Render regional egress trust, Free cold starts/limits, no HA
or guaranteed zero-cost provider fork restore, brief planned downtime, remaining
ICU differences and mandatory forward recovery after target writes. The cloud
runtime needs its normal error-handling/logging reviewed under the same policy.
Current no-billing/no-card/₹0 constraints remain binding; quota failure means
stop/maintenance, never an automatic paid fallback.

Next operation after the policy choice: review a cloud-specific import controller
and a release candidate with the maintenance gate; only then approve a bounded
one-window operation as specified in cloud-final-migration-window.md. Existing
local-only controllers deliberately refuse cloud endpoints and must not simply
have their isolation/logging guards removed. No import/cutover approval follows
from reading or accepting this document alone.

Sources:
- https://www.postgresql.org/docs/16/runtime-config-logging.html
- https://www.postgresql.org/docs/16/collation.html
- https://aiven.io/docs/platform/howto/list-monitoring
- https://aiven.io/docs/platform/concepts/permissions
- https://aiven.io/docs/products/postgresql/reference/advanced-params
- https://render.com/docs/free

## Latest owner package

AUTONOMOUS-RELEASE-APPROVAL.md is now the concise A/B/C decision document. Current
recommendation is B (require verified stronger protection), using C as the present
hold while Linux-container execution and management reauthentication are blocked.
A remains an explicit owner policy change; it cannot meet a zero-sensitive-log
guarantee. No choice or support submission has been authorized. Additional schema
checks improve integrity/prevalidation but do not redact provider primary errors.

## Current owner policy and final investigation — 8 November 2026

Owner requires B: stronger verified protection before any real-data cloud upload. Earlier choices are historical options, not inferred acceptance. Integrity PASS does not satisfy this privacy gate.

Official PostgreSQL16 logging documentation confirms TERSE omits DETAIL/HINT/QUERY/CONTEXT, not the primary message. Aiven advanced parameters expose verbosity, duration logging and service_log; the latter describes storage for API/Console availability, not guaranteed removal from all internal copies. Four-day service retention and log-reader restrictions reduce exposure but do not redact payloads. No new control justifies repeating the synthetic 22P02 experiment. No supported enforceable redaction was established; BLOCKED, not a proof that no provider could ever offer one.

Aiven Free documentation explicitly excludes support services and integrations. A clarification through an available official contact may be requested after approval, with no promise of Free support or paid fallback. Ask specifically about primary-message redaction before persistence, provider copies, scoped controls, and preservation of security auditing; never send real examples/records.

Neon documents a $0 tier and TLS verify-full, but reviewed plan/security pages do not establish primary-error redaction. Switching would add provisioning/networking/migration work without proving this requirement. Existing encrypted local PostgreSQL remains suitable for rehearsals, not an approved public production host. No new service or provider change was made. Preserve MySQL and backups; retirement deadline remains owner-verified21 December2026. If protection cannot be established, plan availability/preservation before retirement rather than silently accepting risk.

Linux/container execution is independently blocked by absent Linux Java and unavailable Docker. Metadata-only cloud TLS/schema preflight now PASS after restored authentication; no records uploaded. Next approval may cover one provider inquiry and a specific isolated Linux runtime preparation, not production migration.

Reviewed sources:
- https://www.postgresql.org/docs/16/runtime-config-logging.html
- https://www.postgresql.org/docs/16/logfile-maintenance.html
- https://aiven.io/docs/products/postgresql/reference/advanced-params
- https://aiven.io/docs/platform/howto/list-monitoring
- https://aiven.io/docs/products/postgresql/concepts/pg-free-tier
- https://neon.com/docs/security/security-overview
- https://neon.com/docs/introduction/plans

## Linux recovered; privacy gate unchanged

8 November2026: actual Linux-native Java21/Spring Boot synthetic-only TLS/startup/health checks PASS. Container remains unverified. This strengthens runtime evidence, not primary-error redaction. The one approved inquiry could not be submitted through the authenticated form without selecting a Basic support tier; no Free entitlement established, no payment/trial use. Draft and exact blocker: aiven-primary-error-inquiry.md. No ticket/reference, no real data sent, no new error probe. B remains the selected policy; no risk acceptance was inferred.

## Resumed evidence audit — 8 November 2026

Recovered existing completed commit c80b07a rather than reinstalling Java or replaying synthetic verification. Current java/javac 21.0.13 and retained runtime receipt were read; six retained Linux unit-test results show zero failures/errors/skips, and no Java process remained. No database access or Docker troubleshooting was repeated.

Official Free-plan and support terms were rechecked: Free explicitly excludes support services; Basic is included in paid plans. The support page also contains broader ticket-access wording, which does not establish Free entitlement for the required Basic-only form. The prior submission blocker therefore remains; no ticket or additional message was sent. Stronger supported primary-error protection is still UNRESOLVED, not inferred from runtime success. The existing inquiry authorization persists for a confirmed no-cost official channel; no further routine approval is required for that same scope.

## Final feasibility recommendation (latest8 November2026)

Recommend C as the immediate operational hold, retain B as the strict protection requirement. No reviewed zero-cost option demonstrates enforceable primary-message redaction; A would require explicit informed policy change, never implied here. Neon and Supabase comparisons and current official sources are in final-infrastructure-privacy-assessment.md. No new failing probe, support submission, cloud configuration or real-data upload. No claim that a new provider automatically eliminates logging. Existing MySQL remains authoritative/cloud-hosted; preservation and availability need an owner decision before21 December2026. Container is independently blocked by absent rootless identity/mappings/helpers, despite a successful harmless namespace probe.

## Latest explicit owner direction — Option C

Owner now selects C: no real production financial-data upload to PostgreSQL cloud. Stronger primary-error protection remains desired; no acceptance of A and no new logging/support experiment. Rootless actual OCI image build and four synthetic application scenarios PASS, which resolves local container capability, not provider privacy or deployed Render evidence. A synthetic-only portfolio plan requires no-real-input/write safeguards and separate release approval; current app is not fake-only merely because seed rows are fake. See rootless-container-verification.md and synthetic-portfolio-release-plan.md. Production remains NO-GO, email disabled.
