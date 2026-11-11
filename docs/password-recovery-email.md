# Password recovery email: configuration handoff

Email-disabled release update: `VITE_EMAIL_ACTIONS_ENABLED=false` is the default UI
gate. Keep both backend delivery flags false independently. Recovery/check-email pages
explain unavailability; email-invitation creation is hidden and verification requests
disabled. Private invitation links and other product features do not depend on Brevo.
Later approved activation also needs a frontend rebuild with this flag true, after
eligibility and real inbox/reset verification; changing a UI flag cannot enable sending.
Support ticket [#5661752](https://help.brevo.com/hc/requests/5661752) has been confirmed
submitted. No need to wait for its reply to prepare an email-disabled release.

Account-specific update, 7 November 2026: signed-in account shows Free plan, 300 shared
marketing/transactional emails/day and no configured payment method. SplitSmart sender
is Verified with a freemail/DMARC warning. Phone verification still blocks key creation
and sending; transactional approval/domain-free eligibility and real delivery remain
unproven. No email/key/configuration change. Both flags stay false. The owner must
complete private phone/OTP verification and explicitly approve a reset message to a
named inbox. A successful reset also queues a password-change notice, which needs its
own sending approval. See [release runbook](production-release-readiness.md) for current
gates and the prohibition on rollback to unversioned authentication. This update
supersedes older statements below that no sender/account-specific check has occurred.

M1B is prepared locally. Email delivery remains disabled. No provider account, API key, paid service, production migration, push or deployment was created by this work.

## Verified public terms, 6 November 2026

- [Brevo Free](https://help.brevo.com/hc/en-us/articles/208589409-About-Brevo-s-pricing-plans): 300 emails/day, no card, no time limit; quota does not roll over. Do not buy credits or upgrade to handle limits.
- [Sender/account requirements](https://help.brevo.com/hc/en-us/articles/115000188150-Troubleshooting-Issues-with-Brevo-SMTP): a sender must be verified, and new transactional accounts require separate activation through Brevo support. API credentials are private and separate from SMTP keys.
- [Domain requirements](https://help.brevo.com/hc/en-us/articles/16045394674066-Troubleshooting-Domain-authentication): free mailbox domains cannot be authenticated by their users. Brevo documents temporary rewriting to brevosend.com for unauthenticated/free-mail senders. This does not establish permanent domain-free eligibility for this account. No custom domain purchase is authorized.
- [Tracking](https://help.brevo.com/hc/en-us/articles/11643306229906-Can-I-anonymize-the-tracking-of-opens-and-clicks-for-my-emails): tracking is enabled by default; anonymization is not the same as disabling link rewriting. The adapter sends plain text without HTML/pixels and does not invent unsupported tracking-disable headers. Inspect a received test message before declaring reset links safe for live use.
- [HTTPS API](https://developers.brevo.com/reference/send-transac-email): uses POST https://api.brevo.com/v3/smtp/email with server-side api-key authentication. Render Free's SMTP restrictions do not prevent this HTTPS design.

## Exact owner action at the account boundary

1. Sign in to an existing Brevo Free account, or manually create a Free account only if it asks for no card/billing activation. Verify a sender mailbox you already control.
2. Request transactional sending approval and ask Brevo to confirm this mailbox can send password-reset messages on Free without purchasing a domain or supplying a payment method. Stop if that is required.
3. Confirm the actual Free limits and that the account is not a paid trial. Do not activate add-ons, credits, billing or a paid plan.
4. Keep API credentials entirely outside chat/Git. After eligibility is confirmed, configure an ignored local environment file or the approved host's private secret settings in a separately authorized step. Never paste a key into chat or use a VITE_ variable for it.
5. Before marking delivery verified, privately test a received plain-text message: correct sender, trusted origin, no third-party tracking wrapper or token in Referer/access logs, working single-use link, and expiration. Do not log or screenshot a real token. Confirm account approval rather than relying on a successful API response alone.

Current boundary: no account-specific eligibility, sender ownership/approval, delivery or tracking behavior has been verified. No key is requested now. Keep both enablement flags false until the review and a separately authorized test/configuration step are complete.

## Private server configuration (names only; no credentials)

- RECOVERY_EMAIL_ENABLED: false by default.
- RECOVERY_EMAIL_DELIVERY_VERIFIED: false by default. An operator assertion that sender approval, no-card/domain-free eligibility and safe actual delivery have been reviewed; not an automatic provider capability check.
- BREVO_API_KEY: server-only API key, never SMTP key. No default key.
- RECOVERY_EMAIL_SENDER: verified sender email. No default sender.
- RECOVERY_FRONTEND_ORIGIN: exact trusted HTTPS origin, for example https://splitsmart-92b.pages.dev; no path, userinfo, query or fragment. No request Host header is used.
- APP_RECOVERY_TOKEN_LIFETIME_MINUTES: optional server setting from 15 to 30 minutes; defaults to 30. The database expiry and the expiry stated in the email use the same instant. No schema migration is required for this setting.

Both flags must be true before the adapter is available; missing/invalid configuration fails closed. The transport uses a fixed HTTPS endpoint, three-second connection and five-second read timeouts, no redirects, no automatic retry, and discards provider bodies. Any non-201 response or transport failure privately stops sending until process restart/review. Public availability reflects configuration only: recipient-dependent failures cannot change public status and reveal account existence. A configured request still returns a conditional 202 after such a failure; API acceptance is never presented as delivered email. Disable the configuration explicitly during operational recovery.

## UI and API contract

- Login links to /forgot-password. Enabled forgot-password accepts a normalized email and returns 202; /check-email says only that the request was received. Disabled email returns the same 503/no-store response for every valid address and keeps the user on the request form with an unavailable message.
- Delivery links use /reset-password#token=...; fragments do not reach the HTTP server. Startup captures the token in module memory and removes fragment/query from browser history before React, authentication fetches or navigation. Query-string tokens are not accepted.
- Tokens and passwords never enter sessionStorage, localStorage, router state, console output or UI text. Refreshing the cleaned reset page requires reopening the email link (if still valid) or requesting another.
- Reset uses a POST body with token, password and confirmPassword. A successful 204 clears local session state and shows reset success followed by login. The reset route works while signed in. Invalid/expired/used links lead to a new-link action; uncertain network outcomes offer explicit retry/sign-in guidance, not automatic resubmission.
- V8 and JWT versioning are unchanged by M1B. Legacy JWT compatibility lasts until the account resets, when all older versions fail database validation. No new migration is introduced.

## Release gate

No live release is approved. Apply V8 through the separately authorized migration identity before deploying the version-aware backend; do not enable recovery while old backend instances can authenticate revoked JWTs. Coordinate frontend/backend rollout for the new disabled-provider 503 behavior. A safe rollback must not revive pre-reset JWTs by reverting to unversioned authentication.

Local delivery queue and account throttles retain M1A limitations: best-effort in-memory queue, restart-local source counters, persistent account issuance limits. A 202 is not a delivery receipt. A password-change security email is queued only after the successful reset commits; delivery failure cannot undo the reset. Durable queuing/operational reporting and token retention cleanup remain follow-up considerations before wider live use.
