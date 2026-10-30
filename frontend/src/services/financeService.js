import { ApiError, apiRequest } from './apiClient.js'

const MONEY_PATTERN = /^(-?)(0|[1-9]\d{0,16})(?:\.(\d{1,2}))?$/
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const MAX_SAFE_PAISE = BigInt(Number.MAX_SAFE_INTEGER)

function isId(value) {
  return (typeof value === 'number' && Number.isSafeInteger(value) && value > 0)
    || (typeof value === 'string' && /^\d+$/.test(value) && Number.isSafeInteger(Number(value)) && Number(value) > 0)
}

function toPaise(value, { signed = false } = {}) {
  if (typeof value === 'number' && (!Number.isFinite(value) || Math.abs(value) > Number.MAX_SAFE_INTEGER / 100)) {
    return null
  }
  const text = typeof value === 'number' || typeof value === 'string' ? String(value).trim() : ''
  const match = MONEY_PATTERN.exec(text)
  if (!match || (!signed && match[1] === '-')) return null

  const whole = BigInt(match[2])
  const fraction = BigInt((match[3] ?? '').padEnd(2, '0') || '0')
  const amount = whole * 100n + fraction
  if (amount > MAX_SAFE_PAISE) return null
  return match[1] === '-' ? -amount : amount
}

export function formatFinancialInr(value) {
  const paise = toPaise(value, { signed: true })
  if (paise === null) return 'Amount unavailable'
  const absolute = paise < 0n ? -paise : paise
  const formatted = `₹${absolute / 100n}.${String(absolute % 100n).padStart(2, '0')}`
  return paise < 0n ? `−${formatted}` : formatted
}

export function normalizeFinancialInr(value) {
  const paise = toPaise(value)
  if (paise === null) return null
  return `${paise / 100n}.${String(paise % 100n).padStart(2, '0')}`
}

export function parseFinancialInrPaise(value) {
  return toPaise(value, { signed: true })
}

function invalidFinanceResponse() {
  return new ApiError('The service returned unexpected financial data. Refresh and try again.', {
    code: 'invalid_response',
  })
}

function requireMoney(value, { signed = false } = {}) {
  const amount = toPaise(value, { signed })
  if (amount === null) throw invalidFinanceResponse()
  return amount
}

function requireBalance(balance) {
  if (
    !balance
    || !isId(balance.userId)
    || typeof balance.name !== 'string'
    || typeof balance.email !== 'string'
  ) {
    throw invalidFinanceResponse()
  }

  requireMoney(balance.totalPaid)
  requireMoney(balance.totalShare)
  requireMoney(balance.totalSentSettlements)
  requireMoney(balance.totalReceivedSettlements)
  requireMoney(balance.netBalance, { signed: true })
  return balance
}

function requireGroupBalances(response, groupId) {
  if (
    !response
    || !isId(response.groupId)
    || String(response.groupId) !== String(groupId)
    || response.currencyCode !== 'INR'
    || !Array.isArray(response.balances)
  ) {
    throw invalidFinanceResponse()
  }

  requireMoney(response.totalExpenseAmount)
  const seen = new Set()
  let netTotal = 0n
  for (const balance of response.balances) {
    requireBalance(balance)
    const userId = String(balance.userId)
    if (seen.has(userId)) throw invalidFinanceResponse()
    seen.add(userId)
    netTotal += requireMoney(balance.netBalance, { signed: true })
  }
  if (netTotal !== 0n) throw invalidFinanceResponse()
  return response
}

function requireSuggestion(suggestion) {
  if (
    !suggestion
    || !isId(suggestion.fromUserId)
    || !isId(suggestion.toUserId)
    || String(suggestion.fromUserId) === String(suggestion.toUserId)
    || typeof suggestion.fromName !== 'string'
    || typeof suggestion.toName !== 'string'
    || requireMoney(suggestion.amount) <= 0n
  ) {
    throw invalidFinanceResponse()
  }
  return suggestion
}

function requireSuggestions(response, groupId) {
  if (
    !response
    || !isId(response.groupId)
    || String(response.groupId) !== String(groupId)
    || response.currencyCode !== 'INR'
    || !Array.isArray(response.suggestions)
  ) {
    throw invalidFinanceResponse()
  }

  const total = requireMoney(response.totalSuggestedAmount)
  let suggestionTotal = 0n
  for (const suggestion of response.suggestions) {
    requireSuggestion(suggestion)
    suggestionTotal += requireMoney(suggestion.amount)
  }
  if (suggestionTotal !== total || (response.suggestions.length === 0 && total !== 0n)) {
    throw invalidFinanceResponse()
  }
  return response
}

function requireSettlement(settlement, groupId) {
  if (
    !settlement
    || !isId(settlement.id)
    || !isId(settlement.groupId)
    || String(settlement.groupId) !== String(groupId)
    || !isId(settlement.fromUserId)
    || !isId(settlement.toUserId)
    || String(settlement.fromUserId) === String(settlement.toUserId)
    || !isId(settlement.recordedByUserId)
    || typeof settlement.requestId !== 'string'
    || !UUID_PATTERN.test(settlement.requestId)
    || (settlement.note !== null && typeof settlement.note !== 'string')
    || (settlement.createdAt !== null && typeof settlement.createdAt !== 'string')
    || requireMoney(settlement.amount) <= 0n
  ) {
    throw invalidFinanceResponse()
  }
  if (settlement.createdAt !== null && Number.isNaN(new Date(settlement.createdAt).getTime())) {
    throw invalidFinanceResponse()
  }
  return settlement
}

function requireSettlements(response, groupId) {
  if (!Array.isArray(response)) throw invalidFinanceResponse()
  const seen = new Set()
  return response.map((settlement) => {
    requireSettlement(settlement, groupId)
    if (seen.has(String(settlement.id))) throw invalidFinanceResponse()
    seen.add(String(settlement.id))
    return settlement
  })
}

function groupPath(groupId, suffix) {
  return `/api/groups/${encodeURIComponent(groupId)}/${suffix}`
}

export async function getGroupBalances(groupId, { signal } = {}) {
  const response = await apiRequest(groupPath(groupId, 'balances'), { signal })
  return requireGroupBalances(response, groupId)
}

export async function getSettlementSuggestions(groupId, { signal } = {}) {
  const response = await apiRequest(groupPath(groupId, 'settlement-suggestions'), { signal })
  return requireSuggestions(response, groupId)
}

export async function listSettlements(groupId, { signal } = {}) {
  return requireSettlements(await apiRequest(groupPath(groupId, 'settlements'), { signal }), groupId)
}

export async function createSettlement(groupId, payload) {
  const settlement = requireSettlement(await apiRequest(groupPath(groupId, 'settlements'), {
    method: 'POST',
    body: payload,
    expectedStatus: 201,
  }), groupId)

  if (
    settlement.requestId !== payload.requestId
    || String(settlement.fromUserId) !== String(payload.fromUserId)
    || String(settlement.toUserId) !== String(payload.toUserId)
    || requireMoney(settlement.amount) !== requireMoney(payload.amount)
  ) {
    throw invalidFinanceResponse()
  }
  return settlement
}
