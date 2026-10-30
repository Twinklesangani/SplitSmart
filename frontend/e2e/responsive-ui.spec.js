import { addMembers, createExpense, createGroup, expect, newAccount, registerAndLogin, signIn, signOut, test } from './support/app.js'

async function assertResponsiveLayout(page, width) {
  const layout = await page.evaluate(() => {
    const controls = [...document.querySelectorAll('button, a[href], input:not([type="hidden"]), select, textarea')]
      .filter((element) => {
        const style = window.getComputedStyle(element)
        return element.getClientRects().length > 0
          && style.display !== 'none'
          && style.visibility !== 'hidden'
          && style.opacity !== '0'
          && !element.closest('[aria-hidden="true"]')
      })
      .map((element) => {
        const rect = element.getBoundingClientRect()
        return {
          name: element.getAttribute('aria-label') || element.innerText || element.getAttribute('id') || element.tagName,
          left: rect.left,
          right: rect.right,
          top: rect.top,
          bottom: rect.bottom,
        }
      })
    const overlaps = []
    for (let first = 0; first < controls.length; first += 1) {
      for (let second = first + 1; second < controls.length; second += 1) {
        const a = controls[first]
        const b = controls[second]
        const width = Math.min(a.right, b.right) - Math.max(a.left, b.left)
        const height = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top)
        if (width > 1 && height > 1) overlaps.push([a.name, b.name])
      }
    }
    return {
      viewportWidth: document.documentElement.clientWidth,
      scrollWidth: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth),
      offscreenControls: controls.filter((control) => control.left < -1 || control.right > window.innerWidth + 1).map((control) => control.name),
      overlaps,
    }
  })
  expect(layout.viewportWidth).toBe(width)
  expect(layout.scrollWidth).toBeLessThanOrEqual(layout.viewportWidth)
  expect(layout.offscreenControls).toEqual([])
  expect(layout.overlaps).toEqual([])
}

test('responsive pages, keyboard navigation, drawer focus and accessible feedback', async ({ page }, testInfo) => {
  test.setTimeout(180_000)
  const a = newAccount('Responsive Alpha')
  const b = newAccount('Responsive Bravo')
  const c = newAccount('Responsive Charlie')
  await registerAndLogin(page, a)
  await signOut(page)
  await registerAndLogin(page, b)
  await signOut(page)
  await registerAndLogin(page, c)
  await signOut(page)

  await signIn(page, a)
  const groupId = await createGroup(page, `Responsive ${a.email.slice(3, 11)}`)
  await addMembers(page, groupId, [b, c])
  const expenseId = await createExpense(page, groupId, 'Responsive E2E expense', '900.00')

  await signOut(page)
  await signIn(page, b)
  await page.goto(`/groups/${groupId}/balances`)
  await page.locator('.suggestion-item').filter({ hasText: b.name }).filter({ hasText: a.name })
    .getByRole('link', { name: 'Record this repayment' }).click()
  await page.getByRole('button', { name: 'Record repayment', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('was saved')
  await signOut(page)
  await signIn(page, a)

  for (const width of [375, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await signOut(page)
    await page.goto('/login')
    const email = page.getByLabel('Email address')
    const password = page.getByLabel('Password')
    await email.focus()
    await page.keyboard.press('Tab')
    await expect(password).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeFocused()
    await assertResponsiveLayout(page, width)

    await page.goto('/register')
    await page.getByRole('button', { name: 'Create account', exact: true }).click()
    await expect(page.getByRole('alert')).toHaveCount(3)
    await expect(page.getByText('Enter a password.')).toBeVisible()
    await assertResponsiveLayout(page, width)

    await signIn(page, a)
    await page.goto('/dashboard')
    await expect(page.getByRole('heading', { name: 'Welcome, Responsive' })).toBeVisible()
    await assertResponsiveLayout(page, width)
    await page.goto('/groups')
    await expect(page.getByRole('heading', { name: 'My groups' })).toBeVisible()
    await assertResponsiveLayout(page, width)
    await page.goto(`/groups/${groupId}`)
    await expect(page.locator('.member-list .member-card')).toHaveCount(3)
    await assertResponsiveLayout(page, width)
    await page.goto(`/groups/${groupId}/expenses`)
    await expect(page.getByText('Responsive E2E expense')).toBeVisible()
    await assertResponsiveLayout(page, width)
    await page.goto(`/groups/${groupId}/expenses/new`)
    await expect(page.getByRole('form', { name: 'Create expense' })).toBeVisible()
    await assertResponsiveLayout(page, width)
    await page.goto(`/groups/${groupId}/expenses/${expenseId}`)
    await expect(page.getByRole('heading', { name: 'Responsive E2E expense' })).toBeVisible()
    await assertResponsiveLayout(page, width)
    await page.goto(`/groups/${groupId}/expenses/${expenseId}/edit`)
    await expect(page.getByRole('form', { name: 'Edit expense' })).toBeVisible()
    await assertResponsiveLayout(page, width)
    await page.goto(`/groups/${groupId}/balances`)
    await expect(page.getByRole('heading', { name: 'Member balances' })).toBeVisible()
    await assertResponsiveLayout(page, width)
    await page.goto(`/groups/${groupId}/settlements`)
    await expect(page.getByRole('heading', { name: 'Settlement history' })).toBeVisible()
    await expect(page.locator('.settlement-history__item')).toHaveCount(1)
    await assertResponsiveLayout(page, width)

    if (width === 375) {
      const menu = page.getByRole('button', { name: 'Open navigation' })
      await menu.click()
      const close = page.locator('.mobile-drawer').getByRole('navigation', { name: 'Main navigation' }).getByRole('button', { name: 'Close navigation' })
      await expect(close).toBeFocused()
      await page.keyboard.press('Escape')
      await expect(menu).toHaveAttribute('aria-expanded', 'false')
      await expect(menu).toBeFocused()
    }

    await page.screenshot({
      path: testInfo.outputPath(`responsive-${width}-settlements.png`),
      fullPage: true,
      mask: [
        page.locator('.account-card__identity'),
        page.locator('.member-card__identity'),
      ],
    })
  }
})
