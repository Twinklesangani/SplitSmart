# Rootless OCI verification — 8 November 2026

**Rootless engine PASS. Actual production image build PASS. Four synthetic application-container scenarios PASS. Actual Render deployment NOT executed. Real-data production release remains NO-GO, privacy Option C selected.**

## Authorized setup and exact local scope

Recovered release worktree `audit-output/m1a`, branch `codex/password-recovery-backend`, starting HEADcc520229aedfbcbdf56d6faf396659c78da9c18d. Preserved root/active unrelated edits and all recovery material. Did not repeat Docker Desktop or prior namespace/Linux-native milestones.

Ubuntu26.04 x86_64/ext4 had adequate memory and28GiB physical Windows storage at precheck. Refreshed existing signed official Ubuntu indexes (`apt-get update` with bounded network timeouts); did not add repositories or upgrade the distribution. Simulated `apt-get --no-install-recommends --no-upgrade install podman uidmap slirp4netns fuse-overlayfs`:0 upgrades/removals,13 new packages. Installed exactly that transaction. A Windows output-encoding error occurred after installation; inspected dpkg state/audit instead of repeating it. All requested packages installed, dpkg audit clean.

Verified versions: Podman5.7.0, crun1.21, conmon2.1.13, slirp4netns1.3.3, fuse-overlayfs1.14, uidmap4.17.4. A locked account `ss_oci_verify` UID/GID2001 was created only after checking name/ID/home absence. It has nologin shell, no sudo group and0700 home. Initial subordinate mapping files were empty; assigned UID/GID100000–165535, no unrelated mapping changed. Commands: groupadd, useradd with automatic range allocation disabled, then `usermod --add-subuids 100000-165535 --add-subgids 100000-165535`. Created private `/run/user/2001` and account-specific container config. Corrected ownership only on the newly created `.config` parent. No password, SSH access, lingering service, engine API listener or Windows identity was configured.

`runuser -u ss_oci_verify -- env -i HOME=... XDG_RUNTIME_DIR=/run/user/2001 PATH=/usr/bin:/bin podman info` reported rootless=true, overlay, crun. Account config uses cgroupfs/file events, no daemon service. WSL reported non-shared-root/session-bus warnings. No mount propagation/systemd/security setting was changed; runtime tests used `--cgroups=disabled` and Java heap cap256MiB. Kernel resource limits therefore are not equivalent to Render cgroups.

A real Alpine3.23 OCI container ran as10001:10001 with networking off, read-only root, no capabilities and no-new-privileges; its UID/GID assertions PASS. Test-only containers/images retained; no reset/prune or existing Docker/WSL resource touched.

## Reproduced compatibility fix and actual image

Initial production Dockerfile build failed because Podman refuses ambiguous registry short names. Smallest fix: qualify the two existing official base images as `docker.io/library/maven:3.9-eclipse-temurin-21` and `docker.io/library/eclipse-temurin:21-jre`. No other application behavior changed.

Copied only Dockerfile, .dockerignore, entrypoint, pom.xml and src/main into a new private account-owned context; no Git directory, credentials, exports, unrelated files or real records. Enforced entrypoint LF as Git already requires. Executed actual multi-stage `podman build --network=slirp4netns --format=docker` with the production Dockerfile. Maven compiled91 Java source files/release21 and packaged successfully; production Dockerfile intentionally skips tests, so this is not a unit-test claim.

Image tag: `localhost/splitsmart-verify:qualified-20261108`; image ID `sha256:9d9db0ab4fc478fa22c92a55bf75da66b06dbfe93fbdb63f5e2a2465ff101e28`. Runtime inspected/pinned that ID and verified Config.User10001:10001 and no test secrets baked into image metadata. Base tags remain mutable: a later build must be verified again; this is not a guarantee that a future remote build has identical base layers.

## Actual application-container verification

Controller `scripts/run_rootless_verification.py` first pins existing named Free PostgreSQL endpoint, checks the synthetic test database nonce and exact Flyway V1–V11 checksums through its restricted test migration role. It never connects to MySQL or accepts a real-data source/application database. Runtime URL is constructed from pinned endpoint and guarded synthetic DB name; existing test roles only, plus fresh disposable JWT secret. Both recovery-email flags false.

Public verifier code is copied into the dedicated account's0700 tools directory; credentials travel separately as private stdin JSON. This avoids WSL interpreting shell expressions in a Python `-c` source argument. Podman receives temporary secret references, not password/JWT argument values or image build arguments. Secret store stays under the private encrypted local environment; temporary secrets removed after owned containers stop. Existing DPAPI role credentials were not reset.

Four actual scenarios PASS:

- Maintenance-enabled backend: Java21, runtime and Flyway TLS verify-full, current V11 startup, DB-inclusive readiness/liveness UP, all API access503.
- Maintenance-disabled backend: health UP, anonymous protected API401, forgot-password with fake-only address503 because email delivery is disabled. No delivery attempted.
- Invalid CA: foreground startup failed with SSL/certificate diagnostics, using otherwise identical synthetic setup. Output remained in RAM and was checked for credential leakage; no file log.
- Wrong hostname: a fake `.invalid` alias mapped to the pinned target IP; startup failed with PostgreSQL JDBC hostname-verification diagnostics. No DNS/SQL logging experiment or bypass of certificate checks.

Positive containers used no application log driver; negative cases used foreground passthrough captured in memory only. Containers had read-only roots, private tmpfs, cap-drop=all, NoNewPrivs1, UID/GID10001, CA file0600 and no CA-base64 variable in Java PID1 environment. Only ephemeral127.0.0.1 ports were published. Application image namespaces contained no host backup/keyring/database mounts or engine socket. No actual Render service was started/deployed.

The new harness initially failed on a NUL escape and then on shell expansion in WSL source transport; actual image identity diagnostics proved correct. Fixed harness transport/diagnostics, retained attempts, stopped only owned fixtures and removed each attempt's temporary secrets. The final successful attempt is `ssverify-20261108-oci4`; do not blindly replay it. Receipt at `/home/ss_oci_verify/ssverify-20261108-oci4/result.json` and private Windows AivenPostgresPreparation/rootless-check-result.json. Final direct checks confirmed0 running containers and0 temporary secrets in this dedicated account. Stopped containers/images remain for inspection. Fresh synthetic nonce/history metadata check after tests confirmed all V1–V11 checksums still exact.

Ten new focused guard/transport unit tests PASS (image-ID pinning, nonroot/loopback/capability restrictions, no password env arguments, secret ownership, log handling, fake-host scope, native argument encoding and file-based dedicated-user launcher). Prior backend/frontend/browser/data-rehearsal suites were unaffected and not repeated.

## Release distinction and next operation

OCI-compatible verification is now PASS. Actual Render kernel/cgroup/build/deployment/health behavior is NOT verified, because deployment is unapproved. Keep separate runtime/Flyway credentials and provider CA/hostname validation. Docker Desktop remains previously unavailable and was not modified. No real-data cloud import, production freeze/write/export, push/merge, billing or deployment occurred. Encrypted backups, GPG keys and all recovery databases preserved.

Latest owner direction is Option C: no real production financial records uploaded to PostgreSQL cloud. Stronger provider error protection remains desired; no new logging probe or support attempt. A separate synthetic-only portfolio deployment is technically plausible but needs a reviewed design that prevents visitors adding real personal/financial information, dedicated fake fixtures/roles and a separate approval. Current production app is not automatically a safe demo merely by seeding fake rows. Do not attach a public demo to recovery or application data. See synthetic-portfolio-release-plan.md.

No rootless setup permission needs repeating. Next owner decision concerns a separate local synthetic-demo implementation, continued hold, or later separately scoped real-data release policy; no decision here authorizes a deployment.

## Resume without replay

Do not rerun account creation, package installation, image build or successful fixed attempt just to prove a receipt exists. Read current receipts and actual Git state first. `/run/user/2001` may disappear on a later WSL lifecycle; if absent, recreate only that approved private directory with the verified existing UID/GID, rather than creating another account/range or restarting Windows. Never replace existing different ownership/configuration. Future image rebuild needs a new reviewed tag/source fingerprint; old moving-base tags do not certify new artifacts. Existing successful controller attempt intentionally refuses replay via exclusive result-directory creation. All old diagnostic attempts are synthetic, stopped and retained.

Post-build physical C: free storage freshly measured18.9GiB; no automatic image/cache cleanup was performed.
