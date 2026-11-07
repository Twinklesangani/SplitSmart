#!/bin/sh
set -eu
umask 077
# Default matches the existing MySQL path. Directory override is useful for
# isolated startup tests; deployments leave it unset.
tls_dir="${SPLITSMART_TLS_DIRECTORY:-/tmp}"
[ -d "$tls_dir" ] || { echo 'TLS directory unavailable' >&2; exit 1; }
profiles=",${SPRING_PROFILES_ACTIVE:-prod},"
case "$profiles" in
  *,postgresql,*)
    case "$profiles" in *,prod,*) echo 'Conflicting database profiles' >&2; exit 1;; esac
    case "$profiles" in *,postgres-prod,*) ;; *) echo 'PostgreSQL production profile required' >&2; exit 1;; esac
    # Validate before the pipeline: POSIX sh reports only its LAST stage status.
    : "${DB_TLS_CA_BASE64:?Required PostgreSQL CA}"
    printf %s "$DB_TLS_CA_BASE64" | base64 -d > "$tls_dir/aiven-postgres-ca.pem"
    [ -s "$tls_dir/aiven-postgres-ca.pem" ] || exit 1
    export DB_TLS_CA_FILE="$tls_dir/aiven-postgres-ca.pem"
    ;;
  *,postgres-prod,*)
    echo 'PostgreSQL driver profile required' >&2; exit 1
    ;;
  *)
    : "${DB_TLS_TRUST_STORE_BASE64:?Required TLS truststore}"
    printf %s "$DB_TLS_TRUST_STORE_BASE64" | base64 -d > "$tls_dir/aiven-trust.p12"
    [ -s "$tls_dir/aiven-trust.p12" ] || exit 1
    ;;
esac
unset DB_TLS_CA_BASE64 DB_TLS_TRUST_STORE_BASE64
exec java -jar /app/app.jar --server.port="${PORT:-8080}"
