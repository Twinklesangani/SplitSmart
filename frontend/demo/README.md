# Offline fictional portfolio

Run from frontend in a clean terminal with no database/JWT/mail/API environment. No .env files are loaded by the demo build. Normal production build/entry remains separate.

PowerShell:

```powershell
$env:SPLITSMART_DEMO_ONLY='synthetic-v1'
npm run build:demo
npm run serve:demo
```

Open http://127.0.0.1:18086. Refresh resets RAM; Reset this demo requires a selected fictional profile and confirmation. No real account or password, seed SQL, reset endpoint, email, storage or database exists. Do not use production secrets or serve the normal dist directory as this demo.

Focused checks: `node --test demo/server.test.mjs`; `node node_modules/vitest/vitest.mjs run src/test/syntheticDemo.test.jsx`; `node node_modules/@playwright/test/cli.js test --config=playwright.demo.config.js`. Existing Edge browser is used, no browser download. Full actual-container verification receipt already exists; do not blindly rerun its one-attempt controller or reset existing resources.

Dockerfile.demo is the standalone static-server image (Node24, UID10001). It is not the Java backend image. No database/Flyway migration is needed. Deployment instructions, exact approval, Free limitations and rollback are in SYNTHETIC-PORTFOLIO-APPROVAL.md at repository root. Local readiness is not permission to publish.
