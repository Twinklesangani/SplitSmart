import { ApiError, apiRequest } from './apiClient.js'

const MAX_AMOUNT_PAISE = 9999999999999999999n
const MONEY_PATTERN = /^(0|[1-9]\d{0,16})(?:\.(\d{1,2}))?$/

export function parseMoneyToPaise(value) {
  if (typeof value === 'number' && (!Number.isFinite(value) || Math.abs(value) >= 1e12)) return null
  const text = typeof value === 'number' && Number.isFinite(value)
    ? String(value)
    : typeof value === 'string'
      ? value.trim()
      : ''
  const match = MONEY_PATTERN.exec(text)
  if (!match) return null

  const whole = BigInt(match[1])
  const fraction = BigInt((match[2] ?? '').padEnd(2, '0') || '0')
  const paise = whole * 100n + fraction
  if (paise <= 0n || paise > MAX_AMOUNT_PAISE) return null
  return paise
}

export function normalizeMoney(value) {
  const paise = parseMoneyToPaise(value)
  if (paise === null) return null
  const whole = paise / 100n
  const fraction = String(paise % 100n).padStart(2, '0')
  return `${whole}.${fraction}`
}

export function formatInr(value) {
  const normalized = normalizeMoney(value)
  return normalized === null ? 'Amount unavailable' : `₹${normalized}`
}

function isId(value) {
  return (typeof value === 'number' && Number.isSafeInteger(value) && value > 0)
    || (typeof value === 'string' && /^\d+$/.test(value) && Number.isSafeInteger(Number(value)) && Number(value) > 0)
}

function requireExpense(expense, expectedGroupId, expectedExpenseId) {
  if (
    !expense
    || !isId(expense.id)
    || !isId(expense.groupId)
    || String(expense.groupId) !== String(expectedGroupId)
    || (expectedExpenseId !== undefined && String(expense.id) !== String(expectedExpenseId))
    || typeof expense.description !== 'string'
    || expense.currencyCode !== 'INR'
    || !isId(expense.paidByUserId)
    || !isId(expense.createdByUserId)
    || parseMoneyToPaise(expense.amount) === null
    || !Array.isArray(expense.splits)
    || expense.splits.length === 0
    || expense.splits.some((split) => !split || !isId(split.userId) || parseMoneyToPaise(split.amount) === null)
  ) {
    throw new ApiError('The service returned unexpected expense data. Please refresh and try again.', {
      code: 'invalid_response',
    })
  }

  const participantIds = new Set(expense.splits.map((split) => String(split.userId)))
  const splitTotal = expense.splits.reduce((total, split) => total + parseMoneyToPaise(split.amount), 0n)
  if (participantIds.size !== expense.splits.length || splitTotal !== parseMoneyToPaise(expense.amount)) {
    throw new ApiError('The service returned inconsistent expense shares. Please refresh and try again.', {
      code: 'invalid_response',
    })
  }
  return expense
}

function requireExpenses(expenses, groupId) {
  if (!Array.isArray(expenses)) {
    throw new ApiError('The service returned unexpected expense data. Please refresh and try again.', {
      code: 'invalid_response',
    })
  }
  return expenses.map((expense) => requireExpense(expense, groupId))
}

function expensePath(groupId, expenseId) {
  return `/api/groups/${encodeURIComponent(groupId)}/expenses${expenseId === undefined ? '' : `/${encodeURIComponent(expenseId)}`}`
}

export async function listExpenses(groupId, { signal } = {}) {
  return requireExpenses(await apiRequest(expensePath(groupId), { signal }), groupId)
}

export async function getExpense(groupId, expenseId, { signal } = {}) {
  return requireExpense(await apiRequest(expensePath(groupId, expenseId), { signal }), groupId, expenseId)
}

export async function createExpense(groupId, payload, { signal, operationKey } = {}) {
  const expense = await apiRequest(expensePath(groupId), {
    method: 'POST',
    operationKey,
    body: payload,
    expectedStatus: 201,
    signal,
  })
  return requireExpense(expense, groupId)
}

export async function updateExpense(groupId, expenseId, payload, { signal } = {}) {
  const expense = await apiRequest(expensePath(groupId, expenseId), {
    method: 'PUT',
    body: payload,
    expectedStatus: 200,
    signal,
  })
  return requireExpense(expense, groupId, expenseId)
}

export async function deleteExpense(groupId, expenseId, { signal } = {}) {
  await apiRequest(expensePath(groupId, expenseId), {
    method: 'DELETE',
    expectedStatus: 204,
    signal,
  })
}
