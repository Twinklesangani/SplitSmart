import { defineConfig } from '@playwright/test'

// Mocked-API browser checks: localhost only, never production users or databases.
export default defineConfig({
  testDir: './e2e',
  testMatch: 'password-recovery.browser.js',
  outputDir: './test-results/recovery',
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:15174',
    browserName: 'chromium',
    channel: 'msedge',
    headless: true,
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 15174 --strictPort',
    url: 'http://127.0.0.1:15174',
    reuseExistingServer: true,
  },
})
