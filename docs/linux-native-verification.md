# Linux-native verification — 8 November 2026

Result: **LINUX-NATIVE PASS; CONTAINER BLOCKED; production NO-GO.**

## Environment and installation

Existing Ubuntu WSL2 x86_64; isolated directory `/root/.local/share/splitsmart/java21`, mode-restricted. Official Eclipse Temurin JDK21.0.13+9 downloaded from adoptium/temurin21-binaries GitHub release after API403. Published SHA256 matched archive `d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c`. Both java/javac report21.0.13. No global PATH/package-manager/system configuration changed. Existing encrypted Windows/WSL storage used; no recovery database or backup changed.

## Executed checks

`python scripts/verify_linux_native.py` launched Linux Java using the existing release JAR (SHA256 `a2011578a7784b1fa7833720271a0dace75f0052e9551c3aea12dc054758abb5`). Before launch, named Free target endpoint and test nonce/database identity were checked using existing restricted roles. No real-data source or application database was used. Environment/credentials crossed stdin and process environment only, never command arguments or plaintext files.

Actual Linux JDBC verified provider CA/hostname (`verify-full`), synthetic DB/user identity and encrypted backend session; negative missing-CA connection rejected. Spring profiles `postgresql,postgres-prod`, separate runtime/Flyway roles, Hibernate validation and Flyway V11 current/no pending migration passed. Readiness (including DB), liveness and maintenance gate passed. Both recovery-email flags false. Maintenance enabled to prevent business/scheduled writes. Owned backend stopped; loopback listener gone. Successful output scanned against credentials in bounded RAM, no plaintext logs. Encrypted synthetic failure diagnostics only retained privately. Early harness attempts hit a socket/TIME_WAIT check defect; dynamic port selection and actual listener check fixed it, successful execution receipt retained.

Receipt: `/root/.local/share/splitsmart/native-verification/result.json`. The verifier refuses replay after success. Destination builder was subsequently hardened to ignore saved URLs, reject application/recovery DBs and parameter injection;4 focused guard tests PASS, and protected local comparison confirmed it builds exactly the executed endpoint. Python isolated mode ignores inherited Python startup settings. No further backend startup was repeated just for these guard changes.

Linux focused Java tests ran in `/root/.local/share/splitsmart/backend-unit-verification`, a new source-only copy with existing Maven3.9.9 and sanitized environment/no DB credentials. Selected `-Dtest=PostgresProfileTest,MaintenanceGateTest test`:6 PASS,0 failures/errors/skips, BUILD SUCCESS. Offline cache-origin resolution failed initially; official Maven Central resolution completed successfully. Existing broad42 Java/20 frontend/4 browser evidence was not rerun. After completion no Java test processes remained.

## Limits and next boundary

Single8s Docker availability check timed out; no restart/reset/prune/reinstall/WSL integration edit. No Linux image executed. Nonroot UID10001, image build, `/app` layout/entrypoint and actual Render behavior remain unverified; Linux-native root startup is not a substitute. A separately available healthy container environment is needed, not another unchanged timeout loop.

Provider primary-error protection remains unresolved; approved inquiry could not be submitted via Basic-only support form. See aiven-primary-error-inquiry.md. Keep policy B/no-upload gate and email disabled. No production import/freeze/write/deployment/push/merge permitted.

Installation reference: https://adoptium.net/installation/archives
