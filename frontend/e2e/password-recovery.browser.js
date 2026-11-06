import { test, expect } from '@playwright/test'

const token = 'A'.repeat(43)

test.beforeEach(async ({ page }) => {
  await page.route('https://fonts.googleapis.com/**', (route) => route.abort())
  await page.route('https://fonts.gstatic.com/**', (route) => route.abort())
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const origin = { 'Access-Control-Allow-Origin': 'http://127.0.0.1:15174', 'Access-Control-Allow-Headers': 'content-type', 'Access-Control-Allow-Methods': 'POST,GET,OPTIONS' }
    if (route.request().method() === 'OPTIONS') return route.fulfill({ status: 204, headers: origin })
    if (path === '/api/auth/forgot-password') return route.fulfill({ status: 202, headers: origin, json: { message: 'Request received' } })
    if (path === '/api/auth/reset-password') {
      expect(route.request().postDataJSON().token === token).toBeTruthy()
      return route.fulfill({ status: 204, headers: origin })
    }
    return route.fulfill({ status: 401, headers: origin, json: {} })
  })
})

for (const width of [375, 1280]) {
  test(`recovery flow and keyboard feedback at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/forgot-password')
    await page.getByRole('button', { name: 'Request reset link' }).click()
    await expect(page.getByLabel('Email address')).toBeFocused()
    await page.screenshot({ path: info.outputPath(`${width}-forgot.png`), fullPage: true })
    await page.getByLabel('Email address').fill('disposable@example.invalid')
    await page.getByRole('button', { name: 'Request reset link' }).click()
    await expect(page.getByRole('heading', { name: 'Check your email' })).toBeFocused()
    await expect(page.getByText('Your request was received. Email delivery has not been confirmed.')).toBeVisible()
    await page.screenshot({ path: info.outputPath(`${width}-check-email.png`), fullPage: true })
    await page.goto(`/reset-password#token=${token}`)
    await expect(page).toHaveURL('http://127.0.0.1:15174/reset-password')
    await expect(page.getByRole('heading', { name: 'Choose a new password' })).toBeFocused()
    await page.screenshot({ path: info.outputPath(`${width}-reset.png`), fullPage: true })
    await page.getByLabel('New password', { exact: true }).fill('DisposablePassword123!')
    await page.getByLabel('Confirm new password').fill('DisposablePassword123!')
    await page.getByRole('button', { name: 'Show passwords' }).focus()
    await page.keyboard.press('Enter')
    await expect(page.getByLabel('New password', { exact: true })).toHaveAttribute('type', 'text')
    await page.getByRole('button', { name: 'Update password' }).click()
    await expect(page.getByRole('heading', { name: 'Your password is updated' })).toBeFocused()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
    await page.screenshot({ path: info.outputPath(`${width}-success.png`), fullPage: true })
    await page.getByRole('link', { name: 'Sign in to SplitSmart' }).click()
    await expect(page.getByRole('heading', { name: 'Sign in to SplitSmart' })).toBeVisible()
  })
}

test('unavailable email and expired links never show delivery or reset success', async ({ page }) => {
  await page.route('**/api/auth/forgot-password', (route) => route.fulfill({ status: 503, headers: { 'Access-Control-Allow-Origin': '*' }, json: {} }))
  await page.goto('/forgot-password')
  await page.getByLabel('Email address').fill('disposable@example.invalid')
  await page.getByRole('button', { name: 'Request reset link' }).click()
  await expect(page.getByRole('alert')).toContainText('Email recovery is currently unavailable')
  await page.route('**/api/auth/reset-password', (route) => route.fulfill({ status: 400, headers: { 'Access-Control-Allow-Origin': '*' }, json: {} }))
  await page.goto(`/reset-password#token=${token}`)
  await page.getByLabel('New password', { exact: true }).fill('DisposablePassword123!')
  await page.getByLabel('Confirm new password').fill('DisposablePassword123!')
  await page.getByRole('button', { name: 'Update password' }).click()
  await expect(page.getByRole('heading', { name: 'Request a new reset link' })).toBeVisible()
})
