import { apiRequest, createGroup, expect, newAccount, registerAndLogin, signIn, signOut, test } from './support/app.js'

test('registration boundaries, sign-in, session persistence and account isolation', async ({ page }) => {
  test.setTimeout(180_000)
  const a = newAccount('Alpha Account', 'A'.repeat(72))
  const b = newAccount('Bravo Account', '€'.repeat(24))
  const c = newAccount('Charlie Account')
  const d = newAccount('Delta Account')

  await page.goto('/register')
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveCount(3)
  const passwordInput = page.getByLabel('Password')
  await expect(page.getByText('Enter a password.')).toBeVisible()

  await page.getByLabel('Full name').fill('Invalid Boundary')
  await page.getByLabel('Email address').fill('boundary@example.test')
  await passwordInput.fill(`${'€'.repeat(24)}a`)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Password must be no more than 72 UTF-8 bytes.')

  await registerAndLogin(page, a)
  await expect(page.getByRole('heading', { name: 'Welcome, Alpha' })).toBeVisible()
  await signOut(page)

  await registerAndLogin(page, b)
  await signOut(page)
  await registerAndLogin(page, c)
  await signOut(page)
  await registerAndLogin(page, d)
  await signOut(page)

  await page.goto('/register')
  await page.getByLabel('Full name').fill(a.name)
  await page.getByLabel('Email address').fill(a.email)
  await page.getByLabel('Password').fill(a.password)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('An account with this email already exists.')

  await page.goto('/login')
  await page.getByLabel('Email address').fill(a.email)
  await page.getByLabel('Password').fill('incorrect-e2e-password')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Invalid email or password.')

  await signIn(page, a)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Welcome, Alpha' })).toBeVisible()
  const groupName = `Auth isolation ${a.email.slice(3, 11)}`
  const groupId = await createGroup(page, groupName)

  await signOut(page)
  await page.goto(`/groups/${groupId}`)
  await expect(page).toHaveURL(/\/login$/)
  await signIn(page, b, `/groups/${groupId}`)
  await expect(page.getByRole('heading', { name: 'Group unavailable' })).toBeVisible()
  await page.goto('/groups')
  await expect(page.getByRole('heading', { name: 'No groups yet' })).toBeVisible()
  await page.goto(`/groups/${groupId}`)
  await expect(page.getByRole('heading', { name: 'Group unavailable' })).toBeVisible()
  await expect(page.getByText(groupName, { exact: true })).toHaveCount(0)

  const bAccess = await apiRequest(page, `/api/groups/${groupId}/members`)
  expect([403, 404]).toContain(bAccess.status)
  expect(JSON.stringify(bAccess.body)).not.toContain(groupName)

  await signOut(page)
  await signIn(page, d)
  await page.goto(`/groups/${groupId}`)
  await expect(page.getByRole('heading', { name: 'Group unavailable' })).toBeVisible()
})
