import { defineConfig } from '@playwright/test'

function isolatedUrl(name, port) {
  const value = process.env[name]
  if (!value) throw new Error(`${name} must be set by the isolated E2E runner.`)

  const url = new URL(value)
  if (
    url.protocol !== 'http:'
    || url.hostname !== '127.0.0.1'
    || url.port !== String(port)
    || url.pathname !== '/'
    || url.search
    || url.hash
  ) {
    throw new Error(`${name} must point to the isolated 127.0.0.1:${port} test service.`)
  }
  return url.origin
}

const baseURL = isolatedUrl('SPLITSMART_E2E_BASE_URL', 15173)
isolatedUrl('SPLITSMART_E2E_API_URL', 18081)

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.js',
  outputDir: './test-results/final-release-e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: './test-results/final-release-report', open: 'never' }],
  ],
  use: {
    baseURL,
    browserName: 'chromium',
    channel: 'msedge',
    headless: true,
    viewport: { width: 1280, height: 900 },
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
})
