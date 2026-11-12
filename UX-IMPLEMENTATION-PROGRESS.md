# CTO final product gate / consolidated remaining-work tracker

Authoritative status for this local milestone, 12 November 2026. Code candidate d9a2992e8965db576c86edf137b2b93501fed17e on codex/password-recovery-backend. Backend source unchanged since c42be751f21e5b00209292902dc186d11bc8dce3. Earlier UX navigation commits4222dca and4a7ee99 retained. Production NO-GO.

## PASS — completed local features and verification

Settings: protected /settings, discoverable desktop/mobile navigation, authenticated account name/email, honest read-only profile, security guidance and recovery-availability entry. No authenticated profile-update API exists; no edit/destructive-account controls invented. Existing global Sign out retained. Long email text wraps. No new dependency, schema or API change.

UX fixes: Dashboard naming; direct group Members navigation; async members/invite section focus; route focus; mobile wrapping of group tabs. Existing registration/login/groups/consent invitations/expenses/equal-custom splits/history/balances/suggested transfers/repayments/notifications retained.

Current executions:39 React tests (dashboardUx/authentication/passwordRecovery), two real Java21/local PostgreSQL Settings browser cases at375/1280, production frontend build and oxlint PASS; two runner-isolation tests PASS; credential-pattern scan of production assets zero findings (limited pattern scan, not proof against all secret forms). Previous unchanged real-stack8 browser cases, Java21 authorization/financial34, recovery12 and synthetic Brevo-adapter3 remain valid. Added Settings affects sidebar: responsive suite rerun is required/recorded in UX-TEST-RESULTS. No container/backend configuration changed in this Settings milestone; earlier rootless behavior evidence retained, no new container PASS claimed.

Local PostgreSQL: existing loopback25432 synthetic DB authentication PASS after starting stopped cluster; no credential resets. Browser test runner pins synthetic identity/DB incarnation, disables outbound mail and captures logs privately. Owned API/Vite processes stop after each run. Financial/recovery databases and backups preserved. Initial broad registration failures were isolated and passing suites retained; no production security limits weakened.

## BLOCKED — actual recovery email delivery

Brevo activated by support; non-sending account/sender API authentication PASS, account Free/relay enabled/expected sender active. API credential remains DPAPI-only; no key copied into project. Actual reset/security-notice delivery NOT TESTED. Exact inbox absent. Owner must approve recipient address plus exactly one synthetic reset email and one password-change notification. Follow docs/brevo-activated-test-gate.md in isolated local environment with trusted HTTPS origin; tokens remain private. No live enablement implied. Existing automated expiry/single-use/concurrency/password/JWT/data-preservation evidence retained; no production-account reset performed.

## PARTIAL / NOT TESTED — release rather than local product gaps

Editable profile is intentionally unavailable because no supported update API; not required for minimal read-only Settings. Current live frontend SHA/backend SHA unconfirmed; public last read-only login200 asset index-rWmFr6Iq.js and backend healthUP do not identify a deployment commit. Historical V7 source418d757855dd10965c2872176022f93ac5e35a8a retained only as historical evidence.

Dedicated V7 frontend candidate NOT BUILT/NOT VERIFIED. Full candidate cannot be independently sent to V7 because recovery/JWT usesV8, consent invitationsV9, notificationsV10 and dashboard needs updated backend. No claim of a production-ready V7 package. Coordinated MySQLV8–V10 path is the selected preparation path; PostgreSQL production migration is unnecessary and remains OptionC/NO-GO.

## Coordinated release approval package — no execution authorized

Source frontend/backend candidate:d9a2992e8965db576c86edf137b2b93501fed17e. Frontend artifact frontend/dist (not dist-demo); build file hashes recorded in UX-RELEASE-PLAN. Backend byte-identical source toc42be75, but newly packaged production JAR/image digest must be pinned during separately approved release preparation, not inferred from prior synthetic runner JAR. Existing migrations must retain checksums; ordered MySQLV8 password recovery,V9 invitation consent,V10 notifications. PostgreSQL migrations excluded.

Before execution owner separately approves: exact production metadata/privilege preflight; one fresh consistent encrypted backup and isolated restore; required migration permissions; maintenance/write gate and additiveV8–V10; deployment of compatible version-aware Render backend and matching existing production Cloudflare frontend (never offline demo); final email enablement only after approved delivery test. No push/merge included unless explicitly approved. Current plaintext source-level settings do not contain secret values.

Sequence: verify exact live baseline and source history -> fresh encrypted backup/restore -> gate writes -> additive migrations -> compatible backend health/auth checks, no old unversioned instance serving -> matching frontend -> approved synthetic smoke (auth/isolation/groups/expense/splits/balances/settlement/notification/settings/recovery) -> explicit reopen/GO. Preserve MySQL/data/backups. If failure, keep traffic gated and forward-fix schema-compatible version-aware backend. After reset never restoreV7 auth/stale auth versions or discard new financial writes. Email flags remain false until separately approved actual enablement. See existing production-release-readiness and UX-RELEASE-PLAN for exact environment-variable names and recovery controls.

## Owner decisions only

1. Exact private inbox and two-email test scope.
2. Separate production preflight/backup/migration/deployment authorization after reviewing pinned artifacts and remaining live baseline gaps.
No paid services, production writes, deployment, cloud real data, push/merge, backup deletion or offline demo changes occurred.
