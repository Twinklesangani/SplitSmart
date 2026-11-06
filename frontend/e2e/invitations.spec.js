import { test, expect, newAccount, registerAndLogin, signIn, signOut, createGroup, createExpense, addMembers, groupMembers, apiRequest } from './support/app.js'

test('an invitation made before registration requires acceptance and synchronizes both accounts', async ({ page }) => {
  const owner = newAccount('Invite Owner')
  const recipient = newAccount('Invite Recipient')
  await page.goto('/login')
  await page.getByRole('link', { name: 'Forgot your password?' }).click()
  await expect(page.getByRole('heading', { name: 'Password recovery unavailable' })).toBeFocused()
  await expect(page.getByLabel('Email address')).toHaveCount(0)
  await registerAndLogin(page, owner)
  const groupId = await createGroup(page, `Invitations ${owner.email.slice(3, 11)}`)
  await expect(page.getByLabel('Invitation email address')).toHaveCount(0)
  await expect(page.getByText(/Email invitations are temporarily unavailable/)).toBeVisible()
  await page.getByRole('button', { name: 'Create private invite link' }).click()
  const privateLink = await page.getByLabel('Private invitation link').inputValue()
  await expect(page.locator('.member-list .member-card')).toHaveCount(1)
  await signOut(page)
  await registerAndLogin(page, recipient)
  await page.goto(privateLink)
  await page.getByRole('button', { name: 'Accept and join group' }).click()
  await expect(page.locator('.member-list .member-card')).toHaveCount(2)
  await page.reload()
  await expect(page.locator('.member-list .member-card')).toHaveCount(2)

  await signOut(page)
  await signIn(page, owner)
  await page.getByRole('button', { name: /^Notifications/ }).click()
  await expect(page.getByRole('link', { name: 'A member joined your group' })).toBeVisible()
  const expense = await createExpense(page, groupId, 'Synthetic invited expense', '10.00')
  await page.goto(`/groups/${groupId}/balances`)
  await expect(page.locator('.finance-member')).toHaveCount(2)
  await expect(page.locator('.finance-suggestion-total')).toContainText('₹5.00')
  await signOut(page)
  await signIn(page, recipient)
  await page.getByRole('button', { name: /^Notifications/ }).click()
  await page.getByRole('link', { name: 'A new shared expense was added' }).click()
  await expect(page).toHaveURL(new RegExp(`/groups/${groupId}/expenses/${expense}$`))
  await expect(page.getByRole('heading', { name: 'Synthetic invited expense', exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Synthetic invited expense', exact: true })).toBeVisible()
  await page.goto(`/groups/${groupId}/balances`)
  await expect(page.locator('.finance-suggestion-total')).toContainText('₹5.00')
})

test('four-person ₹50 cycle shows no repayments and keeps original expense history', async ({ page }) => {
  const accounts = ['Cycle A', 'Cycle B', 'Cycle C', 'Cycle D'].map(name => newAccount(name))
  await registerAndLogin(page, accounts[0])
  for (const account of accounts.slice(1)) {
    const registered = await apiRequest(page, '/api/auth/register', {
      method: 'POST', authenticated: false, body: account,
    })
    expect(registered.status).toBe(201)
  }
  const groupId = await createGroup(page, `Circle ${accounts[0].email.slice(3, 11)}`)
  await addMembers(page, groupId, accounts.slice(1))
  const members = await groupMembers(page, groupId)
  const ids = accounts.map(account => members.find(member => member.email === account.email).userId)
  for (let index = 0; index < ids.length; index++) {
    const expense = await apiRequest(page, `/api/groups/${groupId}/expenses`, {
      method: 'POST', body: {
        description: `Circle obligation ${index + 1}`, amount: '50.00',
        paidByUserId: ids[(index + 1) % ids.length],
        splits: [{ userId: ids[index], amount: '50.00' }],
      },
    })
    expect(expense.status).toBe(201)
  }
  const before = await apiRequest(page, `/api/groups/${groupId}/expenses`)
  expect(before.body).toHaveLength(4)
  await page.goto(`/groups/${groupId}/balances`)
  await expect(page.getByRole('heading', { name: 'All settled', exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Record this repayment', exact: true })).toHaveCount(0)
  const suggestions = await apiRequest(page, `/api/groups/${groupId}/settlement-suggestions`)
  expect(suggestions.body.totalSuggestedAmount).toBe('0.00')
  expect(suggestions.body.suggestions).toEqual([])
  const after = await apiRequest(page, `/api/groups/${groupId}/expenses`)
  expect(after.body).toEqual(before.body)
  await page.goto('/dashboard')
  const dashboard = await apiRequest(page, '/api/dashboard')
  expect(dashboard.body.owed).toBe('0.00')
  expect(dashboard.body.receivable).toBe('0.00')
})
