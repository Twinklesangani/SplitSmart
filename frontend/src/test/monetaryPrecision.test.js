import { expect, it } from 'vitest'
import { formatInr, parseMoneyToPaise } from '../services/expenseService.js'
import { formatFinancialInr } from '../services/financeService.js'

it('preserves all persisted amounts exactly in expense and settlement displays', () => {
  for (const amount of ['0.01', '1000000000000.00', '70368744177664.01', '99999999999999999.99']) {
    expect(formatInr(amount)).toBe('₹' + amount)
    expect(formatFinancialInr(amount)).toBe('₹' + amount)
    expect(parseMoneyToPaise(amount)).not.toBeNull()
  }
})
it('rejects invalid new amounts without rounding', () => {
  for (const amount of ['0', '-1', '1.001', '100000000000000000.00', 'NaN', 'Infinity']) {
    expect(parseMoneyToPaise(amount)).toBeNull()
  }
})
it('preserves aggregate balances beyond the column limit', () => {
  expect(formatFinancialInr('199999999999999999.98')).toBe('₹199999999999999999.98')
})

it('reads large and small expenses through the actual JSON client', async () => {
  const { vi } = await import('vitest')
  const { listExpenses } = await import('../services/expenseService.js')
  const expense = (id, amount) => ({ id, groupId: 1, description: 'Synthetic',
    currencyCode: 'INR', paidByUserId: 1, createdByUserId: 1, amount,
    splits: [{ userId: 1, amount }] })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify([
    expense(1, '70368744177664.01'), expense(2, '0.60'),
  ]), { headers: { 'Content-Type': 'application/json' } })))
  try {
    const { items } = await listExpenses(1)
    expect(items.map(item => formatInr(item.amount))).toEqual(['₹70368744177664.01', '₹0.60'])
  } finally { vi.unstubAllGlobals() }
})

it('rejects corrupt list data explicitly instead of displaying an incomplete ledger', async () => {
  const { vi } = await import('vitest')
  const { listExpenses } = await import('../services/expenseService.js')
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('[{"amount":"invalid"}]',
    { headers: { 'Content-Type': 'application/json' } })))
  try {
    await expect(listExpenses(1)).rejects.toMatchObject({ code: 'invalid_response' })
  } finally { vi.unstubAllGlobals() }
})

it('rejects large numeric money whose original decimal text is already lost', () => {
  expect(parseMoneyToPaise(JSON.parse('70368744177664.01'))).toBeNull()
  expect(formatFinancialInr(JSON.parse('70368744177664.01'))).toBe('Amount unavailable')
})

it('rejects a history cursor that repeats or moves toward newer records', async () => {
  const { vi } = await import('vitest')
  const { apiHistory } = await import('../services/apiClient.js')
  try {
    for (const cursor of ['5', '6']) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('[{}]', {
        headers: { 'Content-Type': 'application/json', 'X-Next-Before': cursor },
      })))
      await expect(apiHistory('/api/groups/1/expenses', { before: '5' })).rejects.toMatchObject({
        code: 'invalid_response', message: 'History pagination did not advance. Refresh and try again.',
      })
    }
  } finally { vi.unstubAllGlobals() }
})
