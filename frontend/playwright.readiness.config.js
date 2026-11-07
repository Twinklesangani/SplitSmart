import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  testMatch: 'release-readiness.browser.js',
  outputDir: './test-results/readiness',
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:15176',
    browserName: 'chromium', channel: 'msedge', headless: true,
    trace: 'off', screenshot: 'off', video: 'off',
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 15176 --strictPort',
    url: 'http://127.0.0.1:15176',
    reuseExistingServer: false,
    env: { VITE_API_BASE_URL: 'http://127.0.0.1:15176', VITE_EMAIL_ACTIONS_ENABLED: 'false' },
  },
})
