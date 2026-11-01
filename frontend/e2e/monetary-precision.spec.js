import { test, expect, newAccount, registerAndLogin, signOut, signIn, createGroup, addMembers,
  groupMembers, apiRequest, createExpense } from './support/app.js'

test('large monetary strings survive browser input, API, persistence, balances and repayments', async ({ page }) => {
  test.setTimeout(180_000)
  const creditor = newAccount('Precision creditor')
  const debtor = newAccount('Precision debtor')
  await registerAndLogin(page, debtor)
  await signOut(page)
  await registerAndLogin(page, creditor)
  const group = await createGroup(page, 'Precision browser ledger')
  await addMembers(page, group, [debtor])
  await createExpense(page, group, 'Precision-normal', '0.60')
  await createExpense(page, group, 'Precision-trillion', '1000000000000.00')
  await createExpense(page, group, 'Precision-maximum', '99999999999999999.99')
  const members = await groupMembers(page, group)
  const payer = members.find(m => m.name === creditor.name).userId
  const recipient = members.find(m => m.name === debtor.name).userId
  const exact = '70368744177664.01'
  const created = await apiRequest(page, '/api/groups/' + group + '/expenses', {
    method: 'POST', body: { description: 'Precision-exact', amount: exact, paidByUserId: payer,
      splitType: 'EXACT', splits: [{ userId: recipient, amount: exact }] },
  })
  expect(created.status).toBe(201)
  expect(created.body.amount).toBe(exact)
  const expenseId = created.body.id
  await page.goto('/groups/' + group + '/expenses/' + expenseId)
  await expect(page.getByText('₹' + exact, { exact: true }).first()).toBeVisible()
  await page.reload()
  await expect(page.getByText('₹' + exact, { exact: true }).first()).toBeVisible()
  const list = await apiRequest(page, '/api/groups/' + group + '/expenses')
  expect(list.body).toHaveLength(4)
  expect(list.body.find(row => row.description === 'Precision-trillion').amount).toBe('1000000000000.00')
  expect(list.body.find(row => row.description === 'Precision-maximum').amount).toBe('99999999999999999.99')
  expect(list.body.find(row => row.id === expenseId).amount).toBe(exact)
  await page.goto('/groups/' + group + '/expenses')
  await expect(page.getByText('Precision-trillion', { exact: true })).toBeVisible()
  await expect(page.getByText('Precision-normal', { exact: true })).toBeVisible()
  await expect(page.getByText('₹1000000000000.00', { exact: true })).toBeVisible()
  await expect(page.getByText('₹99999999999999999.99', { exact: true })).toBeVisible()
  const invalid = await apiRequest(page, '/api/groups/' + group + '/expenses', {
    method: 'POST', body: { description: 'Invalid', amount: '100000000000000000.00',
      paidByUserId: payer, splitType: 'EQUAL', participantUserIds: [payer] },
  })
  expect(invalid.status).toBe(400)
  expect((await apiRequest(page, '/api/groups/' + group + '/expenses')).body).toHaveLength(4)
  const balances = await apiRequest(page, '/api/groups/' + group + '/balances')
  const paise = value => {
    expect(typeof value).toBe('string')
    return BigInt(value.replace('.', ''))
  }
  expect(balances.body.balances.reduce((sum, row) => sum + paise(row.netBalance), 0n)).toBe(0n)
  await page.goto('/groups/' + group + '/balances')
  await expect(page.locator('.finance-member')).toHaveCount(2)
  for (const row of balances.body.balances) {
    const absolute = row.netBalance.replace(/^-/, '')
    await expect(page.getByText('₹' + absolute, { exact: true }).first()).toBeVisible()
  }
  await signOut(page)
  await signIn(page, debtor)
  await page.goto('/groups/' + group + '/settlements')
  await page.getByLabel('Recipient').selectOption(String(payer))
  await page.getByLabel('Amount (INR)').fill(exact)
  await expect(page.getByLabel('Amount (INR)')).toHaveValue(exact)
  await page.getByRole('button', { name: 'Record repayment', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('was saved')
  await expect(page.getByText('₹' + exact, { exact: true }).first()).toBeVisible()
  const settlements = await apiRequest(page, '/api/groups/' + group + '/settlements')
  expect(settlements.body[0].amount).toBe(exact)
})
