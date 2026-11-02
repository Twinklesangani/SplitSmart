import { test, expect, apiRequest, newAccount } from './support/app.js'

test('the real login throttle displays generic feedback without automatic retries', async ({ page }) => {
  await page.goto('/login')
  const account = newAccount('Throttle synthetic')
  for (let index = 0; index < 10; index++) {
    const response = await apiRequest(page, '/api/auth/login', {
      method: 'POST', authenticated: false,
      body: { email: account.email, password: 'Incorrect123!' },
    })
    expect(response.status).toBe(401)
  }
  await page.getByLabel('Email address').fill(account.email)
  await page.getByLabel('Password').fill('Incorrect123!')
  let submissions = 0
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/auth/login') submissions++
  })
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Too many requests. Please wait before trying again.')
  expect(submissions).toBe(1)
  await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeEnabled()
})
