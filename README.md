# SplitSmart

SplitSmart is a group expense management application intended to help people
track and manage shared expenses.

## Live portfolio and release identity

- Frontend: [SplitSmart on Cloudflare Pages](https://splitsmart-92b.pages.dev/).
- Backend: [public health check](https://splitsmart-backend-ydj2.onrender.com/api/health).
- Latest verified deployed backend release: `bc59921336f148784317550a5f29202edbeda825`
  on `codex/product-completion`; its GitHub CI completed successfully.
- This checkout also contains later engineering work. Its branch and feature
  documentation must not be treated as proof that those changes are deployed.
  The frontend deployment SHA needs separate confirmation in Pages.

### Free hosting availability

This is a portfolio demo with best-effort availability, not guaranteed 24/7 service.
[Render Free](https://render.com/docs/free) sleeps after 15 idle minutes, can restart,
and shares 750 instance-hours per workspace per calendar month. One continuously
running service needs 720 hours in November or 744 in a 31-day month; other services
consume the same allowance. No card was on file and November usage was 1.93 hours
when inspected on 5 November 2026; these are observations, not ongoing guarantees.
Without a payment method, exhausted allowances can suspend service rather than
create paid capacity. Do not add billing to keep this demo online.

[Aiven Free](https://aiven.io/docs/platform/concepts/service-pricing#free-tier)
may power off inactive databases after notification. A public process health check
does not query MySQL and cannot prevent this. The existing database was recovered
from power-off; readiness includes the database health contributor. Monitor provider
notifications and recover the existing Free service manually when needed, preserving
MySQL compatibility and keeping credentials and backup files outside Git.

[Cloudflare Pages limits](https://developers.cloudflare.com/pages/platform/limits/)
include 500 builds/month, 20,000 files and 25 MiB per asset on Free; Pages Functions
have separate Workers quotas. Static frontend delivery does not ensure API/database
availability. [UptimeRobot Free](https://uptimerobot.com/pricing/) supports five-minute
monitoring; use one private monitor within its
[fair-use policy](https://uptimerobot.com/terms-fair-use/).

The existing HTTP monitor has email alerts and no attached public status page.
Its Free configuration uses HEAD: the deployed `/api/health` allows GET but currently
returns HTTP 401 to HEAD. A DOWN monitor therefore does not necessarily mean GET
or MySQL is unavailable. Do not upgrade or claim monitoring recovery until this
method mismatch is resolved through a separately reviewed minimal change or a
verified Free GET-capable monitoring option. No such code change is included here.

### Publication gate (5 November 2026)

GitHub is private, with no open PRs at review time. The deployed release and recent
CI runs passed; tests were not repeated for this documentation-only review.
The local checkout differs from the deployed branch and has a pre-existing test
edit that is not part of this documentation commit. Compare release SHAs before
publishing. Build/run instructions below describe this checkout.

Tracked-file checks found no generated build trees, raw logs, private-key files or
local environment files. Ignore rules cover environment files, local credentials,
database data and operational reports. A bounded heuristic scan of 67 text blobs
reachable from local branches and remote-tracking refs found no provider-token or
private-key pattern matches; literal-password candidates still need classification
with the established secret scanner. This is not a clean-history certification.
GitHub reported Dependabot alerts and secret scanning disabled, so unresolved
security alerts cannot be ruled out from those services. The prior revoked-account
incident is documented in the engineering summary; do not publish until historical
credentials, logs and scanner findings have been reviewed. No Git history was rewritten.

Recommendation: keep the current MySQL-compatible services for an honestly labelled
best-effort portfolio, retain TLS identity verification and private credentials,
review provider inactivity emails and maintain private recoverable backups.
Do not migrate to an unverified free provider for an always-on promise. Five-minute
monitoring is a documented Free feature, not a provider uptime guarantee or a way
to override suspension limits. Render had no card and projected $0 charges at review;
Aiven and Cloudflare account-wide payment settings were not independently verified.

## Technology stack

- Backend: Java 21, Spring Boot 3.5.x, Maven, and MySQL 8
- Frontend: React, Vite, JavaScript, and plain CSS
- Version control: Git and GitHub
- Deployment goal: a public HTTPS application accessible on mobile and desktop

## Release status and architecture

React calls the Spring Boot REST API with bearer JWTs. Controllers delegate to
transactional services; Spring Data JPA persists users, memberships, expenses,
splits and repayment records in MySQL. Flyway owns versioned schema changes.
Membership and creator/sender permissions are enforced by the backend.

Historical verified baseline: 83 backend tests, 10 real MySQL integration tests,
76 frontend tests and 6 Edge browser scenarios. These are bounded local results.
The old exposed database account's access has been removed and the application
uses a dedicated DML-only runtime account. Publication still requires the owner's
GitHub account selection and final content review; see
[engineering quality summary](docs/engineering-quality.md).
JWT runtime verification is recorded in the local final release report. A live
portfolio deployment exists as identified above; GitHub remains private.

```text
React frontend → REST / JWT → Spring Boot controllers
              → transactional services → JPA repositories → MySQL
```

Services enforce membership/creator permissions and use group-level database
locking for financial writes. EQUAL shares use deterministic paise allocation;
EXACT shares must sum to the total. Settlement request IDs prevent duplicate
bookkeeping entries. Flyway manages migrations; runtime credentials validate
existing migrations but cannot apply future DDL. Future schema changes require a
separate authorized migration step. Local performance/tracing results are bounded
observations, not production-scale guarantees.

CI uses Java 21 and Node 24, Maven unit tests, Vitest, Oxlint and Vite. Hosted
validation passed 83 backend tests, 76 frontend tests and 10 real MySQL tests.
Pull requests targeting `main` run the disposable MySQL Testcontainers suite through
`verify -Pmysql-it`, alongside backend and frontend jobs. These financial tests check
persisted splits, authorization, rollback and concurrent repayment constraints.
A failing test fails its job; no `continue-on-error` hides failures.

Manual execution remains available through Actions → CI → Run workflow: set
`mysql_integration` to true to include MySQL tests (false runs backend/frontend only).
Pushes to `main` run backend/frontend checks. No real database credentials, deployment
or AI review are enabled. Automatic PR integration passed in
[CI run 41126007661](https://github.com/Twinklesangani/SplitSmart/actions/runs/41126007661),
including 83 backend tests, 10 MySQL integration tests, 76 frontend tests, lint and build.
Running a job is distinct from enforcing it as a merge requirement; branch protection
or rulesets must be configured separately where the repository plan permits them.

For checks, run `./mvnw.cmd clean test`, `./mvnw.cmd package`, and
`./mvnw.cmd verify -Pmysql-it` from backend; run `npm.cmd run test -- --pool=threads --maxWorkers=1`,
`npm run lint` (Oxlint), and `npm run build` from frontend. Package before starting
the isolated browser runner: `powershell -NoProfile -File scripts/run-final-release-e2e.ps1 -Suite all`
from the repository root. Never package while its test JVM holds the JAR open.

## Repository structure

- `backend/` - Spring Boot REST API, persistence, security, and tests
- `frontend/` - React and Vite browser-based user interface
- `README.md` - project overview, stack, structure, and development status
- `.gitignore` - excludes local, sensitive, and generated files from version control

## Run the backend locally

Use Java 21 (set `JAVA_HOME` and ensure its `bin` is first on PATH), Node.js compatible
with the installed Vite package's `engines` requirement, and MySQL 8.4. Docker Desktop
is required only for the disposable MySQL integration/browser checks. Use the Maven
Wrapper; a global Maven installation is unnecessary.

Configure MySQL and load the ignored backend environment file using the instructions
below **before** starting Spring Boot. From the repository root, run:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

The backend uses the default server port, 8080.

## Health check

For local development on port 8081, start the backend with:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"
```

Send a GET request to `http://localhost:8081/api/health`. It returns:

```json
{"status":"UP"}
```

## Frontend

The frontend uses React 19, Vite 8, JavaScript, React Router, and plain CSS.
From the repository root, install dependencies and start the development server with:

```powershell
cd frontend
npm install
npm run dev
```

Create a production build with `npm run build`.

For a reproducible installation from the committed lockfile, prefer `npm ci`.
Run frontend checks with `npm run lint`, `npm run test`, and `npm run build`.

The UI includes `/dashboard`, `/groups`, group overview, expenses, balances and
settlements routes under `/groups/:groupId`, and `/login` and `/register`.
Group listing, creation, details, and member management use authenticated API
data. The dashboard shows the actual group count and a real group preview, while
financial dashboard totals and activity remain honest placeholders. Group
balances, repayment suggestions, and settlement history use the authenticated
finance APIs. Group expenses can be listed, created, viewed, edited, and deleted
through the authenticated expense API. The authenticated shell has desktop
sidebar navigation and an Escape-dismissible mobile drawer.

Authentication calls `POST /api/auth/register`, `POST /api/auth/login`, and
`GET /api/auth/me`. Configure the frontend API origin in
`frontend/.env.local` using `VITE_API_BASE_URL`; the development default is
`http://localhost:8081`. `frontend/.env.example` contains the local example.
The backend CORS allowlist must include the exact frontend origin
(`http://localhost:5173` by default).

The login response's `accessToken` is kept in memory and `sessionStorage` for
same-tab refresh continuity; the backend profile endpoint verifies it before
protected UI is rendered. The browser clears the session on logout or expected
token expiry, but logout does not revoke the JWT on the server, so the token
may remain valid until its expiry. `sessionStorage` is readable by JavaScript
and does not protect a token from XSS; it is an MVP tradeoff, not a production
ideal. Do not store passwords or tokens in `localStorage`.

Protected frontend routes are a navigation safeguard only. Spring Security's
JWT validation and server-side authorization remain the security boundary.

Group management calls `GET /api/groups` and `POST /api/groups`. Group details
are resolved from the authenticated user's group list because the backend does
not expose a single-group endpoint. Member lists use
`GET /api/groups/{groupId}/members`; adding an existing account uses
`POST /api/groups/{groupId}/members` with `{ "email": "..." }`. New groups need a
nonblank name of at most 120 characters. Only the group creator may add
members; the backend also verifies membership and returns a privacy-preserving
404 to users outside the group. The UI shows the add-member form only to the
creator, but the backend remains authoritative. Member addition uses the
registered email address; user search, invitations, and directory access are
not provided.

The frontend group service is `frontend/src/services/groupService.js`.
Group lists, details, and member lists distinguish loading, empty, retryable
error, and inaccessible-group states. Group creation refreshes local list state
from the real create response and opens the returned group ID. Member addition
refreshes the authorized member list after success. Session-expired group
requests clear the current browser session and protected group content.

The expense UI uses `frontend/src/services/expenseService.js` and these existing
authenticated endpoints:

- `GET /api/groups/{groupId}/expenses` and `GET /api/groups/{groupId}/expenses/{expenseId}`
- `POST /api/groups/{groupId}/expenses`, `PUT /api/groups/{groupId}/expenses/{expenseId}`, and `DELETE /api/groups/{groupId}/expenses/{expenseId}`

The create and edit forms resolve payer and participant names from the current
authorized group-member list and submit their IDs. New expenses default to
EQUAL splitting; the backend calculates and persists the final shares. EXACT
splits use string-form decimal amounts and integer-paise validation in the UI;
the saved shares are the default edit values because split mode is not stored by
the API. The UI accepts amounts up to INR 999,999,999,999.99 so every paise can
be safely validated and round-tripped through the current JSON numeric expense
responses; the backend supports a larger DECIMAL(19,2) range. The expense API
does not currently return a creation timestamp, so the UI identifies that
timestamp as unavailable rather than deriving one.

Only the original creator is shown edit and delete controls; Spring Security
authorization remains authoritative. Delete requires confirmation and only
removes the expense from the UI after the API confirms success. Expenses are
available under `/groups/:groupId/expenses`, with create, detail, and edit
routes beneath it. Financial dashboard totals and activity remain placeholders;
the balances, repayment suggestions, settlement history, and reported
repayment form are connected separately to the finance APIs.

### Balances and repayments

The balances page reads `GET /api/groups/{groupId}/balances` and
`GET /api/groups/{groupId}/settlement-suggestions`; the settlements page reads
`GET /api/groups/{groupId}/settlements`. It displays the server-provided INR
totals, member balances, and ordered repayment recommendations. The browser
validates response shape and formats safe paise amounts but does not calculate
balances or generate repayment recommendations.

The settlement form records a payment the signed-in member reports making with
`POST /api/groups/{groupId}/settlements`. It never transfers money. The server
derives and authorizes the sender, validates current balances, and rejects
duplicate request IDs. On an uncertain network result, the UI retains the same
request ID and requires history reconciliation before an explicit retry. After
a successful record, balances, suggestions, and history are refreshed from the
server. INR is the only supported currency, and the dashboard's financial
summary and activity sections remain placeholders.

To run the backend locally, load the ignored `backend/.env.local` values into
the current PowerShell process as described below, then start Spring Boot on
port 8081. In a separate terminal, start the frontend from `frontend/` with
`npm run dev`.

## Local MySQL database

The portable paths below describe this development machine, not an installer for a
new machine. On another machine, provision MySQL through its supported installer,
create an empty local schema and a dedicated loopback account using an authorized
administrator, then copy `backend/.env.example` to `backend/.env.local` and replace
placeholders locally. Never reuse an exposed credential. Do not reset an existing
database or edit Flyway V1–V3. Separate migration privileges from runtime privileges
when preparing deployment; the current local account has schema DDL permissions.

The local development database uses portable MySQL 8.4.13 LTS, database
`splitsmart_dev`, and a dedicated DML-only runtime account. The server binds only to
`127.0.0.1:3306`; its data and server configuration are stored under
`%USERPROFILE%\mysql\splitsmart`, outside this repository.

Start the portable local server in PowerShell with:

```powershell
& "$HOME\mysql\mysql-8.4.13\bin\mysqld.exe" --defaults-file="$HOME\mysql\splitsmart\my.ini" --console
```

Press `Ctrl+C` in that terminal to stop it. Local connection settings belong in
the ignored `backend/.env.local`; use `backend/.env.example` as a placeholder
reference. Keep all real values private. Spring Boot reads `DB_URL`,
`DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, and
`APP_CORS_ALLOWED_ORIGINS` from its process environment.
PowerShell commands below load the ignored local file without putting secrets
in tracked files:

```powershell
Set-Location backend
Get-Content .\.env.local | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"
```

`APP_CORS_ALLOWED_ORIGINS` is a comma-separated allowlist of exact browser
origins. It defaults to the local Vite origins `http://localhost:5173` and
`http://127.0.0.1:5173`; configure the deployed frontend's HTTPS origin in the
backend environment. Wildcard origins are rejected.

The ignored `backend/.env.local` also holds `JWT_SECRET`, a Base64-encoded
random key of at least 32 bytes. Keep it private; configure the same variable
through the hosting platform's secret manager when deploying.

Flyway applies versioned schema changes from
`backend/src/main/resources/db/migration`. Hibernate validates the mappings
against that schema and does not create or update tables. Keep schema changes
in new Flyway migrations rather than relying on Hibernate auto-update.

## Authentication

`POST /api/auth/register` accepts:

```json
{
  "name": "Test User",
  "email": "test@example.com",
  "password": "SecurePass123!"
}
```

Successful registration returns `201 Created` with the generated ID, name, and
normalized email. Invalid input returns `400 Bad Request`; an already
registered email returns `409 Conflict`.

Example response:

```json
{
  "id": 1,
  "name": "Test User",
  "email": "test@example.com"
}
```

`POST /api/auth/login` accepts the registered email and password and returns a
signed Bearer access token valid for 60 minutes. Use it to call
`GET /api/auth/me` with an `Authorization: Bearer <accessToken>` header. The
current-user endpoint returns only the account ID, name, and email. Invalid
credentials and missing or invalid tokens are rejected. There are no refresh
tokens or token-revocation mechanism yet.

API errors use a consistent JSON response containing `code`, `message`,
`timestamp`, and `path`. Authentication failures do not include token or
credential details; unexpected server errors return a generic message.

## Groups

Both group endpoints require a valid Bearer token from login. `POST
/api/groups` creates a group and automatically makes its creator a member:

```http
POST /api/groups
Authorization: Bearer <accessToken>
Content-Type: application/json

{"name":"Goa Trip"}
```

The response is `201 Created` with the generated group ID, name, and creator
user ID. The creator and initial membership are saved in one transaction.
`GET /api/groups` returns the authenticated user's memberships, newest group
first; it returns `[]` when the user has none. Neither endpoint accepts a user
ID from the client to select the acting user.

List the signed-in user's groups with:

```http
GET /api/groups
Authorization: Bearer <accessToken>
```

The create response is:

```json
{"id":1,"name":"Goa Trip","createdByUserId":12}
```

The list response is an array of the same group objects:

```json
[{"id":1,"name":"Goa Trip","createdByUserId":12}]
```

### Group members

Only the group creator can add a member, and the target must already have a
SplitSmart account. The email is trimmed and normalized to lowercase. Adding
the creator or an existing member again returns `409 Conflict`; an unknown
email or inaccessible/nonexistent group returns `404 Not Found`. Invalid
requests return `400 Bad Request`, and a non-creator member attempting to add
someone receives `403 Forbidden`.

```http
POST /api/groups/1/members
Authorization: Bearer <accessToken>
Content-Type: application/json

{"email":"friend@example.com"}
```

Successful addition returns `201 Created`:

```json
{"userId":25,"name":"Friend","email":"friend@example.com"}
```

Any current group member can list members; unrelated users receive `404 Not
Found`, matching the response for a nonexistent group:

```http
GET /api/groups/1/members
Authorization: Bearer <accessToken>
```

The `200 OK` response contains public member profiles, including the creator,
ordered by when each membership was added:

```json
[
  {"userId":12,"name":"Creator","email":"creator@example.com"},
  {"userId":25,"name":"Friend","email":"friend@example.com"}
]
```

## Expenses

Any current group member can record an expense, but the payer and every split
participant must also be members of that same group. The authenticated member
is recorded as the creator; the payer is the person who paid the total, while
each split amount is the amount owed by that participant. These may be
different people. Expenses support `INR` only.

Create an expense with `POST /api/groups/{groupId}/expenses`:

```http
POST /api/groups/5/expenses
Idempotency-Key: "00000000-0000-4000-8000-000000000001"
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "description": "Dinner",
  "amount": 900.00,
  "paidByUserId": 25,
  "splits": [
    {"userId": 12, "amount": 400.00},
    {"userId": 25, "amount": 300.00},
    {"userId": 31, "amount": 200.00}
  ]
}
```

For backward compatibility, omitting `splitType` continues to use `EXACT`
splitting, and existing `splits` requests are unchanged. `EXACT` may also be
specified explicitly. All exact split amounts must be positive, have at most
two decimal places, and sum exactly to the expense total.

To divide the amount equally, send `splitType: "EQUAL"` and a nonempty list of
current group member IDs instead of `splits`:

```json
{
  "description": "Dinner",
  "amount": 1000.00,
  "paidByUserId": 25,
  "splitType": "EQUAL",
  "participantUserIds": [31, 12, 25]
}
```

The backend calculates shares in integer paise using exact decimal arithmetic.
It sorts participant IDs ascending, divides the total paise evenly, then gives
one leftover paise to each of the first participants until the remainder is
used. Thus the example produces user 12: `333.34`, user 25: `333.33`, and user
31: `333.33`, returned in ascending user ID order. A participant must receive
at least `INR 0.01`; an amount too small to give every participant a positive
share is rejected. Both modes persist their final shares through the same
expense creation transaction and `expense_splits` table. Split mode records
how shares are calculated; the database stores the resulting amounts rather
than a split-mode property.

The `201 Created` response includes generated IDs, the fixed `INR` currency,
the authenticated creator, payer, and each persisted split. The expense total
must be positive and exactly equal the positive split sum. Values with more
than two decimal places, duplicate participants, or nonmember payer/participant
IDs are rejected with `400 Bad Request`. Missing or inaccessible groups return
`404 Not Found`; missing or invalid authentication returns `401 Unauthorized`.
The equal-split request must not include `splits`, and exact requests must not
include `participantUserIds`.

List a group's expenses using `GET /api/groups/{groupId}/expenses`. Members
receive `200 OK`, newest expense first; a group with no expenses returns `[]`.
Nonmembers receive `404 Not Found`.

### Expense details, editing, and deletion

Any current group member can retrieve one expense. The expense must belong to
the group in the URL; inaccessible groups, missing expenses, and an expense
under a different group ID return `404 Not Found`:

```http
GET /api/groups/5/expenses/50
Authorization: Bearer <access-token>
```

The `200 OK` body uses the same `ExpenseResponse` shape as expense creation,
including the persisted shares.

Only the original creator can replace an expense's editable fields. Send the
same EXACT or EQUAL request format as expense creation:

```http
PUT /api/groups/5/expenses/50
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "description": "Updated Dinner",
  "amount": 1000.00,
  "paidByUserId": 25,
  "splitType": "EQUAL",
  "participantUserIds": [31, 12, 25]
}
```

A successful update returns `200 OK` with the existing expense ID and creator.
Validation and authorization happen before replacing shares; the expense
update, old-share removal, and new-share insertion run in one transaction.
Failed validation or persistence leaves the original expense and shares
unchanged. Other current group members receive `403 Forbidden` when editing;
nonmembers receive `404 Not Found`.

Only the original creator can physically delete an expense:

```http
DELETE /api/groups/5/expenses/50
Authorization: Bearer <access-token>
```

Success returns `204 No Content`; the expense's split records are removed in
the same transaction. Other group members receive `403 Forbidden`, while
nonmembers and already-deleted expenses receive `404 Not Found`.

Expense editing and deletion recalculate current balances. Recorded settlement
rows remain immutable; editing or deleting expenses does not reverse or rewrite
them, so changes that affect historical settlement context require a separately
designed audited correction workflow.

The MVP supports **INR only**. The API fixes the stored currency to INR and
rejects an optional supplied currency code unless it is exactly `INR`.
Multicurrency support will require explicit currency-aware balance handling.
The `expenses` and `expense_splits` tables are managed by Flyway migration V2;
Hibernate continues to validate rather than create schema. Java validates
monetary values with `BigDecimal` without rounding supplied amounts.

### Group balances

Any current member of a group can retrieve its current balances:

```http
GET /api/groups/5/balances
Authorization: Bearer <access-token>
```

The endpoint returns `200 OK`, includes every current member in ascending user
ID order, and calculates amounts dynamically from persisted expenses and
shares:

```json
{
  "groupId": 5,
  "currencyCode": "INR",
  "totalExpenseAmount": 1500.00,
  "balances": [
    {
      "userId": 12,
      "name": "Asha",
      "email": "asha@example.com",
      "totalPaid": 900.00,
      "totalShare": 400.00,
      "totalSentSettlements": 0.00,
      "totalReceivedSettlements": 0.00,
      "netBalance": 500.00
    },
    {
      "userId": 25,
      "name": "Riya",
      "email": "riya@example.com",
      "totalPaid": 600.00,
      "totalShare": 500.00,
      "totalSentSettlements": 0.00,
      "totalReceivedSettlements": 0.00,
      "netBalance": 100.00
    },
    {
      "userId": 31,
      "name": "Dev",
      "email": "dev@example.com",
      "totalPaid": 0.00,
      "totalShare": 600.00,
      "totalSentSettlements": 0.00,
      "totalReceivedSettlements": 0.00,
      "netBalance": -600.00
    }
  ]
}
```

`totalPaid` is the sum of expenses paid by the member; `totalShare` is the sum
of their allocated expense shares. `totalSentSettlements` and
`totalReceivedSettlements` summarize recorded payments. The balance formula
is `totalPaid - totalShare + totalSentSettlements -
totalReceivedSettlements`. A positive balance means the member should receive
money overall, a negative balance means they owe money overall, and zero means
they are balanced. An existing group with no expenses or settlements returns
all its members with zero balances. Expenses and settlements from other groups
are excluded, and inaccessible or nonexistent groups return `404 Not Found`;
missing or invalid authentication returns `401 Unauthorized`.

All amounts are `INR`; multicurrency conversion is not supported. Balances
show aggregate amounts. `totalExpenseAmount` counts expenses only; settlements
do not alter expense totals.

### Settlement records

Any current group member can record a payment they say they made to another
member:

```http
POST /api/groups/5/settlements
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "fromUserId": 31,
  "toUserId": 12,
  "amount": 200.00,
  "requestId": "8c59d562-690d-4c13-9ed1-20d432fb92aa",
  "note": "UPI repayment"
}
```

The authenticated user must match `fromUserId`, and both participants must be
group members. The sender must currently owe the recipient; the amount cannot
exceed either outstanding balance. A successful request returns `201 Created`.
Reusing a `requestId` within the same group returns `409 Conflict`. Amounts
must be positive INR values with at most two decimal places. Notes are optional
and limited to 200 characters.

`GET /api/groups/5/settlements` returns the group's settlement history, newest
first. Both settlement operations require authentication and current group
membership; inaccessible or nonexistent groups return `404 Not Found`.
Settlement rows are immutable through the API. This MVP records a member's
assertion of payment, not a confirmed bank transaction; it has no reversal,
dispute, or audit-correction workflow. Corrections require a separately
designed audited process.

Settlement creation and expense creation, editing, and deletion lock the same
group row. This serializes balance-changing operations within a group while
preserving their existing database transactions.

### Settlement suggestions

Any current group member can request read-only repayment suggestions:

```http
GET /api/groups/5/settlement-suggestions
Authorization: ******
```

The endpoint derives suggestions from the same current, settlement-aware net
balances returned by `GET /api/groups/{groupId}/balances`. Positive balances
are recipients and negative balances are senders. Each iteration matches the
largest remaining debtor with the largest remaining creditor; equal amounts
are ordered by ascending user ID. Suggestions contain user IDs and names,
positive INR amounts, and the total suggested amount. Calculations preserve
exact paise and do not write expenses, splits, settlements, or other records.

This deterministic greedy algorithm is practical but is not guaranteed to
produce the globally smallest number of transfers. Suggestions can become
stale after an expense or settlement changes; settlement recording always
revalidates current balances and rejects payments beyond the current debt or
receivable. Inaccessible or nonexistent groups return `404 Not Found`; missing
or invalid authentication returns `401 Unauthorized`.

## Development status

Steps 01–14 are implemented: registration, login, signed JWT authentication,
group and member management, transactionally persisted expenses with exact or
deterministic equal splitting, expense lifecycle management, and dynamically
calculated group balances. Step 15 settlement recording is implemented and
verified with automated H2 tests and the configured local MySQL 8.4.13 instance.
Step 16 settlement suggestions are implemented and verified with the automated
H2 suite and configured local MySQL 8.4.13 instance. The live verification
confirmed Flyway V1–V3 remained unchanged and removed its uniquely identified
disposable records.
Step 17 backend hardening adds allowlisted CORS, consistent safe JSON errors,
and shared per-group locking for expense and settlement writes. It is verified
with 67 H2 tests, a successful executable-JAR package, and bounded live
MySQL/API/CORS checks; the live check
confirmed Flyway V1–V3 remained unchanged and created no application records.
No concurrent production-load stress test has been run.
Step 18 establishes the responsive React UI foundation. Step 19 connects
registration, login, current-user verification, protected navigation, and
client-side logout to the existing auth endpoints. Step 20 connects group
listing, creation, authorized details, and creator-managed membership to the
existing group APIs. Step 21 connects expense listing, creation, details,
editing, and deletion. Step 22 connects group balances, server-generated
repayment suggestions, and self-reported settlement recording/history. The
dashboard's financial summary and activity remain placeholders.

## Exact monetary JSON contract

All monetary response fields are decimal JSON strings, including expense shares,
balances, suggested transfers and settlements. For example, amount is
"70368744177664.01". Clients must parse those strings using decimal or integer
minor-unit arithmetic. This response type change requires updating consumers that
previously expected JSON numbers. The bundled React client is updated together.
Request amounts retain BigDecimal binding and accept decimal strings (the UI
sends strings) and exact JSON decimal literals. New individual amounts must be
positive, have at most two fractional digits and fit DECIMAL(19,2), with maximum
99999999999999999.99 INR. Existing records remain readable across that range.
Aggregate balances can exceed the individual amount maximum. The browser uses
BigInt for paise arithmetic. Legacy numeric browser fixtures are accepted only
below 10^12 INR, where decimal text retains paise. Larger numeric responses are
rejected explicitly because precision may already have been lost.


## Expense creation operation keys

POST /api/groups/{groupId}/expenses requires Idempotency-Key containing one
quoted canonical UUID, for example "00000000-0000-4000-8000-000000000001".
This is an explicit creation-contract change. Existing API callers must add it.
The bundled UI, browser helpers and local audit request helpers send the header.
Generate a cryptographically random UUID for each intentional new expense and
reuse it for every retry of that operation. Missing or malformed keys return 400.

The operation is scoped to authenticated user and group. Successful creation and
the stored receipt commit in the same transaction, with database-enforced
uniqueness and the existing group lock. Concurrent identical calls serialize and
return the same 201 receipt. A key reused with different validated logical details
returns 409. Amount scale, default INR/EXACT, and participant order are normalized
for fingerprinting. Split mode, description, payer, total and resolved participant
shares are material. Membership and validation are checked before replay.

The original receipt is retained even if its expense is edited or deleted.
Replay confirms that creation occurred and does not restore or overwrite that
expense. Its ID may now refer to a changed or deleted resource. Keys have no
time expiry. Receipts remain for the lifetime of their group/account and are
removed only if that parent is deleted. No automatic retention cleanup is added.

The UI stores only an operation UUID in sessionStorage, scoped by user and group.
It survives same-tab reloads. Re-enter the same details to recover the original
receipt. Payloads are not stored. Logout/expiry clears operation IDs. Recovery
across logout, a closed tab, storage deletion or another device requires a caller
to retain the original key. Review expense history before starting a separate
expense. The explicit separate-expense action generates a new key on submission,
even if content is identical. A request with an uncertain result retains its key.

The header follows the structured-string format in the IETF draft. Conflict
status 409 is the application's documented policy for changed payloads. This
implementation waits on the database group lock for concurrent requests rather
than returning an in-progress receipt. Lock failures remain errors and are safe
to retry with the same key. Reference [IETF draft](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/).

## Authentication and request resource protection

This configuration supports one application instance. All attempts count, including
successful authentication. Denials do not extend the fixed window, and successful
authentication does not reset source or account budgets. There are no permanent
account locks. A caller waits for the current window to expire.

| Property | Default | Purpose |
| --- | --- | --- |
| app.security.limit-window-seconds | 60 | Independent fixed windows from first use |
| app.security.login-source-limit | 60 | Login requests per direct/trusted source |
| app.security.login-target-limit | 10 | Login requests per resolved account across sources |
| app.security.registration-source-limit | 10 | Registration requests per source |
| app.security.max-limiter-entries | 4096 | Total source and target windows |
| app.security.auth-body-bytes | 4096 | Login/registration body bytes |
| app.security.api-body-bytes | 65536 | Other API body bytes |
| app.security.max-query-chars | 2048 | Raw query length |
| app.security.max-query-parameters | 50 | Raw query parameter count |
| app.security.trusted-proxies | empty | Exact literal proxy IP addresses, comma separated |

The auth body allowance fits maximum name, email and password fields even with
JSON Unicode escapes. General request size accommodates at most 128 expense
participants/shares, 19-digit IDs and exact decimal amounts with substantial
encoding headroom. New create/update requests enforce 1–128 participants where
the relevant list is supplied. Existing records remain readable. These size
and cardinality limits also apply to retries of older requests. A rejected large
retry must not be turned into a fresh creation key automatically.

Bodies with a declared excessive length return 413 before body reads. Unknown
length and chunked bodies are read up to the selected cap plus one byte before
413. Allowed bodies are replayed as unchanged bytes to MVC. Jackson also limits
nesting to 32, strings to 4096 characters and numeric tokens to 64 characters.
These parser bounds are additional to DTO validation. Tomcat bounds total
request headers at 16 KB, form posts and discarded bodies at 64 KB, and parameter
count at 50. Its connection/read and keep-alive timeouts are 10 seconds, with
50 worker threads, 200 connections and an accept backlog of 50. JSON body
limits are enforced by the application guard, independently of form-post limits.

429 replies have generic JSON feedback and Retry-After seconds. The UI waits
for the user to retry and does not schedule authentication requests. Password
input is limited to the existing registration capability of 72 UTF-8 bytes.
Overlong login input is rejected before password hashing.

The limiter stores only bounded ephemeral source addresses, account IDs and
hashed nonexistent-account targets. After the bounded source check and DTO
validation, login resolves the account through its existing database lookup.
Existing accounts are keyed by user ID so MySQL-equivalent email aliases share
one budget. Nonexistent targets use the normalized email hash. Both receive the
same quota and generic denial response, before password verification.
Expired entries are reclaimed lazily on the next authentication request.
A full map rejects new keys with 429 rather than evicting active counters.
This trades temporary availability for bounded memory and protection from
counter-reset bypass. Repeated blocked requests never prolong an existing window.
An active attacker can still exhaust subsequent windows, and per-account
protection can temporarily affect a legitimate user's login.

By default all forwarding headers are ignored. Keep
server.forward-headers-strategy=NONE, which is enforced at startup. A future
reverse proxy must strip/replace untrusted client forwarding data and be listed
as an exact literal IP in app.security.trusted-proxies. The application walks
X-Forwarded-For from the socket peer right to left, stopping at the first
untrusted address. Malformed, repeated or oversized forwarding data falls back
to the socket peer. Forwarded and X-Real-IP are ignored. Unknown hosting proxy
ranges must not be configured as wildcard trust.

Counters reset on process restart and are not shared across replicas. The
account-target budget covers multiple source addresses reaching this instance.
A replicated deployment requires a reviewed shared limiter or an upstream
shared control before these thresholds can be claimed across the deployment.
MySQL is not used for per-attempt counter writes in this single-instance design.
Host-level protection is still needed against volumetric attacks and slow clients.

The isolated bulk backend fixture profiles explicitly use larger auth budgets.
Dedicated abuse tests override them with small limits and a controllable clock
tests expiration and concurrency. Run the focused real-browser auth checks with
scripts/run-final-release-e2e.ps1 -Suite auth. Bulk browser scenarios may require
explicit synthetic-workload quotas or separate runs, since their signup burst is
intentionally subject to the same configured limits.

Design references are the [OWASP authentication guidance](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)
and [OWASP anti-automation guidance](https://cheatsheetseries.owasp.org/cheatsheets/Bot_Management_and_Anti-Automation_Cheat_Sheet.html).

## Bounded group financial history

GET /api/groups/{groupId}/expenses and GET /api/groups/{groupId}/settlements now
return one page. Their JSON bodies remain arrays. The default is 25 records and
the maximum is 100. Callers must no longer assume that one array is the complete
history. The bundled UI reads the new metadata and offers manual Load More.

Use limit and before as canonical positive decimal integers. before is an
exclusive record-ID boundary and records are ordered by ID descending.
Unsupported parameters, repeated parameters, empty values, negative values,
malformed cursors and limits above 100 return 400. Offset, page and arbitrary
sort parameters are unsupported.

When another page exists, X-Next-Before contains the oldest returned record ID
as a decimal string. Send that string as before on the next request. The header
is exposed through CORS and absent on the last page. Keep cursors as strings to
avoid JavaScript integer conversion. There is no total-count query.

New records above an existing cursor appear on refresh, which starts again at
the newest page. Deletions do not invalidate numeric cursor boundaries. This is
a traversal of changing data, with no frozen snapshot across requests. Refresh
after concurrent edits or deletions to see the latest state. Load More preserves
loaded rows on failure, retries the same cursor and merges by record ID. Group
or account changes cancel stale requests and clear the displayed scope.

Expense history first selects at most limit+1 IDs in SQL, then fetches complete
expense details/splits for at most limit selected IDs. Collection fetch joins
are never paginated directly. Settlement history applies the SQL limit to its
root query with to-one fetches. Existing group/ID indexes support these seeks.

GET /api/groups/{groupId}/settlements?requestId=UUID returns at most one matching
repayment from the authorized group. It cannot be combined with before. The
UI uses this indexed lookup when reconciling an uncertain repayment, including
a receipt older than the first history page. The same group-authorization rules
apply, with no cross-group count or existence metadata.

Balances and suggestions continue to use full-ledger database aggregates and
the complete member roster. History pages are never used as authoritative
financial totals. Exact decimal strings, BigInt paise and the expense-creation
Idempotency-Key contract are preserved.

The maximum bounds history roots per request. Selected legacy expenses still
include their complete splits. Member rosters, group directories and aggregate
work over a complete ledger have separate scaling considerations. The UI can
grow its displayed history through deliberate Load More clicks. No automatic
infinite scrolling, new dependency, index or migration is introduced.

Run the isolated relevant browser journeys with
scripts/run-final-release-e2e.ps1 -Suite history. They use synthetic data and the
existing resource-ownership and cleanup checks.
