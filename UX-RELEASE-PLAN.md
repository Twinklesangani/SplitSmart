# UX release plan

NO-GO: local navigation candidate not authorized for deployment and full journey/browser gates remain pending. Current base a8663cd includes M1–M4 backend dependencies absent from live MySQL V7. No standalone frontend deployment from this tree is safe without a feature/API compatibility review. Pure shell/anchor changes are frontend-only, but a V7 release would require a separately pinned V7-compatible frontend candidate and tests; do not deploy the whole current frontend to V7 on that assumption. Combined release requires separately approved additive V8–V10, compatible token-version-aware backend, and matching frontend. PostgreSQL production migration remains NO-GO/Option C. Email disabled until authorized inbox/two-message test plus explicit production activation. No pushes, migrations or hosting changes authorized. Keep prior backups and offline demo untouched.

## Exact source compatibility and release paths

Historical V7 backend/frontend source: 418d757855dd10965c2872176022f93ac5e35a8a. Local UX/frontend+backend candidate baseline: 4222dca8da630ada0e4ce3f5df7b51ef25c179c2. Fresh live deployment SHA remains unconfirmed.

- Existing login/register, groups, expenses, balances and settlements: V7 routes present in historical source. Later API response compatibility still requires tests; shared route names alone are not proof.
- Password recovery/reset + JWT versioning: V8 and compatible backend. No email test or production enablement approved.
- Consent invitations/private links/email proof: V9 plus matching backend. Old direct enrollment must not be silently retained as a fallback.
- Persisted bell/read state/event notifications: V10 plus matching backend. Current AppShell mounts NotificationBell, unlike V7.
- Real dashboard aggregate/activity response: matching updated backend query/API required; not safe to assume V7 contract.
- Dashboard label, Members anchor, focus handling: frontend-only logic; no new API/schema. Changes reside in 4222dca, but its whole parent frontend also contains backend-dependent features.

Path A (not packaged or approved): create separate candidate from historical V7 frontend and port only compatible shell/anchor/focus changes, adapting to V7 components. Pin a new source SHA and artifacts after testing all legacy journeys against a synthetic V7 backend. Do not cherry-pick the complete AppShell/GroupPages files from current HEAD or deploy current bundle. No deployable V7 candidate exists yet; cannot claim GO.

Path B: use baseline 4222dca frontend/backend plus subsequent reviewed tooling/docs commits; separately approve fresh production identity/history/privilege preflight, encrypted backup+isolated restore, controlled additive MySQL V8–V10 and coordinated Render/Pages rollout. Pin built artifact checksums and exact final release SHA after full real-stack verification. Configure verified TLS/private credentials; eliminate old unversioned instances before recovery. Brevo metadata Free/relay/sender PASS but actual two-email inbox test is still pending; activate production email only after explicit approval and delivery verification. Keep offline demo and PostgreSQL Option C unchanged. Never restore unversioned V7 authentication after resets; gate traffic and forward-fix preserving auth versions and financial writes.

Blocking release gates: synthetic local PostgreSQL authentication, broad browser journeys, exact current hosting revisions, dedicated V7 candidate if Path A selected, fresh backup approval and coordinated release authorization. No migration/deployment/push performed.
Updated gate: local synthetic authentication and selected8 real-stack browser scenarios PASS. Current frontend includes mobile wrapping fix in the forthcoming local commit. V7 frontend-only candidate remains NOT READY: source-port/build/API verification still required before any separate V7 release. Combined V8–V10 candidate also remains NO-GO pending exact live baseline, fresh backup/restore, approved email inbox test and coordinated deployment authorization. No PostgreSQL migration needed for MySQL UX release.
