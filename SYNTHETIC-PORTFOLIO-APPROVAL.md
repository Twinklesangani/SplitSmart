# SplitSmart synthetic portfolio — owner release approval package

**Public demo deployment PASS (9 November 2026).** The owner-approved Cloudflare Pages Free Direct Upload was completed for the new project `splitsmart-offline-demo` at https://splitsmart-offline-demo.pages.dev. Real-data production migration remains NO-GO/Option C.

Approved source SHA: `d58b0c7925266ef7fe7804dd7cc89c07fc66adb4`. Uploaded artifact: `frontend/dist-demo`; `demo-manifest.json` SHA-256 `f750c131049103dd5fdbe45def7374689763a87dbccb4ee627a3a231a4050125`. Cloudflare reported all 6 files uploaded and deployment success. Live root and `/demo` entry route loaded with synthetic/offline labels. The existing production Pages project was not changed. See the final publication record in `docs/synthetic-demo-verification.md`.

Reviewed code candidate: `a24fca1ef046e390aca6665382c7f02de87b6387` (includes fixture commit3e12d1a and implementationfb0039d). Later documentation-only handoff commit does not change the tested application. Tested image: `sha256:454abbc3a94320676a7260b78dfbb50de3015f67dfd91c6d5f614f988ddc762f`. Asset hashes: docs/synthetic-demo-artifact-manifest.json.

## Published experience

A clearly labeled offline React guided simulation, served as static assets. It is not a live multi-user backend. Registration/sign-in select fixed fictional personas; all amounts and actions are presets. No real visitor information or credentials can be entered through the UI. There is no database, backend API, mail delivery, reusable password or server seed/reset endpoint. Reset clears this tab's React memory only. Existing production accounts, financial records, JWT revocation, APIs and databases remain unchanged.

Local gates:3 Java21 fixture/financial-service contracts;11 React/model tests;4 real HTTP/policy tests;4 desktop/mobile browser cases against local static server and again against actual rootless OCI image; read-only filesystem write rejection; startup marker/mixed-DB negatives; scoped lint, normal/demo production builds, artifact separation and npm production audit0 known vulnerabilities. Previous Java/PostgreSQL integration evidence is retained, not claimed as a newly run demo DB test: no demo database/profile exists or is required.

## Hosting scope used

One direct upload was approved and completed to the distinct Cloudflare Pages Free project `splitsmart-offline-demo` at `https://splitsmart-offline-demo.pages.dev`. The existing `splitsmart-92b.pages.dev` project was not selected. Source SHA and artifact hash are recorded above. No API URL, DB/JWT/Brevo credentials, functions, analytics, bindings, custom domain, or repository integration were configured. The artifact's `_headers` declares CSP/connect-none/form-none plus no-referrer/nosniff/no-store. The current tooling could not independently inspect the live response header values; see `docs/synthetic-demo-verification.md`.

Render was not used. No Render service or environment was changed.

## Remaining boundaries

The one approved publication action is complete. Any redeployment, project disablement, or settings change needs separate owner direction. Review routine hosting metadata (IP/user-agent/access logs) and host privacy settings: eliminating application inputs/SQL does not eliminate platform request logs. No third-party analytics or personal query strings. This is not acceptance of the separate financial-data SQL logging risk.

Do not change production, reuse real records, or enable billing to make the demonstration available.

## Live smoke completed and remaining independent checks

1. Verified the distinct public origin, fictional/no-API disclaimer, approved manifest checksum, and Cloudflare success state.
2. Live root and `/demo` route loaded over HTTPS. Live DOM showed same-origin JS/CSS, no forms/inputs/external links. Persona selection and synthetic group creation worked. Prior desktop/mobile/offline scenarios remain covered by the recorded local/container browser tests; no redundant live full suite was run.
3. The uploaded `_headers` file declares CSP connect/form restrictions and other headers; live response headers could not be independently read with current tooling. This remains an open verification item, not a claimed PASS.
4. Hosting access logs may still include routine request metadata; owner review of Cloudflare privacy/log handling remains separate. No real backend/DB connection or data upload occurred.

## Reset, recovery and rollback

No DB migration, seed job, credential rotation or restore is part of this deployment. Refresh/new tab reinitializes fictional fixtures; Reset asks confirmation and affects only the current simulation. Keep source, lockfile, built artifact manifest and tested image digest. On failure disable only the demo/preview or restore its last reviewed static artifact; verify CSP/isolation again. Never change production's MySQL/backend/Pages configuration or invoke V7 rollback. Preserve encrypted backups/GPG keys/recovery databases.

The full application's existing migration/release plan remains separately gated by privacy policy, fresh frozen snapshot, approved real adapter, reconciliation and coordinated deployment. A demo approval grants none of those actions.
