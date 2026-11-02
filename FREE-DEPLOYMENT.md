# Portfolio demo: no-card deployment checklist

Use synthetic records only. Keep GitHub private. Use provider subdomains, no card on file, no trial, paid workspace, Functions, disk, or paid add-on. Stop if any console requests a payment method. Disable automatic preview deployments and builds on unrelated branches.

## Render and MySQL

Select a Free web service on a Hobby workspace, Docker runtime, root directory backend, Dockerfile ./Dockerfile, health path /actuator/health/readiness. The image runs a Java 21 JRE as a non-root user, activates prod, and reads Render's PORT. Do not pass secrets as Docker build arguments. Use runtime settings only.

Create one Aiven Free MySQL service, not a trial. Check engine version against tested MySQL 8.4, native InnoDB constraints, primary keys required, and ability to grant separate identities access to a single empty schema. No global privileges required. Restrict allowed source IP ranges to Render's published outbound ranges for the selected service where supported. Use the service DNS hostname and assigned port, not an IP or localhost. The two credential-free JDBC URLs must point to the same schema. Grant application SELECT, INSERT, UPDATE, DELETE; grant the separate migrator schema-scoped migration privileges. Never use the admin login in the application.

Render runtime environment names:

- DB_URL, DB_USERNAME, DB_PASSWORD
- FLYWAY_DB_URL, FLYWAY_DB_USERNAME, FLYWAY_DB_PASSWORD
- DB_TLS_TRUST_STORE_URL, DB_TLS_TRUST_STORE_PASSWORD
- JWT_SECRET: independently generated Base64-encoded key of at least 32 random bytes before encoding.
- APP_CORS_ALLOWED_ORIGINS: exact HTTPS Pages production origin; no wildcard or preview domains.

Download the Aiven project CA through the authenticated console. On a trusted machine, import it with keytool -importcert into a new PKCS12 truststore; enter its password interactively. Base64-encode the truststore **offline**, put the encoded contents in a Render secret file named trust.p12.b64, and use the following Docker Command override. Set the truststore URL to the file URI for /tmp/aiven-trust.p12. The production profile configures VERIFY_IDENTITY for both JDBC clients. Do not enable shell tracing or print environment variables.

    sh -ec 'umask 077; base64 -d /etc/secrets/trust.p12.b64 > /tmp/aiven-trust.p12; exec java -jar /app/app.jar --server.port="${PORT:-8080}"'

Keep application rate limiting and forwarded-header defaults. Clients behind the same untrusted proxy can share an authentication quota; do not trust arbitrary forwarded IPs to avoid this. Recheck CA and outbound address changes. Verify readiness and that the real hostname validates before allowing demo traffic.

## Cloudflare Pages

Grant the GitHub App access only to SplitSmart. Static Pages Free, root frontend, Node 24 (NODE_VERSION), npm run build, output dist. VITE_API_BASE_URL must be the public HTTPS Render origin at build time; it is visible to every browser. Never put tokens, passwords, JDBC or internal URLs into Pages variables. After setup, open a nested React route directly, check an authorized-origin browser request and preflight, try an unlisted origin, and use synthetic accounts for an authentication and expense smoke test.

## Free-service limitations

Render sleeps after 15 idle minutes; its 750 monthly hours are shared. Cold starts include JVM initialization and database access. Without a payment method bandwidth exhaustion suspends service and exhausted build minutes stop builds. Unusually high outbound traffic to an external DB can also cause suspension. Storage is ephemeral. Aiven provides one node with 1 GB RAM and 1 GB storage, 76 connections and backups, without HA or an SLA; retention and restore options must be checked before relying on them. Aiven may power off inactive services. Do not keep either service awake artificially. Pages Free permits 500 builds/month, one concurrent build, and 20 minutes per build. Use disposable demo data throughout.

Before creating Aiven users, check the [service-user defaults](https://aiven.io/docs/products/mysql/howto/manage-service-users): new service users can be admin-level. Start both with an empty mysql_grants list and grant only the intended schema privileges separately. Inspect SHOW GRANTS before providing either login to Render.

Provider references: [Render Free limits](https://render.com/docs/free), [Render instance sizes](https://render.com/pricing), [Aiven MySQL Free](https://aiven.io/docs/products/mysql/concepts/mysql-free-tier), [Pages limits](https://developers.cloudflare.com/pages/platform/limits/), [Pages Git integration](https://developers.cloudflare.com/pages/configuration/git-integration/).

## Local startup evidence

For the demo, set Render runtime JAVA_TOOL_OPTIONS to the single JVM option -XX:TieredStopAtLevel=1. This is a startup-oriented compiler setting; leave heap sizing at the Java 21 container default. A local run under a 512,000,000-byte hard memory limit, no extra swap, and 0.1 CPU reached database-backed HTTP readiness after 182 seconds. Cgroup peak was 254,418,944 bytes. All four original migrations succeeded on empty TLS-only MySQL 8.4.13 with required primary keys. The first capped default-JVM run failed during JDBC metadata initialization; a one-CPU control passed in 30 seconds. These are individual startup observations, not a load test or a cold-start guarantee. Check the first real deploy and one resume from idle before sharing the public URL.
