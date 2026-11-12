import { chromium } from '@playwright/test'
import { randomBytes } from 'node:crypto'
let browser
try {
  if (!process.env.SS_RECOVERY_TOKEN || !process.env.SS_RECOVERY_OLD_PASSWORD || !process.env.SS_RECOVERY_OLD_JWT) throw Error()
  const token = process.env.SS_RECOVERY_TOKEN
  const password = randomBytes(32).toString('base64url') + 'Aa7!'
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const context = await browser.newContext({ ignoreHTTPSErrors: false })
  const page = await context.newPage()
  page.setDefaultTimeout(15000)
  await page.route('**/*', route => {
    const url = new URL(route.request().url())
    if (!['localhost', '127.0.0.1'].includes(url.hostname)) return route.abort()
    return route.continue()
  })
  await page.goto('https://localhost:15175/reset-password#token=' + token)
  await page.getByLabel('New password', { exact: true }).fill(password)
  await page.getByLabel('Confirm new password').fill(password)
  await page.getByRole('button', { name: 'Update password', exact: true }).click()
  await page.getByRole('heading', { name: 'Your password is updated' }).waitFor()
  const result = await page.evaluate(async ({ token, password, oldPassword, jwt }) => {
    const post = (path, body) => fetch('https://localhost:18443' + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    const email = 'twinklesangani@gmail.com'
    const old = await post('/api/auth/login', { email, password: oldPassword })
    const current = await post('/api/auth/login', { email, password })
    const stale = await fetch('https://localhost:18443/api/auth/me', { headers: { Authorization: 'Bearer ' + jwt } })
    const replay = await post('/api/auth/reset-password', { token, password, confirmPassword: password })
    return old.status === 401 && current.status === 200 && stale.status === 401 && replay.status === 400
  }, { token, password, oldPassword: process.env.SS_RECOVERY_OLD_PASSWORD, jwt: process.env.SS_RECOVERY_OLD_JWT })
  if (!result) throw Error()
  process.stdout.write('RECOVERY_SECURITY_PASS')
} catch {
  process.exitCode = 1
} finally {
  await browser?.close()
}
