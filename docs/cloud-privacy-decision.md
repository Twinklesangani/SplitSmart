# Cloud privacy decision — owner review pending

8 November 2026. **NO-GO for real-data import today.** Investigation is complete;
acceptance of residual risk, final-loader review and scoped release approvals are
still required. No production record was uploaded, no cloud setting changed.

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
