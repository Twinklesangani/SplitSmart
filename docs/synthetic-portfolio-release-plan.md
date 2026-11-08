# Synthetic-only portfolio — local implementation verified, release plan only

8 November2026. Owner selected privacy Option C. No deployment, push, service creation or real-data upload is authorized. Existing production remains authoritative and unchanged.

## Latest implementation status

Local-only implementation is complete and verified at a24fca1ef046e390aca6665382c7f02de87b6387. Dedicated offline React/static-server entry, no real inputs/APIs/databases/credentials, tab-memory-only reset.3 Java contracts,11 React/model,4 HTTP tests and4 browser cases locally/actual OCI PASS; source and actual container artifact hashes match. See SYNTHETIC-PORTFOLIO-APPROVAL.md and docs/synthetic-demo-verification.md. No publication authorized; prior planning below is superseded where it describes the demo as unimplemented.

## Feasibility

Actual production Dockerfile now builds and runs rootlessly with synthetic PostgreSQL, verified TLS/V11/health/nonroot and email disabled. Existing React/product tests and local data-integrity evidence remain valid. This is enough to plan a separate demonstration, not enough to publish the current full app safely as fake-only.

The current app accepts registration and free-text financial inputs. Fake seed data or a disclaimer does not prevent real names/emails/financial details being submitted and reaching provider logs. Maintenance mode blocks all APIs, so it is not an interactive read-only demo switch. A safe synthetic portfolio needs an explicitly reviewed demo mode or static fixture frontend that does not collect/send visitor records. The separate offline entry is now implemented and verified; production app behavior is unchanged.

## Preferred proposed design

A separately named, clearly labeled synthetic demonstration with immutable fake fixtures, deterministic amounts and no collection of real identity/contact/payment/financial details. Prefer read-only fixture browsing first. Disable registration, password recovery, email sending and all server mutations, including indirect read-triggered writes; enforce restrictions server-side if a backend is reachable. Do not rely on hidden UI controls. Never reuse recovery databases, production credentials, JWT_SECRET, auth tokens or data. Test adversarial direct endpoint requests before publishing. If choosing a static fixture-only frontend, make it explicit that actions are simulated; it must not silently connect to live APIs or replace the real dashboard.

Use a dedicated synthetic database/role only after an approved resource plan; the existing integration DB is a test fixture with cleanup semantics and must not casually become public hosting storage. Do not populate or repurpose the reserved application target without explicit approval. Separate URL/branch/configuration from live SplitSmart. No public indexing/access to test reports, credentials or administrative endpoints.

## Zero-cost and availability gate

Reverify actual Free quotas before any proposal is executed. Render documents750 Free instance hours per workspace/month, shared by its running Free web services; a second backend can exhaust the shared allowance and affect existing availability. Idle services spin down after15minutes, and filesystems are ephemeral. No paid fallback. A static Cloudflare Pages preview may avoid a second backend but still requires explicit push/deployment approval and complete offline fixture isolation. Do not promise a second always-on Free service. No new cloud service/domain is created by this plan.

## Approvals in order

1. Local-only demo design/implementation was approved and completed. No further permission for that same work is needed; this does not approve cloud writes/deployment.
2. After local verification, review exact SHA, resource/quota/network plan, TLS/restricted roles and unique demo URL. Obtain separate explicit push/integration, synthetic schema/seed and deployment approvals as applicable.
3. Only after actual deployed smoke/security results should owner approve making that demo accessible/promoted. Keep email disabled and verify no real visitor data is collected. Existing production and backups remain.

A portfolio-demo approval never accepts PostgreSQL real-data logging exposure or authorizes final MySQL migration. Current Option C/no-real-upload gate remains. Real migration separately requires policy resolution, approval-bound loader, write-freeze, fresh consistent encrypted snapshot/restore, reconciliation and coordinated deployment with no stale-V7 rollback after target mutations.

Free hosting source reviewed: https://render.com/docs/free
