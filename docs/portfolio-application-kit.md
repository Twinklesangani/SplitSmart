# SplitSmart portfolio application kit

Updated 13 November 2026. Source and hosting are unchanged. The public link is a static, fictional simulation; the separately developed full-stack application is not connected to it.

## Project summary

SplitSmart is an expense-sharing product built with Java 21, Spring Boot, React, MySQL/PostgreSQL and Flyway. The full-stack codebase includes authentication and password recovery, consent-based group invitations, persisted notifications, expense tracking, balance summaries and settlement suggestions. The portfolio site is a separate React-only static simulation with preset fictional people and transactions. It has no backend, database, login, email or production API connection.

## Resume entry

**SplitSmart — Expense-Sharing Platform** | Java 21, Spring Boot, React, MySQL, PostgreSQL, Flyway

- Built full-stack group-expense workflows with secure account recovery, consent-based invitations, notifications, balance summaries and settlement suggestions; preserved exact decimal amounts and original obligations while simplifying repayment recommendations.
- Added an additive PostgreSQL migration path and data-validation tooling, with synthetic migration and reconciliation coverage.
- Published a separate offline React portfolio simulation on Cloudflare Pages. The demo uses fictional preset data and does not call the Spring Boot APIs. Focused evidence includes 42 backend tests, 20 frontend tests, 26 PostgreSQL synthetic integration tests, and demo-specific Java, React, HTTP and browser checks.

## LinkedIn project description

I built SplitSmart to make shared expenses easier to understand and settle. The full-stack application uses Java 21, Spring Boot and React, with MySQL/PostgreSQL and Flyway-backed schema management. It includes group expenses, balance summaries, settlement suggestions, secure password-recovery flows, invitations and notifications. I added focused tests around authorization, financial calculations and migration behavior. The public portfolio link is a separate, offline React simulation with fictional preset data; it does not connect to the backend or use real accounts.

Demo: https://splitsmart-offline-demo.pages.dev/

## Two-minute recruiter walkthrough

**0:00–0:15 — Set expectations**

“Here is the SplitSmart portfolio demo. It is a static, offline simulation, so every person and amount is fictional. It does not call the backend or collect login details.” Point to the banner that says “No API or database connection.”

**0:15–0:35 — Start the guided flow**

Choose “Register fictional Mira,” then “Try as Mira,” and create the preset group. Explain that this imitates onboarding without creating an account. Point out the group summary and INR balances.

**0:35–1:00 — Show shared spending and invitations**

Open Trip ledger and Invitations. Walk through the preset expense split and recipient acceptance states. Mention that the real application implements these flows with authenticated Spring Boot APIs; this static demo only illustrates the interaction.

**1:00–1:25 — Explain settlement logic**

Open Circular debts. Show the four fictional ₹50 obligations A→B→C→D→A. The net settlement suggestions are zero while the original obligations remain visible. Explain that the demo uses fixed snapshots checked against the full-stack settlement logic; it does not calculate against a live account.

**1:25–1:45 — Cover notifications and recovery**

Show the notification panel and the recovery section. Clarify that these are simulated states: no invitation email or password-reset email is sent from the portfolio demo.

**1:45–2:00 — Close with the architecture and tests**

“The production-style application is Java 21 and Spring Boot with a React client and Flyway migrations. I tested the backend, frontend, migration and financial paths with synthetic data. This published page is intentionally separate so it cannot expose accounts or financial records.”

## Public demo facts and limitations

- URL: https://splitsmart-offline-demo.pages.dev/ (also supports the `/demo` route).
- Cloudflare Pages Free Direct Upload; new project `splitsmart-offline-demo`.
- Reviewed source SHA: `d58b0c7925266ef7fe7804dd7cc89c07fc66adb4`.
- Artifact: `frontend/dist-demo`; manifest SHA-256: `f750c131049103dd5fdbe45def7374689763a87dbccb4ee627a3a231a4050125`.
- Fictional state is in-memory and resets on refresh. Login, invitations, expense changes, notifications and recovery are demonstrations only. No API, DB, email, payment or persistence is involved.
- Live HTTPS response headers were verified in the deployment closure record. Cloudflare adds `Report-To`/`NEL` and `Access-Control-Allow-Origin: *` at the edge; app assets are same-origin and public.
- Prior local/container responsive tests passed at desktop and mobile dimensions. A fresh independent Incognito/mobile public-browser check was attempted for this closure but browser controls stopped before navigation; do not claim that additional check passed. No regression was confirmed in the completed live smoke checks.

## Separate production application: actions still needed

- **Data preservation:** Aiven MySQL is the existing production source and is scheduled for retirement on 21 December 2026. Existing encrypted backups and tested local restores are valuable but stale against ongoing production writes. A fresh encrypted V7 snapshot and isolated restore passed on 12 November 2026; subsequent writes are outside that snapshot. Obtain separate approval for the final frozen snapshot; keep the GPG recovery key protected and establish an independently recoverable encrypted copy. Do not assume provider backups survive service retirement.
- **Privacy:** Production data migration to PostgreSQL remains NO-GO under Option C. PostgreSQL primary errors may echo values into provider logs; local validation reduces but cannot eliminate this risk. Do not upload real records until the owner changes the policy or a stronger supported protection is verified.
- **Monitoring and access:** Review Aiven service retirement/plan notices, service health and quota; restrict access to Render, Aiven and Cloudflare logs; verify application log retention and that reset tokens, credentials and financial details are never logged. Platform request/error telemetry exists independently of app analytics.
- **Release state:** No production migration or deployment occurred in this milestone. The approved two-email synthetic recovery test passed; production email remains disabled. Production release needs its separate privacy, backup, migration-window and deployment approvals.

## Handoff

No code change or redeployment is part of this closure. Keep the existing uncommitted preservation and database work untouched. Resume with production backup/retirement planning only under its existing approval boundaries.
