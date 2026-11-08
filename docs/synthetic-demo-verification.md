# Synthetic demo local verification — 8 November2026

## Architecture and implemented experience

Separate frontend/demo.html + React entry; normal main/App/AuthProvider not mounted. Existing UI components, styling tokens and precise currency formatter reused. Finite frozen fictional dataset uses only example.invalid identities and exact decimal strings. Guided group creation, explicit recipient acceptance, expired/revoked/duplicate states, equal/unequal splits, Java-verified settlement snapshots, preserved circular obligations, event/persona-scoped notifications, unavailable recovery and logout/reset. There are no real account/password forms or configurable financial inputs. Clearly labeled simulation at all times.

No Java application code, production API contract, auth/hash/token policy, Flyway SQL or existing database changed. Only a database-free Java contract test was added. No local or cloud database created/read for demo data. No production backup, account or transaction used. Seed/reset is in-memory initialization, not SQL or remote administration; selected persona is a demonstration convenience, not a real authentication/security credential.

## Actual results

- Java21 SyntheticPortfolioContractTest3 PASS: five snapshots against existing settlement service; exact original share/repayment reconciliation; four preserved circular obligations/all emails fictional. Initial expected suggestion order corrected to match service, not change service. Mockito's existing dynamic-agent warnings are not test failures.
- React/model11 PASS, including arbitrary-payload rejection, persona/consent/expiry/revocation/duplicates, money/message UTF8, deterministic reset, immutable fixtures, scoped read state and no API/storage effects.
- Static server/policy4 PASS using real loopback HTTP: default/mixed-config denial, artifact/path integrity, API/admin write rejection, CSP, no reflection/cookie/private-file exposure. Sandbox loopback/cache access initially needed normal tool escalation; no security setting changed.
- Demo build and normal production build PASS; default artifact still uses previous public API configuration/email false and contains no simulation. Demo artifact excludes production auth/token/API code and third-party fonts; environment files ignored. Explicitly disabled normal public-directory copying; an actual temporary fictional public marker was excluded from output and removed afterward.
-4 Playwright cases (full workflow + negative boundary at1280x900 and375x812) PASS locally and against actual OCI-served site. Existing Edge channel reused after missing Playwright browser revision was detected; no browser update/install. Screenshots reviewed, desktop card padding/UTF8 feedback/selected-persona styling improved and affected checks repeated. No external or API requests in full walkthrough, no page errors/horizontal overflow; skip-link keyboard check PASS. Other-origin test storage sentinels retained, and reload resets demo.
- New actual frontend/Dockerfile.demo build PASS under existing rootless Podman. Node24, UID10001, read-only/cap-drop/no-new-privileges, loopback-only port18087, default/mixed-configuration startup negatives, no database/email use and four browser cases PASS. Actual write to demo-owned index.html rejected with EROFS. Owned test containers stopped, no rootless application containers running; images/stopped fixtures retained, no prune/reset.
- Scoped lint clean. Official npm production audit reports0 known vulnerabilities; npm ci in image also reported0. No dependency additions/upgrades. This is not a guarantee of no vulnerabilities.

## Evidence and reproducibility

See frontend/demo/build.mjs, server.test.mjs, playwright.demo.config.js and scripts/verify_offline_demo_container.py. Browser screenshots/reports are ignored artifacts under frontend/demo-test-results, containing fictional data only. Private local receipts under LOCALAPPDATA/SplitSmart/SyntheticPortfolioDemo* include actual image ID, checks and cleanup status; never blindly replay completed attempt or overwrite existing fixtures. Final manifest fingerprints and approved code/image identity are recorded in the handoff after final verification.

Earlier backend/PostgreSQL/OCI verification remains valid and was not rerun wholesale. Actual Render/Cloudflare configuration/header/availability behavior remains unverified because no deployment is authorized. This demo removes the need for a financial-data database; it does not fix provider primary-error logging for real-data migration. Option C/email disabled/production NO-GO remain.

## Known limits

Registration/login/invitations/payments are explicitly simulated, not real operations. Data/notifications are deterministic and do not persist. No email/link/payment is delivered. Visitor-controlled direct HTTP traffic can contain headers/IPs or arbitrary URLs at a hosting platform; the app provides no personal-data entry and does not log/reflect request values, but provider web logs require owner review before publication. CSP/headers on actual Pages/Render must be checked after separately approved deployment. No paid fallback or new service created.

## Final artifact/review gate

Code candidate a24fca1ef046e390aca6665382c7f02de87b6387; tested image sha256:454abbc3a94320676a7260b78dfbb50de3015f67dfd91c6d5f614f988ddc762f. Final public-directory guard rebuilt to the exact same runtime image ID; no redundant browser replay was needed. Public local manifest equals the manifest copied in memory from the executed stopped container. Source-only extra public marker was excluded and its owned temporary file/directory removed. Known-secret scan across committed demo scope PASS; final documentation scan follows before local commit. No secrets/real records or generated test screenshots are committed.

## Approved Cloudflare Pages deployment — 9 November 2026

One public deployment was explicitly approved for Cloudflare Pages Free Direct Upload, new project `splitsmart-offline-demo`, expected URL https://splitsmart-offline-demo.pages.dev, source SHA `d58b0c7925266ef7fe7804dd7cc89c07fc66adb4`, artifact `frontend/dist-demo`, manifest SHA-256 `f750c131049103dd5fdbe45def7374689763a87dbccb4ee627a3a231a4050125`. Cloudflare confirmed successful deployment at that exact URL. The upload tree showed all six files: `_headers`, `demo-manifest.json`, `demo.html`, `index.html`, and CSS/JS under `assets/`.

Live root rendered the expected title and fictional/offline/privacy notices. Direct `/demo.html` navigation resolved to `/demo` and rendered successfully. Simulated persona selection and fictional group creation worked; no real account or remote data was used. DOM checks found same-origin JS/CSS references, no forms or user-input controls, and no external links. The browser opened HTTPS without a certificate warning. Earlier desktop/mobile automated checks were already PASS and were not repeated.

`_headers` containing CSP (`connect-src 'none'`, `form-action 'none'`, `frame-ancestors 'none'`) and Referrer-Policy, nosniff, and no-store was included in the deployment. A read-only HTTPS HEAD request confirmed these live response headers and `HTTP 200`. Cloudflare also returned `Access-Control-Allow-Origin: *` (the site contains only public static assets), plus standard `Report-To`/`NEL` headers pointing to Cloudflare's network-error reporting endpoint. No Set-Cookie header was present. These are platform-provided headers, not application analytics scripts; no external resources or analytics scripts were observed in the page. The wildcard CORS response is not used to expose private data because the project has no API or user data. This does not affect existing production projects/services.

No GitHub push/merge, production Pages change, Render/Aiven change, database operation, real-data upload, billing action, custom domain, analytics, binding, function, or email activation occurred. Production remains NO-GO/Option C; email remains disabled.
