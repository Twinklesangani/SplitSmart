# Aiven primary-error privacy inquiry — prepared, NOT submitted

8 November 2026. Owner approved one narrowly scoped technical inquiry. No records, credentials, project/service identifiers or private logs are included below.

## Submission check

Authenticated Console → menu → Support opened the official Support Center. Create ticket exposes a required Support Tier selector offering only Basic. Official support terms describe Basic as included in paid subscriptions; PostgreSQL Free documentation excludes support services. No confirmed Free inquiry route was available through this form. No tier was falsely selected, ticket submitted, paid support activated or billing changed. No reference exists. Existing trial banner was not used as a paid fallback.

## Draft

Subject: PostgreSQL Free — primary-error value redaction and privacy controls

Hello Aiven,

Please clarify whether PostgreSQL Free offers a supported, enforceable way to prevent sensitive input values in PostgreSQL primary error messages from entering provider-managed logs.

Synthetic example only: a parameterized integer conversion of the fake string `SYNTHETIC_ONLY_NOT_A_NUMBER` can fail with SQLSTATE 22P02 and include that string in the primary error. TERSE, disabled statement logging and zero parameter-length logging do not establish primary-message redaction.

1. Can primary-error input values be redacted before log persistence, and is that control supported on Free?
2. Which relevant server/session controls may Free customers use, without disabling necessary security auditing?
3. If redaction is unavailable, what provider/operator access restrictions, retention, internal-copy/deletion and privacy guarantees apply? Does disabling service_log affect only API/Console visibility?
4. Is there an officially supported sensitive-data migration and ongoing application error-handling procedure that addresses this exposure?

Please answer for the Free plan without an upgrade or payment method. No customer records or private logs are attached. Thank you.

## Next boundary

Owner may submit this draft through an officially confirmed no-cost channel, or authorize a specific such channel if available. Do not post account details publicly or treat a support reply as permission to upload data. Keep real-data import blocked until a supported control is documented and verified with synthetic inputs.

Sources:
- https://aiven.io/docs/platform/howto/support
- https://aiven.io/support-services
- https://aiven.io/docs/products/postgresql/concepts/pg-free-tier
