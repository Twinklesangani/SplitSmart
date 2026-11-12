import { test, expect, newAccount, registerAndLogin, signOut } from './support/app.js'

for (const width of [375, 1280]) {
  test(`settings, recovery and protected history at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    const account = newAccount('Settings Synthetic')
    await registerAndLogin(page, account)
    if (width < 760) await page.getByRole('button', { name: 'Open navigation' }).click()
    const navigation = width < 760 ? page.locator('.mobile-drawer') : page.locator('.desktop-sidebar')
    await navigation.getByRole('link', { name: 'Settings', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Settings', exact: true })).toBeVisible()
    await expect(page.getByRole('main')).toBeFocused()
    await expect(page.getByRole('main').getByText(account.email, { exact: true })).toBeVisible()
    await expect(page.getByText('Profile editing is not available yet.')).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.reload()
    await expect(page.getByRole('heading', { name: 'Settings', exact: true })).toBeVisible()
    await page.getByRole('link', { name: 'View recovery availability' }).click()
    await expect(page.getByRole('heading', { name: 'Password recovery unavailable' })).toBeVisible()
    await page.goBack()
    await expect(page.getByRole('heading', { name: 'Settings', exact: true })).toBeVisible()
    await signOut(page)
    await page.goto('/settings')
    await expect(page.getByRole('heading', { name: 'Sign in to SplitSmart' })).toBeVisible()
  })
}
