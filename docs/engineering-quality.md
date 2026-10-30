# Engineering quality and limitations

SplitSmart is a Java 21/Spring Boot and React expense-sharing application. Settlements
record repayments already made; they do not transfer money.

Latest verified local baselines: **83 backend tests, 10 real MySQL integration tests,
76 frontend tests and 6 Microsoft Edge browser scenarios**. These are historical
executions, not claims that hosted CI has passed. Browser checks covered authentication,
account isolation, groups, EQUAL/EXACT expenses, financial reconciliation, and responsive
layouts at 375, 768, 1024 and 1440 pixels.

| Tool | Evidence and limits |
|---|---|
| SpotBugs | Executed; remaining warnings were classified with documented accepted limitations, not blanket false positives |
| Testcontainers | Real disposable MySQL migration, transaction and concurrency checks passed |
| Schemathesis | Bounded isolated contract tests completed; documented domain/contract and TRACE mismatches remain, so no unconditional fuzzing sign-off |
| k6 | Predeclared bounded local latency/error targets met; no production-capacity claim |
| OpenTelemetry | HTTP/JDBC trace relationships inspected; single-pass overhead comparison inconclusive |
| Playwright | Six real Edge scenarios passed, including authorization and persisted financial correctness |
| SonarQube | Pending due to local disk capacity; not executed |
| PR-Agent | Pending a reviewed GitHub workflow, provider budget and meaningful pull-request diff |

Database runtime permissions are limited to SELECT/INSERT/UPDATE/DELETE. The former
exposed account's schema access was revoked and fresh access denial verified. JWT
runtime checks verified issued signatures and invalid-token rejection. Private local
operational reports, credentials, recovery files and raw logs are excluded from publication.

The fixed H2 test-profile signing fixture is synthetic and differs from the live
runtime key. `.gitleaks.toml` extends default rules and permits only that exact line
in that exact file; production authentication and all other scanning remain enabled.
Never deploy using the test profile or reuse its fixture.

CI is a local draft until run on GitHub. MySQL integration is a manual option pending
hosted Docker validation. Production deployment additionally needs HTTPS, managed
secrets, migration credentials separate from runtime, backups/recovery, monitoring,
and review of JWT/sessionStorage and token-revocation limitations documented in README.
No deployment or external AI review is complete.
