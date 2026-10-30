import { addMembers, apiRequest, createExpense, createGroup, expect, groupMembers, newAccount, registerAndLogin, signIn, signOut, test } from './support/app.js'

test('equal and exact expense persistence, editing, deletion and creator authorization', async ({ page }) => {
  test.setTimeout(180_000)
  const a = newAccount('Expense Creator')
  const b = newAccount('Expense Member B')
  const c = newAccount('Expense Member C')
  const d = newAccount('Expense Outsider')
  for (const account of [a, b, c, d]) {
    await registerAndLogin(page, account)
    await signOut(page)
  }

  await signIn(page, a)
  const groupId = await createGroup(page, `Expenses ${a.email.slice(3, 11)}`)
  await addMembers(page, groupId, [b, c])
  const equalDescription = `Equal expense ${a.email.slice(3, 11)}`
  const equalExpenseId = await createExpense(page, groupId, equalDescription, '900.00')
  await expect(page.locator('.expense-share-list li')).toHaveCount(3)
  for (const account of [a, b, c]) {
    await expect(page.locator('.expense-share-list li').filter({ hasText: account.name })).toContainText('₹300.00')
  }
  await page.reload()
  await expect(page.getByRole('heading', { name: equalDescription })).toBeVisible()
  await expect(page.locator('.expense-share-list li')).toHaveCount(3)

  const members = await groupMembers(page, groupId)
  const participantIds = members.map((member) => member.userId)
  await signOut(page)
  await signIn(page, b)
  await page.goto(`/groups/${groupId}/expenses/${equalExpenseId}/edit`)
  await expect(page.getByText('Only the person who created this expense can edit it.')).toBeVisible()
  const unauthorizedUpdate = await apiRequest(page, `/api/groups/${groupId}/expenses/${equalExpenseId}`, {
    method: 'PUT',
    body: {
      description: 'Unauthorized edit',
      amount: '900.00',
      paidByUserId: members.find((member) => member.name === a.name).userId,
      currencyCode: 'INR',
      splitType: 'EQUAL',
      participantUserIds: participantIds,
    },
  })
  expect(unauthorizedUpdate.status).toBe(403)
  const unauthorizedDelete = await apiRequest(page, `/api/groups/${groupId}/expenses/${equalExpenseId}`, { method: 'DELETE' })
  expect(unauthorizedDelete.status).toBe(403)

  await signOut(page)
  await signIn(page, a)
  await page.goto(`/groups/${groupId}/expenses/${equalExpenseId}/edit`)
  await page.getByLabel('Description').fill(`${equalDescription} updated`)
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByRole('heading', { name: `${equalDescription} updated` })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: `${equalDescription} updated` })).toBeVisible()

  await page.getByRole('link', { name: 'Edit expense' }).click()
  await page.getByLabel('Total amount (INR)').fill('0.00')
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Enter a positive amount')
  await page.getByRole('link', { name: 'Cancel', exact: true }).click()
  await expect(page.getByRole('heading', { name: `${equalDescription} updated` })).toBeVisible()

  await page.getByRole('button', { name: 'Delete expense', exact: true }).click()
  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('heading', { name: `${equalDescription} updated` })).toBeVisible()

  await page.getByRole('button', { name: 'Delete expense', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Delete expense', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('Expense deleted successfully.')
  await expect(page.getByText(`${equalDescription} updated`, { exact: true })).toHaveCount(0)

  const exactDescription = `Exact expense ${a.email.slice(3, 11)}`
  const exactExpenseId = await createExpense(page, groupId, exactDescription, '900.00', 'EXACT', {
    [a.name]: '400.00',
    [b.name]: '300.00',
    [c.name]: '200.00',
  })
  const expectedShares = new Map([[a.name, '₹400.00'], [b.name, '₹300.00'], [c.name, '₹200.00']])
  for (const [name, amount] of expectedShares) {
    await expect(page.locator('.expense-share-list li').filter({ hasText: name })).toContainText(amount)
  }
  await page.reload()
  await expect(page.getByRole('heading', { name: exactDescription })).toBeVisible()
  const persistedExpenses = await apiRequest(page, `/api/groups/${groupId}/expenses`)
  expect(persistedExpenses.status).toBe(200)
  expect(persistedExpenses.body.map((expense) => String(expense.id))).toContain(exactExpenseId)
  expect(persistedExpenses.body.map((expense) => expense.description)).not.toContain(`${equalDescription} updated`)

  await signOut(page)
  await signIn(page, d)
  await page.goto(`/groups/${groupId}/expenses/${exactExpenseId}`)
  await expect(page.getByRole('heading', { name: 'Group unavailable' })).toBeVisible()
})
