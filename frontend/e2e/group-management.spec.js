import { addMembers, apiRequest, createGroup, expect, groupMembers, newAccount, registerAndLogin, signIn, signOut, test } from './support/app.js'

test('group creation, membership, creator authorization and outsider privacy', async ({ page }) => {
  test.setTimeout(180_000)
  const a = newAccount('Group Creator')
  const b = newAccount('Group Member B')
  const c = newAccount('Group Member C')
  const d = newAccount('Group Outsider')
  const groupName = `Membership ${a.email.slice(3, 11)}`

  for (const account of [a, b, c, d]) {
    await registerAndLogin(page, account)
    await signOut(page)
  }

  await signIn(page, a)
  const groupId = await createGroup(page, groupName)
  await addMembers(page, groupId, [b, c])
  await expect(page.locator('.member-list .member-card')).toHaveCount(3)
  await page.reload()
  await expect(page.locator('.group-overview-welcome h2')).toHaveText(groupName)
  await expect(page.locator('.member-list .member-card')).toContainText([a.name, b.name, c.name])

  const createdGroup = (await apiRequest(page, '/api/groups')).body.find((group) => String(group.id) === groupId)
  expect(createdGroup.name).toBe(groupName)
  const members = await groupMembers(page, groupId)
  expect(members.map((member) => member.name).sort()).toEqual([a.name, b.name, c.name].sort())

  await signOut(page)
  await signIn(page, b)
  await page.goto(`/groups/${groupId}`)
  await expect(page.locator('.group-overview-welcome h2')).toHaveText(groupName)
  await expect(page.getByRole('button', { name: 'Add member', exact: true })).toHaveCount(0)
  const forbiddenAdd = await apiRequest(page, `/api/groups/${groupId}/members`, {
    method: 'POST',
    body: { email: d.email },
  })
  expect(forbiddenAdd.status).toBe(403)

  await signOut(page)
  await signIn(page, d)
  await page.goto(`/groups/${groupId}`)
  await expect(page.getByRole('heading', { name: 'Group unavailable' })).toBeVisible()
  await expect(page.getByText(groupName, { exact: true })).toHaveCount(0)
  const outsiderResponse = await apiRequest(page, `/api/groups/${groupId}/members`)
  expect([403, 404]).toContain(outsiderResponse.status)
  expect(JSON.stringify(outsiderResponse.body)).not.toContain(groupName)
})
