# Implemented API inventory

This describes the reviewed local release candidate, not the older live deployment. JSON APIs use `/api`; authenticated operations require a Bearer JWT. Server authorization checks current membership, ownership or recipient identity as appropriate. Never put tokens in URLs or logs. DTOs/controllers are authoritative for payloads; this inventory deliberately does not invent a public API contract.

## Authentication and health

- `POST /api/auth/register`, `POST /api/auth/login`; `GET /api/auth/me`.
- `POST /api/auth/forgot-password`, `POST /api/auth/reset-password`.
- `POST /api/auth/email-verification`, `POST /api/auth/verify-email`.
- `GET /api/health`; `GET /actuator/health/liveness`; `GET /actuator/health/readiness`.

Forgot-password uses a generic response to avoid account enumeration; email-disabled mode reports unavailability rather than claiming delivery. Reset requires a valid single-use token and invalidates earlier JWT versions. Recovery responses prohibit caching. No profile-update endpoint is implemented.

## Groups and financial operations

- `GET /api/dashboard`.
- `GET /api/groups`, `POST /api/groups`.
- `GET /api/groups/{groupId}/members`.
- `POST /api/groups/{groupId}/leave`; `DELETE /api/groups/{groupId}/members/{userId}`.
- `DELETE /api/groups/{groupId}` archives the group while preserving financial history.
- `GET /api/groups/{groupId}/expenses`, `POST /api/groups/{groupId}/expenses`.
- `GET`, `PUT`, `DELETE /api/groups/{groupId}/expenses/{expenseId}`.
- `GET /api/groups/{groupId}/balances`, `GET /api/groups/{groupId}/settlement-suggestions`.
- `GET /api/groups/{groupId}/settlements`, `POST /api/groups/{groupId}/settlements`.

Expense creation uses an `Idempotency-Key` header. Suggestions are derived from balances; they do not delete original obligations. The legacy POST members route rejects direct enrollment with a conflict: use consent-based invitations.

## Invitations and notifications

- `GET`, `POST /api/groups/{groupId}/invitations`.
- `GET /api/invitations`; `POST /api/invitations/{id}/accept`; `POST /api/invitations/{id}/decline`.
- `GET`, `POST /api/groups/{groupId}/invite-links`.
- `DELETE /api/groups/{groupId}/invite-links/{id}`; `POST /api/invitations/link-accept`.
- `GET /api/notifications`; `GET /api/notifications/unread-count`; `PUT /api/notifications/{id}`.

Invitations require acceptance; expiry, revocation, duplicate and email-ownership rules apply. Notifications are scoped to the authenticated recipient. Copy-link invitations remain available when email is disabled. Do not disclose invite/reset secrets in examples or screenshots.
