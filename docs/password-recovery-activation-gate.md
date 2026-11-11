# Password recovery activation gate — 12 November 2026

Implementation commit: c42be751f21e5b00209292902dc186d11bc8dce3.

## Evidence and external boundary

Current signed-in Brevo transactional page still presents Configuration / Verification onboarding and Welcome to SMTP. This does not prove API sending permission. Prior phone-verification blocker and support ticket #5661752 remain unresolved evidence; no support reply was available in this browser view. Owner action: complete the existing account transactional Verification step privately and, if still blocked, follow up on that existing ticket. Do not create duplicate accounts/tickets or purchase a domain/plan. No key or OTP belongs in chat.

Three synthetic RecoveryEmailTest cases passed in this run (zero failures/errors/skips): fixed HTTPS endpoint and API header, trusted fragment link/composition, and sanitized errors with private transport circuit breaking. No message sent. Previous 12 Java21 recovery tests remain valid. V8 migration test source preserves V1–V7 checksums, existing identity/hash and financial records; earlier MySQL evidence retained, no new migration execution claimed.

## Authorized inbox test, after separate approval

Approve a named private inbox, one reset email and the resulting password-change notification for a disposable synthetic account. Use an isolated database/application, trusted HTTPS origin and private environment configuration. Verify inbox receipt and tracking behavior privately; open link without recording it; reset once; confirm old password and JWT fail, new login succeeds and token reuse fails. Compare account/group/expense/balance snapshots. Test expiry locally using a controlled clock rather than sending more emails. Never activate production flags on the basis of an API 201 alone.

## Coordinated release decision

PostgreSQL migration is unnecessary for recovery. MySQL V8 adds token-version state and hashed reset tokens. The current branch also includes later features/migrations: do not deploy its full backend against V8 alone. Choose either the existing reviewed combined V8–V10 release or prepare and verify a dedicated recovery-only candidate, then pin exact backend/frontend SHAs and artifacts before approval.

Before any production migration: separately approve fresh encrypted backup and isolated restore, confirm production history/permissions and preserve recovery material. Gate incoming writes during the approved rollout; apply only the selected additive migrations through the migration identity, start the compatible version-aware backend, and ensure no old backend instance remains before recovery enablement. Deploy matching frontend. Keep email flags false until provider approval and inbox test succeed. Public unavailable responses must remain generic.

Pre-reset rollback may restore only a schema-compatible version-aware backend. After any reset, preserve auth_token_version monotonically; never restore V7 authentication or stale user rows that revive JWTs. Forward-fix while traffic is gated. Verify health, login, protected authorization, disabled recovery, financial reconciliation and matching frontend routes before reopening. Email activation is a final separately approved operation. Preserve Option C and the offline demo.

Status: local implementation verified; operational/live recovery BLOCKED by provider approval, authorized inbox test and separately approved pinned release. No production operations performed.
