export const MARKER = 'synthetic-v1'
export const CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'none'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'; object-src 'none'"
export function validateEnvironment(env) {
  if (env.SPLITSMART_DEMO_ONLY !== MARKER) throw new Error('DEMO_EXPLICIT_OPT_IN_REQUIRED')
  const forbidden = Object.keys(env).some(key => /^(DB_|DATABASE_|JDBC_|PGHOST$|PGPORT$|PGUSER$|PGPASSWORD$|PGDATABASE$|MYSQL_|FLYWAY_|SPRING_|JWT_|BREVO_|RECOVERY_)/.test(key) && env[key]
    || key === 'VITE_API_BASE_URL' && env[key] || key === 'VITE_EMAIL_ACTIONS_ENABLED' && env[key] !== 'false' && env[key])
  if (forbidden) throw new Error('DEMO_DATABASE_API_OR_EMAIL_CONFIG_FORBIDDEN')
}
