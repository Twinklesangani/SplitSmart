import { addMembers, apiRequest, createExpense, createGroup, expect, groupMembers, newAccount, registerAndLogin, signIn, signOut, test } from './support/app.js'

// Independent assertion parser: app services require Vite's browser environment.
function parseFinancialInrPaise(value) {
  const match = /^(-?)(\d+)(?:\.(\d{1,2}))?$/.exec(String(value))
  expect(match, 'API money must be a decimal with at most two fraction digits').not.toBeNull()
  const paise = BigInt(match[2]) * 100n + BigInt((match[3] || '').padEnd(2, '0'))
  return match[1] ? -paise : paise
}

async function persistedBalances(page, groupId) {
  const response = await apiRequest(page, `/api/groups/${groupId}/balances`)
  expect(response.status).toBe(200)
  return new Map(response.body.balances.map((balance) => [
    balance.name,
    parseFinancialInrPaise(balance.netBalance),
  ]))
}

test('balances, repayment validation, settlement persistence and final reconciliation', async ({ page }) => {
  test.setTimeout(180_000)
  const a = newAccount('Finance Alpha')
  const b = newAccount('Finance Bravo')
  const c = newAccount('Finance Charlie')
  for (const account of [a, b, c]) {
    await registerAndLogin(page, account)
    await signOut(page)
  }

  await signIn(page, a)
  const groupId = await createGroup(page, `Finance ${a.email.slice(3, 11)}`)
  await addMembers(page, groupId, [b, c])
  const expenseDescription = `Only equal expense ${a.email.slice(3, 11)}`
  const expenseId = await createExpense(page, groupId, expenseDescription, '900.00')
  const members = await groupMembers(page, groupId)
  const memberId = (account) => members.find((member) => member.name === account.name).userId

  await page.goto(`/groups/${groupId}/balances`)
  await expect(page.locator('.finance-member')).toHaveCount(3)
  const balanceFor = (name) => page.getByRole('article').filter({ has: page.getByRole('heading', { name: new RegExp(`^${name}(?:\\s*You)?$`) }) })
  await expect(balanceFor(a.name)).toContainText('Should receive')
  await expect(balanceFor(a.name)).toContainText('₹600.00')
  await expect(balanceFor(b.name)).toContainText('Owes')
  await expect(balanceFor(b.name)).toContainText('₹300.00')
  await expect(balanceFor(c.name)).toContainText('Owes')
  await expect(balanceFor(c.name)).toContainText('₹300.00')
  await expect(page.locator('.finance-suggestion-total')).toContainText('₹600.00')
  await expect(page.locator('.suggestion-item')).toHaveCount(2)
  const initialBalances = await persistedBalances(page, groupId)
  expect(initialBalances.get(a.name)).toBe(60_000n)
  expect(initialBalances.get(b.name)).toBe(-30_000n)
  expect(initialBalances.get(c.name)).toBe(-30_000n)
  const initialSuggestions = await apiRequest(page, `/api/groups/${groupId}/settlement-suggestions`)
  expect(initialSuggestions.status).toBe(200)
  expect(parseFinancialInrPaise(initialSuggestions.body.totalSuggestedAmount)).toBe(60_000n)
  expect(initialSuggestions.body.suggestions.map((suggestion) => (
    `${suggestion.fromName}->${suggestion.toName}:${parseFinancialInrPaise(suggestion.amount)}`
  )).sort()).toEqual([
    `${b.name}->${a.name}:30000`,
    `${c.name}->${a.name}:30000`,
  ].sort())

  await signOut(page)
  await signIn(page, b)
  await page.goto(`/groups/${groupId}/balances`)
  await expect(page.locator('.finance-suggestion-total')).toContainText('₹600.00')
  await page.locator('.suggestion-item').filter({ hasText: b.name }).filter({ hasText: a.name })
    .getByRole('link', { name: 'Record this repayment' }).click()
  await expect(page).toHaveURL(new RegExp(`/groups/${groupId}/settlements$`))
  await page.getByLabel('Amount (INR)').fill('200.00')

  let firstSubmission = null
  page.on('request', (request) => {
    if (
      firstSubmission === null
      && request.method() === 'POST'
      && new URL(request.url()).pathname === `/api/groups/${groupId}/settlements`
    ) {
      firstSubmission = request.postDataJSON()
    }
  })
  await page.getByRole('button', { name: 'Record repayment', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('was saved')
  await expect(page.getByRole('status')).toContainText('does not confirm or initiate a money transfer')
  expect(firstSubmission.requestId).toBeTruthy()
  expect(firstSubmission.fromUserId).toBe(memberId(b))
  expect(firstSubmission.toUserId).toBe(memberId(a))

  const duplicate = await apiRequest(page, `/api/groups/${groupId}/settlements`, {
    method: 'POST',
    body: firstSubmission,
  })
  expect(duplicate.status).toBe(409)
  let settlements = await apiRequest(page, `/api/groups/${groupId}/settlements`)
  expect(settlements.status).toBe(200)
  expect(settlements.body).toHaveLength(1)
  const afterFirstSettlement = await persistedBalances(page, groupId)
  expect(afterFirstSettlement.get(a.name)).toBe(40_000n)
  expect(afterFirstSettlement.get(b.name)).toBe(-10_000n)
  expect(afterFirstSettlement.get(c.name)).toBe(-30_000n)
  const remainingSuggestions = await apiRequest(page, `/api/groups/${groupId}/settlement-suggestions`)
  expect(parseFinancialInrPaise(remainingSuggestions.body.totalSuggestedAmount)).toBe(40_000n)

  await page.getByLabel('Recipient').selectOption(String(memberId(a)))
  await page.getByLabel('Amount (INR)').fill('999999.99')
  await page.getByRole('button', { name: 'Record repayment', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('exceed the current outstanding balance')
  settlements = await apiRequest(page, `/api/groups/${groupId}/settlements`)
  expect(settlements.body).toHaveLength(1)

  await page.goto(`/groups/${groupId}/balances`)
  await expect(page.locator('.finance-suggestion-total')).toContainText('₹400.00')
  await expect(balanceFor(b.name)).toContainText('₹100.00')
  await page.locator('.suggestion-item').filter({ hasText: b.name }).filter({ hasText: a.name })
    .getByRole('link', { name: 'Record this repayment' }).click()
  await expect(page.getByLabel('Amount (INR)')).toHaveValue('100.00')
  await page.getByRole('button', { name: 'Record repayment', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('was saved')

  await signOut(page)
  await signIn(page, c)
  await page.goto(`/groups/${groupId}/balances`)
  await page.locator('.suggestion-item').filter({ hasText: c.name }).filter({ hasText: a.name })
    .getByRole('link', { name: 'Record this repayment' }).click()
  await expect(page.getByLabel('Amount (INR)')).toHaveValue('300.00')
  await page.getByRole('button', { name: 'Record repayment', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('was saved')

  await signOut(page)
  await signIn(page, a)
  await page.goto(`/groups/${groupId}/balances`)
  await expect(page.getByRole('heading', { name: 'All settled' })).toBeVisible()
  await expect(page.locator('.suggestion-item')).toHaveCount(0)
  await expect(page.locator('.finance-member')).toHaveCount(3)
  for (const account of [a, b, c]) {
    await expect(balanceFor(account.name)).toContainText('Settled')
    await expect(balanceFor(account.name)).toContainText('₹0.00')
  }
  await page.reload()
  await expect(page.getByRole('heading', { name: 'All settled' })).toBeVisible()

  const finalSettlements = await apiRequest(page, `/api/groups/${groupId}/settlements`)
  expect(finalSettlements.body).toHaveLength(3)
  const finalBalances = await persistedBalances(page, groupId)
  for (const account of [a, b, c]) {
    expect(finalBalances.get(account.name)).toBe(0n)
  }
  const finalSuggestions = await apiRequest(page, `/api/groups/${groupId}/settlement-suggestions`)
  expect(parseFinancialInrPaise(finalSuggestions.body.totalSuggestedAmount)).toBe(0n)
  expect(finalSuggestions.body.suggestions).toHaveLength(0)
  const finalExpenses = await apiRequest(page, `/api/groups/${groupId}/expenses`)
  expect(finalExpenses.body).toHaveLength(1)
  expect(finalExpenses.body[0].id).toBe(Number(expenseId))
  expect(finalExpenses.body[0].description).toBe(expenseDescription)
})
