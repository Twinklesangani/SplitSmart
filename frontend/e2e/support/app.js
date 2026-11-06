import { randomBytes, randomUUID } from 'node:crypto'
import { expect, test as base } from '@playwright/test'

export const API_URL = process.env.SPLITSMART_E2E_API_URL
const API_ORIGIN = new URL(API_URL).origin
const unexpectedRequests = new WeakMap()

export const test = base

test.beforeEach(async ({ page }) => {
  const blockedRequests = []
  unexpectedRequests.set(page, blockedRequests)
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.origin !== API_ORIGIN) {
      blockedRequests.push(`${route.request().method()} ${url.pathname}`)
      await route.abort('blockedbyclient')
      return
    }
    await route.continue()
  })
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname.startsWith('/api/') && url.origin !== API_ORIGIN) {
      blockedRequests.push(`${request.method()} ${url.pathname}`)
    }
  })
})

test.afterEach(async ({ page }) => {
  expect(unexpectedRequests.get(page)).toEqual([])
})

export { expect }

export function newAccount(name, password = `${randomBytes(24).toString('base64url')}Aa7!`) {
  return {
    name,
    email: `qa-${randomUUID()}@example.test`,
    password,
  }
}

export async function registerAndLogin(page, account) {
  await page.goto('/register')
  await page.getByLabel('Full name').fill(account.name)
  await page.getByLabel('Email address').fill(account.email)
  await page.getByLabel('Password').fill(account.password)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('status')).toContainText('Your account is ready.')
  await signIn(page, account)
}

export async function signIn(page, account, destination = '/dashboard') {
  // A fresh URL clears React Router history state from an earlier protected redirect.
  // Explicit return-to tests retain the current login entry and its destination.
  if (destination === '/dashboard') await page.goto(`/login?qa-login=${randomUUID()}`)
  await page.getByLabel('Email address').fill(account.email)
  await page.getByLabel('Password').fill(account.password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page).toHaveURL(new URL(destination, process.env.SPLITSMART_E2E_BASE_URL).href)
  if (destination === '/dashboard') {
    await expect(page.getByRole('heading', { name: `Welcome, ${account.name.split(/\s+/)[0]}` })).toBeVisible()
  } else {
    await expect(page.locator('.desktop-sidebar').getByText(account.name, { exact: true })).toBeVisible()
  }
}

export async function signOut(page) {
  const desktopSignOut = page.locator('.desktop-sidebar').getByRole('button', { name: 'Sign out' })
  if (await desktopSignOut.isVisible()) {
    await desktopSignOut.click()
  } else {
    await page.getByRole('button', { name: 'Open navigation' }).click()
    await page.locator('.mobile-drawer').getByRole('button', { name: 'Sign out' }).click()
  }
  await expect(page).toHaveURL(/\/login$/)
}

export async function createGroup(page, name) {
  await page.goto('/groups')
  await page.getByRole('button', { name: 'Create group', exact: true }).first().click()
  await page.locator('.group-form').getByLabel('Group name').fill(name)
  await page.locator('.group-form').getByRole('button', { name: 'Create group', exact: true }).click()
  await expect(page.locator('.group-overview-welcome h2')).toHaveText(name)
  const match = new URL(page.url()).pathname.match(/^\/groups\/(\d+)$/)
  expect(match).not.toBeNull()
  return match[1]
}

export async function addMembers(page, groupId, accounts) {
  for (const account of accounts) {
    const created = await apiRequest(page, `/api/groups/${groupId}/invite-links`, { method: 'POST' })
    expect(created.status).toBe(201)
    const login = await page.request.post(`${API_URL}/api/auth/login`, { data: { email: account.email, password: account.password } })
    expect(login.status()).toBe(200)
    const session = await login.json()
    const accepted = await page.request.post(`${API_URL}/api/invitations/link-accept`, {
      headers: { Authorization: `Bearer ${session.accessToken}` }, data: { token: created.body.token },
    })
    expect(accepted.status()).toBe(200)
  }
  await page.goto(`/groups/${groupId}`)
  await expect(page.locator('.member-list .member-card')).toHaveCount(accounts.length + 1)
}
export async function groupMembers(page, groupId) {
  const response = await apiRequest(page, `/api/groups/${groupId}/members`)
  expect(response.status).toBe(200)
  return response.body
}

export async function createExpense(page, groupId, description, amount, splitType = 'EQUAL', exactAmounts = {}) {
  await page.goto(`/groups/${groupId}/expenses/new`)
  await page.getByLabel('Description').fill(description)
  await page.getByLabel('Total amount (INR)').fill(amount)
  if (splitType === 'EXACT') {
    await page.getByLabel('Custom amounts').check()
    for (const [memberName, share] of Object.entries(exactAmounts)) {
      await page.getByLabel(`Share for ${memberName}`).fill(share)
    }
  }
  await page.getByRole('button', { name: 'Save expense', exact: true }).click()
  await expect(page.getByRole('heading', { name: description, exact: true })).toBeVisible()
  const match = new URL(page.url()).pathname.match(/^\/groups\/\d+\/expenses\/(\d+)$/)
  expect(match).not.toBeNull()
  return match[1]
}

export async function apiRequest(page, path, { method = 'GET', body, authenticated = true, operationKey } = {}) {
  return page.evaluate(async ({ apiUrl, requestPath, requestMethod, requestBody, useSession, key }) => {
    const headers = { Accept: 'application/json' }
    if (requestMethod === 'POST' && /^\/api\/groups\/[^/]+\/expenses$/.test(requestPath)) {
      headers['Idempotency-Key'] = '"' + (key || crypto.randomUUID()) + '"'
    }
    if (useSession) {
      const token = window.sessionStorage.getItem('splitsmart.accessToken')
      if (token) headers.Authorization = `Bearer ${token}`
    }
    if (requestBody !== undefined) headers['Content-Type'] = 'application/json'
    const response = await fetch(new URL(requestPath, apiUrl), {
      method: requestMethod,
      headers,
      body: requestBody === undefined ? undefined : JSON.stringify(requestBody),
    })
    const text = await response.text()
    let responseBody = null
    try {
      responseBody = text ? JSON.parse(text) : null
    } catch {
      responseBody = null
    }
    return { status: response.status, body: responseBody, nextBefore: response.headers.get('X-Next-Before') }
  }, {
    apiUrl: API_URL,
    requestPath: path,
    requestMethod: method,
    requestBody: body,
    useSession: authenticated,
    key: operationKey,
  })
}
