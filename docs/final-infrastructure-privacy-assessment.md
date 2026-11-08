# Final infrastructure and privacy assessment — 8 November 2026

**Production NO-GO.** This is the latest assessment, not approval to change production. Starting release-worktree HEAD was4e1acc5 (a later documentation descendant of reported c80b07a). Existing unrelated tracked/untracked work was preserved.

## Container feasibility: BLOCKED, not an executed build failure

Exactly one Docker Desktop read timed out after8s. No further Desktop attempt, restart/reset/prune, service change, WSL registration/configuration or volume change occurred.

Existing Ubuntu26.04 x86_64 uses the default root account. No Podman, Buildah, runc/crun, newuidmap or slirp4netns executable/package was found. No existing container-storage directory was found in the inspected standard Ubuntu paths; Docker Desktop's separate distribution/data was not inspected or modified. About6.7GiB memory available and28GiB physical C: space; the WSL virtual filesystem's much larger free-space figure is not physical capacity. SplitSmart Linux preparation directory is0700, cgroupv2 present. Existing Windows/storage encryption evidence was reused without changing it.

A harmless `runuser -u nobody -- unshare -Ur /bin/true` PASS proves this tested unprivileged namespace operation works; it does not establish a container engine. No nonroot login account, subordinate UID/GID entries, or mapping helpers are present. Do not repurpose shared nobody for a persistent container identity. Official rootless Podman uses an unprivileged account, subordinate IDs and mapping helpers. Package installation alone cannot supply a complete isolated setup here. Creating an account/mappings and installing privileged mapping helpers is a separate security scope from package-only installation under the owner's no-privilege-escalation boundary. No packages/account/mappings/helpers were installed or altered. This is a deliberate boundary, not proof that rootless containers are impossible.

Single-UID/ignore-chown fallback is not accepted as proof of faithful production ownership/user behavior. Rootful or privileged-container workarounds were not used. No actual OCI image build/start, UID10001, /app layout or containerized TLS/Flyway/health test was executed. Dockerfile/static configuration review is not a container PASS.

Safest proposed next scope (requires explicit owner approval): a dedicated locked, non-sudo Ubuntu-only verification account and private0700 home; a checked non-overlapping subordinate UID/GID range; official Ubuntu Podman/uidmap/network/storage helper packages after a no-remove/no-upgrade dependency simulation; no Windows/Docker Desktop changes, no disabling security controls. Run daemonless under that account with isolated storage, no Docker socket mount, no privileged mode, loopback-only published ephemeral port, restricted secrets outside image layers and synthetic-only DB. First verify ownership/isolation, then build the existing Dockerfile and check actual Java21/nonroot/TLS/Flyway/health/maintenance. Preserve owned fixtures; stop on unexpected package/service/security change. No installation is being requested as part of a production release approval.

## Privacy feasibility and alternatives

Existing synthetic22P02 primary-error evidence remains decisive: PostgreSQL may echo an input value in its own primary message. TERSE removes DETAIL/HINT/QUERY/CONTEXT, not primary text. Parameterization, local validation, role/network/TLS restrictions and log-access controls reduce exposure but do not universally redact errors. Provider copies and framework logs are separate paths. No new deliberate failure probe or logging configuration experiment was repeated.

Aiven documents a no-card Free plan, service-log retention4days, and Free restrictions including no support services, no VPC/integrations/fork-to-Free and no SLA. Its exposed verbosity/service_log controls do not establish pre-persistence primary-message redaction across provider copies. The authenticated Basic-only support form observed earlier remains unsuitable for claiming Free technical support; documentation-based help is the current route. No new ticket/chat attempt or paid support was made; existing approved draft retained.

A — Existing Aiven with explicit residual-risk acceptance: practical continuity using the prepared target, verified TLS/restricted roles and local integrity evidence. It still exposes unexpected sensitive error text to authorized provider/operator log access. Explicit acceptance would be a policy change and still would not authorize import/deployment. Free availability/cold-start/shared-egress risks and actual container/import gates remain. No acceptance was made.

B — A provider with demonstrably stronger protection: meets the current desired requirement only after documented controls and synthetic verification cover the required scope. None was established among reviewed zero-cost options. Neon offers official no-card Free usage and verified TLS capability, but reviewed security/plan docs do not demonstrate primary-error redaction. Free compute/storage/idle quotas and network/ICU validation add work; provisioning is not approved. Supabase advertises $0/500MB, pauses inactive Free projects after1week and offers community support; reviewed logging docs do not establish universal primary-message redaction or complete card/billing eligibility here. It is not certified as meeting every owner constraint. No account/service was created and no records were submitted. TLS/encryption at rest are not log redaction.

C — Defer a NEW PostgreSQL cloud real-data copy: **recommended immediate operating posture**, consistent with the current strict protection gate. This does not move existing MySQL production off Aiven or eliminate its existing provider exposure. Retain current source and encrypted recovery material; local rehearsals are proven, not a public production hosting solution. Ongoing writes make old backups stale. Owner-verified MySQL retirement21 December2026 requires a dated preservation/availability decision; delay cannot promise uninterrupted service. Another export/restore/freeze still needs separate approval.

Recommendation C now while retaining B as the required protection gate. Do not silently reinterpret postponement as accepting downtime or deleting the old service. Owner must decide whether to continue that hold, obtain verifiable B evidence, or explicitly change policy to A. No option was accepted on the owner's behalf.

## Safe engineering review

No backend/frontend source change or new concrete product defect identified. Existing local real-data count/hash/financial/constraint/sequence PASS and synthetic adapter evidence retained; no rehearsals or unaffected suites rerun. Reviewed adapter gate precedes connections/ledger, uses explicit parameterized values, transactional locks/reconciliation and records COMMIT_UNKNOWN/COMMITTED_UNVERIFIED/ROLLBACK_UNCONFIRMED without replay. Real execution is intentionally disabled, not a deployment-ready adapter.

Reviewed Dockerfile: Java21 builder/runtime, narrow source-only build context, explicit USER10001:10001; actual image remains unexecuted. Entry point requires explicit PostgreSQL profiles, creates a restricted CA file and removes base64 inputs; invalid/conflicting configuration fails. PostgreSQL runtime and Flyway use independent credentials with verify-full, clean disabled and no automatic baseline. Keep JWT_SECRET unchanged. Backend recovery email requires both flags, default false; frontend actions require explicit true. Existing prepared frontend has email disabled; no live settings changed. No credential values inspected for this review.

New checks this continuation: bounded engine inventory, physical storage/memory inspection and unprivileged namespace probe PASS; Docker timed out. No new application/integration tests were warranted by documentation-only changes. Retained evidence: Linux-native Spring Boot/TLS/current Flyway/health/maintenance PASS;6 Linux Java tests +4 destination-guard tests; earlier42 backend/20 frontend/4 browser and adapter/schema/status20 results. Do not add overlapping platform reruns into a misleading total.

## Controlled migration and recovery remains plan-only

Before any window: resolve privacy policy/control, execute actual container checks, review an approval-bound real adapter, approve exact code SHA/private push or integration, reverify Render regional egress and administrator IP, approve additive allowlist/TLS-role configuration. Last reviewed Render shared ranges74.220.52.0/24 and74.220.60.0/24 are proposals, not applied or permanent guarantees. Restricted credentials and hostname validation remain mandatory. Render tracks a different branch; do not assume a release-branch push deploys it. Free Render cold starts and non-HA PostgreSQL can interrupt availability; no paid maintenance mode/fallback.

Window only after separate explicit authorization: deploy gated backend, verify all MySQL writers drained/stopped, capture one NEW consistent encrypted snapshot, one isolated restore and reconciliation, then one authorized data-only import into exact empty V11 target with archived V7 history, canonical hashes/financial/constraints/sequences checks and encrypted target backup. Coordinate backend/frontend deployment with email off, synthetic smoke tests and a separate final GO before opening writes. Preserve old MySQL and encrypted backups.

Before target application writes an abort requires proving no target auth/financial mutation and separate recovery approval. After any such mutation: gate traffic and forward-fix with version-compatible auth/data reconciliation; never revive V7/stale MySQL or older token versions. No production operation was executed. Detailed checkpoints remain cloud-final-migration-window.md and AUTONOMOUS-RELEASE-APPROVAL.md.

Sources reviewed:
- https://docs.podman.io/en/latest/markdown/podman.1.html
- https://github.com/podman-container-tools/podman/blob/main/docs/tutorials/rootless_tutorial.md
- https://podman.io/docs/installation
- https://www.postgresql.org/docs/16/runtime-config-logging.html
- https://aiven.io/docs/products/postgresql/concepts/pg-free-tier
- https://aiven.io/docs/products/postgresql/reference/advanced-params
- https://aiven.io/docs/platform/howto/list-monitoring
- https://neon.com/docs/introduction/plans
- https://neon.com/docs/security/security-overview
- https://neon.com/blog/making-pricing-more-predictable
- https://supabase.com/pricing
- https://supabase.com/docs/guides/observability/configure-logging
