# SplitSmart synthetic portfolio — owner release approval package

**Local implementation PASS. Public deployment is NOT authorized. Real-data production migration remains NO-GO/Option C.**

Reviewed code candidate: `a24fca1ef046e390aca6665382c7f02de87b6387` (includes fixture commit3e12d1a and implementationfb0039d). Later documentation-only handoff commit does not change the tested application. Tested image: `sha256:454abbc3a94320676a7260b78dfbb50de3015f67dfd91c6d5f614f988ddc762f`. Asset hashes: docs/synthetic-demo-artifact-manifest.json.

## What is being proposed

A clearly labeled offline React guided simulation, served as static assets. It is not a live multi-user backend. Registration/sign-in select fixed fictional personas; all amounts and actions are presets. No real visitor information or credentials can be entered through the UI. There is no database, backend API, mail delivery, reusable password or server seed/reset endpoint. Reset clears this tab's React memory only. Existing production accounts, financial records, JWT revocation, APIs and databases remain unchanged.

Local gates:3 Java21 fixture/financial-service contracts;11 React/model tests;4 real HTTP/policy tests;4 desktop/mobile browser cases against local static server and again against actual rootless OCI image; read-only filesystem write rejection; startup marker/mixed-DB negatives; scoped lint, normal/demo production builds, artifact separation and npm production audit0 known vulnerabilities. Previous Java/PostgreSQL integration evidence is retained, not claimed as a newly run demo DB test: no demo database/profile exists or is required.

## Choose an isolated hosting target — approval required

Preferred: a separately named Cloudflare Pages demo/preview origin, never replace `splitsmart-92b.pages.dev` or connect its production API. Repository root setting `frontend`; build `SPLITSMART_DEMO_ONLY=synthetic-v1 npm run build:demo`; output `dist-demo` (NOT `dist`). No API URL, DB/JWT/Brevo credentials, server functions, analytics/beacons or database bindings. `_headers` carries CSP/connect-none/form-none, no-referrer/nosniff/no-store. Verify actual platform headers and unknown-route/method behavior before promoting the URL; local server success does not certify provider behavior.

Optional Render equivalent: a NEW isolated demo service only after quota approval, root `frontend`, Dockerfile `Dockerfile.demo`, HTTP port from Render PORT, health path `/`, env `SPLITSMART_DEMO_ONLY=synthetic-v1`, `DEMO_BIND_ADDRESS=0.0.0.0`. No database/API/auth/email secrets. Known database/production routing variables intentionally prevent startup. Node24 image runs10001:10001; rootless local verification is not actual Render verification. Do not overwrite existing backend or reuse its environment. Shared Free workspace hours/build/bandwidth limits can impact existing availability; no paid fallback or keepalive bot.

## Exact approval needed next

Approve only the named isolated host/project/preview URL and exact reviewed source SHA/artifact, any required private push/integration, and that demo deployment operation. No push/deployment has occurred. Review routine hosting metadata (IP/user-agent/access logs) and host privacy settings: eliminating application inputs/SQL does not eliminate platform request logs. No third-party analytics or personal query strings. This is not acceptance of the separate financial-data SQL logging risk.

If the exact public-host configuration cannot meet these boundaries at ₹0/no card, stop. Do not change production, reuse real records or enable billing to make the demonstration available.

## Live smoke and release checkpoint

1. Confirm separate origin and literal synthetic/no-API disclaimer; check asset manifest/hash against reviewed build.
2. Confirm CSP connect/form-none and no external fonts, telemetry, requests, cookies or app storage writes during the full desktop/mobile walkthrough.
3. Verify registration/persona simulation, accepted/revoked/expired invites, equal/unequal expenses, fixed repayment and duplicate block, notification read states, zero circular transfers with all4 obligations retained, unavailable recovery, logout/reset/refresh.
4. Verify arbitrary API/admin methods do not perform mutations, no source/config/manifest credentials exposed, personal query strings not reflected. Hosting logs still require owner review.
5. Confirm actual Free plan/usage; no real backend/DB connection. Public promotion requires owner review of actual deployed evidence.

## Reset, recovery and rollback

No DB migration, seed job, credential rotation or restore is part of this deployment. Refresh/new tab reinitializes fictional fixtures; Reset asks confirmation and affects only the current simulation. Keep source, lockfile, built artifact manifest and tested image digest. On failure disable only the demo/preview or restore its last reviewed static artifact; verify CSP/isolation again. Never change production's MySQL/backend/Pages configuration or invoke V7 rollback. Preserve encrypted backups/GPG keys/recovery databases.

The full application's existing migration/release plan remains separately gated by privacy policy, fresh frozen snapshot, approved real adapter, reconciliation and coordinated deployment. A demo approval grants none of those actions.
