import { expect, newAccount, registerAndLogin, signOut, test } from './support/app.js'

test('smoke: registration, login, dashboard and logout route protection', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
  await registerAndLogin(page, newAccount('Smoke Account'))
  await signOut(page)
  await page.goto('/dashboard')
  await expect(page).toHaveURL(/\/login$/)
})
