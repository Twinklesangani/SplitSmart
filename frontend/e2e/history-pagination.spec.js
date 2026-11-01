import { randomUUID } from 'node:crypto'
import { test, expect, newAccount, registerAndLogin, signIn, signOut, createGroup, addMembers,
  groupMembers, apiRequest } from './support/app.js'

test('expense and repayment histories load bounded pages with complete balances and private scopes', async ({ page }) => {
  test.setTimeout(180_000)
  const owner = newAccount('History owner')
  const debtor = newAccount('History debtor')
  await registerAndLogin(page, debtor)
  await signOut(page)
  await registerAndLogin(page, owner)
  const group = await createGroup(page, 'Paged ledger')
  await addMembers(page, group, [debtor])
  const members = await groupMembers(page, group)
  const payer = members.find(member => member.name === owner.name).userId
  const borrower = members.find(member => member.name === debtor.name).userId
  const path = '/api/groups/' + group + '/expenses'
  expect((await apiRequest(page, path)).body).toHaveLength(0)
  let firstKey
  let firstPayload
  let firstId
  for (let index = 0; index < 26; index++) {
    const operationKey = randomUUID()
    const body = { description: 'History row ' + index, amount: '1.00', currencyCode: 'INR',
      splitType: 'EXACT', paidByUserId: payer, splits: [{ userId: borrower, amount: '1.00' }] }
    const created = await apiRequest(page, path, { method: 'POST', body, operationKey })
    expect(created.status).toBe(201)
    if (index === 0) { firstKey = operationKey; firstPayload = body; firstId = created.body.id }
  }
  await page.goto('/groups/' + group + '/expenses')
  await expect(page.locator('.expense-list-card')).toHaveCount(25)
  await expect(page.locator('.expense-list-card').first()).toContainText('History row 25')
  await expect(page.getByRole('heading', { name: 'History row 0', exact: true })).toHaveCount(0)
  const balances = await apiRequest(page, '/api/groups/' + group + '/balances')
  expect(balances.body.totalExpenseAmount).toBe('26.00')
  await page.getByRole('button', { name: 'Load more expenses' }).click()
  await expect(page.locator('.expense-list-card')).toHaveCount(26)
  await expect(page.getByRole('heading', { name: 'History row 0', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Load more expenses' })).toHaveCount(0)
  const replay = await apiRequest(page, path, { method: 'POST', body: firstPayload, operationKey: firstKey })
  expect(replay.status).toBe(201)
  expect(replay.body.id).toBe(firstId)
  const first = await apiRequest(page, path)
  expect(first.body).toHaveLength(25)
  const older = await apiRequest(page, path + '?before=' + first.nextBefore)
  expect(older.body).toHaveLength(1)
  expect(older.body[0].id).toBe(firstId)

  await page.getByRole('link', { name: 'History row 25', exact: true }).click()
  await page.getByRole('link', { name: 'Edit expense' }).click()
  await page.getByLabel('Description').fill('Edited history row')
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Edited history row', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Delete expense', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Delete expense', exact: true }).click()
  await expect(page.locator('.expense-list-card')).toHaveCount(25)
  await expect(page.getByRole('button', { name: 'Load more expenses' })).toHaveCount(0)

  const otherGroup = await createGroup(page, 'Separate history scope')
  const other = await apiRequest(page, '/api/groups/' + otherGroup + '/expenses', {
    method: 'POST', body: { ...firstPayload, description: 'Other group only', splits: [{ userId: payer, amount: '1.00' }] },
  })
  expect(other.status).toBe(201)
  await page.goto('/groups/' + otherGroup + '/expenses')
  await expect(page.locator('.expense-list-card')).toHaveCount(1)
  await expect(page.getByRole('heading', { name: 'Other group only', exact: true })).toBeVisible()
  await page.goto('/groups/' + group + '/expenses')
  await expect(page.locator('.expense-list-card')).toHaveCount(25)
  await expect(page.getByRole('heading', { name: 'Other group only', exact: true })).toHaveCount(0)

  await signOut(page)
  await signIn(page, debtor)
  let oldestRequest
  for (let index = 0; index < 26; index++) {
    const requestId = randomUUID()
    if (index === 0) oldestRequest = requestId
    const recorded = await apiRequest(page, '/api/groups/' + group + '/settlements', {
      method: 'POST', body: { fromUserId: borrower, toUserId: payer, amount: '0.01', requestId, note: null },
    })
    expect(recorded.status).toBe(201)
  }
  await page.goto('/groups/' + group + '/settlements')
  await expect(page.locator('.settlement-history__item')).toHaveCount(25)
  await page.getByRole('button', { name: 'Load more repayments' }).click()
  await expect(page.locator('.settlement-history__item')).toHaveCount(26)
  await expect(page.getByRole('button', { name: 'Load more repayments' })).toHaveCount(0)
  const receipt = await apiRequest(page, '/api/groups/' + group + '/settlements?requestId=' + oldestRequest)
  expect(receipt.body).toHaveLength(1)
  expect(receipt.body[0].requestId).toBe(oldestRequest)
  expect(receipt.body[0].amount).toBe('0.01')
  const complete = await apiRequest(page, '/api/groups/' + group + '/balances')
  expect(complete.body.totalExpenseAmount).toBe('25.00')
  expect(complete.body.balances.find(member => member.userId === borrower).netBalance).toBe('-24.74')
  expect((await apiRequest(page, '/api/groups/' + otherGroup + '/settlements?requestId=' + oldestRequest)).status).toBe(404)
  await page.goto('/groups/' + otherGroup + '/expenses')
  await expect(page.getByRole('heading', { name: 'Group not found or unavailable' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Other group only', exact: true })).toHaveCount(0)
})
