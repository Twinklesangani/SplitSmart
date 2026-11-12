import { test, expect } from '@playwright/test'

for (const width of [375, 1280]) {
  test(`email-disabled recovery, private links and maintenance feedback at ${width}px`, async ({ page }) => {
    const calls = []
    await page.setViewportSize({ width, height: 900 })
    await page.route('**/*', async route => {
      const url = new URL(route.request().url())
      if (!['127.0.0.1', 'localhost'].includes(url.hostname)) return route.abort()
      if (url.pathname.startsWith('/api/')) {
        calls.push(url.pathname)
        return route.fulfill({ status: 503, json: { code: 'maintenance', message: 'SYNTHETIC_UNTRUSTED_SERVER_DETAIL' } })
      }
      return route.continue()
    })
    await page.goto('/forgot-password')
    const heading = page.getByRole('heading', { name: 'Password recovery unavailable' })
    await expect(heading).toBeVisible()
    await expect(heading).toBeFocused()
    await expect(page.getByRole('textbox')).toHaveCount(0)
    expect(calls).toEqual([])
    await page.getByRole('link', { name: 'Back to sign in' }).click()
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()
    await expect(page.getByLabel('Email address')).toBeFocused()
    await page.getByLabel('Email address').fill('synthetic@example.invalid')
    await page.getByLabel('Password', { exact: true }).fill('SyntheticOnlyPassword123!')
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('temporarily unavailable')
    await expect(page.getByRole('alert')).not.toContainText('SYNTHETIC_UNTRUSTED')
    await expect(page.getByLabel('Password', { exact: true })).toBeFocused()
    expect(calls).toEqual(['/api/auth/login']) // No automatic mutation retry.
    await page.goto('/join')
    await expect(page.getByRole('heading', { name: 'Join a shared group' })).toBeVisible()
    await expect(page.getByRole('alert')).toContainText('complete link')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await page.goto('/dashboard')
    await expect(page.getByRole('heading', { name: 'Sign in to SplitSmart' })).toBeVisible()
  })
}

for (const width of [375, 1280]) {
  test(`member navigation and refresh retain the section at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await page.addInitScript(() => sessionStorage.setItem('splitsmart.accessToken', 'synthetic-only'))
    await page.route('**/api/**', route => {
      const path = new URL(route.request().url()).pathname
      const body = path === '/api/auth/me' ? { id: 1, name: 'Synthetic owner', email: 'owner@example.invalid' }
        : path === '/api/groups' ? [{ id: 42, name: 'Synthetic trip', createdByUserId: 1, archived: false }]
        : path === '/api/groups/42/members' ? [{ userId: 1, name: 'Synthetic owner', email: 'owner@example.invalid' }]
        : path.endsWith('unread-count') ? { unreadCount: 0 } : []
      return route.fulfill({ status: 200, json: body })
    })
    await page.goto('/groups/42')
    await page.getByRole('navigation', { name: 'Group sections' }).getByRole('link', { name: 'Members', exact: true }).click()
    await expect(page).toHaveURL(/#members$/)
    await expect(page.locator('#members')).toBeFocused()
    await page.reload()
    await expect(page.locator('#members')).toBeVisible()
    await expect(page.locator('#members')).toBeFocused()
    await page.getByRole('link', { name: 'Invite people', exact: true }).click()
    await expect(page.locator('#invite-members')).toBeFocused()
    await page.goBack()
    await expect(page.locator('#members')).toBeFocused()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}
