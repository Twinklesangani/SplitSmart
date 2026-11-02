import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App.jsx'
import { AuthProvider } from '../context/AuthContext.jsx'

const group = { id: 42, name: 'Weekend cabin', createdByUserId: 8 }
const members = [
  { userId: 8, name: 'Samira Patel', email: 'samira@example.com' },
  { userId: 9, name: 'Alex Morgan', email: 'alex@example.com' },
  { userId: 10, name: 'Riley Chen', email: 'riley@example.com' },
]
const creator = { id: 8, name: 'Samira Patel', email: 'samira@example.com' }
const token = 'finance-test-token'
const requestId = 'c36bbf01-9440-4b54-a907-f86df0016087'

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  }
}

function balances(overrides = {}) {
  return {
    groupId: group.id,
    currencyCode: 'INR',
    totalExpenseAmount: '900.00',
    balances: [
      {
        userId: 8,
        name: 'Samira Patel',
        email: 'samira@example.com',
        totalPaid: '900.00',
        totalShare: '300.00',
        totalSentSettlements: '0.00',
        totalReceivedSettlements: '0.00',
        netBalance: '600.00',
      },
      {
        userId: 9,
        name: 'Alex Morgan',
        email: 'alex@example.com',
        totalPaid: '0.00',
        totalShare: '300.00',
        totalSentSettlements: '0.00',
        totalReceivedSettlements: '0.00',
        netBalance: '-300.00',
      },
      {
        userId: 10,
        name: 'Riley Chen',
        email: 'riley@example.com',
        totalPaid: '0.00',
        totalShare: '300.00',
        totalSentSettlements: '0.00',
        totalReceivedSettlements: '0.00',
        netBalance: '-300.00',
      },
    ],
    ...overrides,
  }
}

function suggestions(items = [
  { fromUserId: 9, toUserId: 8, fromName: 'Alex Morgan', toName: 'Samira Patel', amount: '300.00' },
  { fromUserId: 10, toUserId: 8, fromName: 'Riley Chen', toName: 'Samira Patel', amount: '300.00' },
]) {
  return {
    groupId: group.id,
    currencyCode: 'INR',
    totalSuggestedAmount: items.reduce((total, item) => total + Number(item.amount), 0).toFixed(2),
    suggestions: items,
  }
}

function settlement(overrides = {}) {
  return {
    id: 81,
    groupId: group.id,
    fromUserId: 9,
    toUserId: 8,
    recordedByUserId: 9,
    amount: '300.00',
    requestId,
    note: null,
    createdAt: '2026-03-19T10:00:00Z',
    ...overrides,
  }
}

function renderFinance(path, {
  user = creator,
  groupList = [group],
  balanceResponse = balances(),
  suggestionResponse = suggestions(),
  settlementResponse = [],
  responseOverrides = {},
  onCreateSettlement,
  locationState,
} = {}) {
  window.sessionStorage.setItem('splitsmart.accessToken', token)
  const entry = locationState
    ? { pathname: path, state: locationState }
    : path
  const state = {
    balanceResponse,
    suggestionResponse,
    settlementResponse,
  }
  fetch.mockImplementation(async (url, options = {}) => {
    const requestUrl = new URL(url)
    if (responseOverrides[requestUrl.pathname]) {
      const override = responseOverrides[requestUrl.pathname]
      return typeof override === 'function' ? override(options, requestUrl) : override
    }
    if (requestUrl.pathname === '/api/auth/me') return jsonResponse(user)
    if (requestUrl.pathname === '/api/groups') return jsonResponse(groupList)
    if (requestUrl.pathname === `/api/groups/${group.id}/members`) return jsonResponse(members)
    if (requestUrl.pathname === `/api/groups/${group.id}/balances`) return jsonResponse(state.balanceResponse)
    if (requestUrl.pathname === `/api/groups/${group.id}/settlement-suggestions`) return jsonResponse(state.suggestionResponse)
    if (requestUrl.pathname === `/api/groups/${group.id}/settlements` && options.method === 'POST') {
      const response = onCreateSettlement
        ? await onCreateSettlement(JSON.parse(options.body))
        : jsonResponse(settlement(JSON.parse(options.body)), 201)
      if (response.status === 201) state.settlementResponse = [await response.json()]
      return response
    }
    if (requestUrl.pathname === `/api/groups/${group.id}/settlements`) return jsonResponse(state.settlementResponse)
    return jsonResponse({ code: 'not_found' }, 404)
  })

  const view = render(
    <MemoryRouter initialEntries={[entry]}>
      <AuthProvider><App /></AuthProvider>
    </MemoryRouter>,
  )
  return { ...view, state }
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn())
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('group financial pages', () => {
  it('renders service-provided member balances and signs without recalculating them', async () => {
    renderFinance(`/groups/${group.id}/balances`)

    expect(await screen.findByRole('heading', { name: 'Member balances' })).toBeInTheDocument()
    expect(await screen.findByText('Should receive')).toBeInTheDocument()
    expect(screen.getAllByText('Owes', { exact: true })).toHaveLength(2)
    expect(screen.getByText('Total expenses recorded · INR')).toBeInTheDocument()
    expect(screen.getAllByText('₹900.00')).toHaveLength(2)
    expect(screen.getAllByText('₹600.00')).toHaveLength(4)
    expect(fetch).toHaveBeenCalledWith(
      'http://localhost:8081/api/groups/42/balances',
      expect.objectContaining({ method: 'GET' }),
    )
  })

  it('preserves recommendation order and offers recording only to the suggested sender', async () => {
    renderFinance(`/groups/${group.id}/balances`, { user: { id: 9, name: 'Alex Morgan', email: 'alex@example.com' } })

    const firstSuggestion = await screen.findByText('Alex Morgan', { selector: '.suggestion-item__main strong' })
    const rows = firstSuggestion.closest('ol').querySelectorAll('li')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Alex Morgan pays Samira Patel')
    expect(rows[1]).toHaveTextContent('Riley Chen pays Samira Patel')
    expect(within(rows[0]).getByRole('link', { name: 'Record this repayment' })).toHaveAttribute('href', '/groups/42/settlements')
    expect(within(rows[1]).queryByRole('link', { name: 'Record this repayment' })).not.toBeInTheDocument()
  })

  it('does not claim the group is settled when the service returns no recommendations but outstanding balances', async () => {
    renderFinance(`/groups/${group.id}/balances`, { suggestionResponse: suggestions([]) })

    expect(await screen.findByRole('heading', { name: 'No recommendations available' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'All settled' })).not.toBeInTheDocument()
  })

  it('uses the service balance response to report a genuinely settled group', async () => {
    const settledBalances = balances({
      totalExpenseAmount: '0.00',
      balances: balances().balances.map((item) => ({
        ...item,
        totalPaid: '0.00',
        totalShare: '0.00',
        netBalance: '0.00',
      })),
    })
    renderFinance(`/groups/${group.id}/balances`, {
      balanceResponse: settledBalances,
      suggestionResponse: suggestions([]),
    })

    expect(await screen.findByRole('heading', { name: 'All settled' })).toBeInTheDocument()
  })

  it('shows the empty history without implying that everyone is settled', async () => {
    renderFinance(`/groups/${group.id}/settlements`)

    expect(await screen.findByRole('heading', { name: 'No repayments recorded yet' })).toBeInTheDocument()
    expect(screen.getByText(/does not indicate whether anyone currently owes money/)).toBeInTheDocument()
  })

  it('shows settlement history in the server-provided order with notes and timestamps', async () => {
    const newest = settlement({ id: 82, requestId: '01234567-89ab-cdef-0123-456789abcdef', note: 'Paid after dinner' })
    const older = settlement({ id: 81, requestId: 'abcdef01-2345-6789-abcd-ef0123456789', amount: '125.00', createdAt: '2026-03-18T10:00:00Z' })
    renderFinance(`/groups/${group.id}/settlements`, { settlementResponse: [newest, older] })

    await screen.findByText('Paid after dinner')
    const history = document.querySelectorAll('.settlement-history__item')
    expect(history).toHaveLength(2)
    expect(history[0]).toHaveTextContent('₹300.00')
    expect(history[0]).toHaveTextContent('Paid after dinner')
    expect(history[0].querySelector('time')).toHaveAttribute('datetime', newest.createdAt)
    expect(history[1]).toHaveTextContent('₹125.00')
  })

  it('prefills a valid sender recommendation and records the authenticated sender with a unique request ID', async () => {
    const user = userEvent.setup()
    const created = settlement({ note: 'Paid in person' })
    let view
    view = renderFinance(`/groups/${group.id}/settlements`, {
      user: { id: 9, name: 'Alex Morgan', email: 'alex@example.com' },
      locationState: { suggestion: suggestions().suggestions[0] },
      onCreateSettlement: async (payload) => {
        expect(payload).toEqual({
          fromUserId: 9,
          toUserId: 8,
          amount: '300.00',
          requestId,
          note: null,
        })
        created.requestId = payload.requestId
        view.state.settlementResponse = [created]
        view.state.balanceResponse = balances({
          totalExpenseAmount: '900.00',
          balances: balances().balances.map((item) => ({ ...item, netBalance: '0.00' })),
        })
        view.state.suggestionResponse = suggestions([])
        return jsonResponse(created, 201)
      },
    })
    vi.stubGlobal('crypto', { randomUUID: () => requestId })

    expect(await screen.findByLabelText(/Recipient/)).toHaveValue('8')
    expect(screen.getByLabelText(/Amount \(INR\)/)).toHaveValue('300.00')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Record repayment' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: 'Record repayment' }))

    expect(await screen.findByRole('status')).toHaveTextContent('was saved')
    const post = fetch.mock.calls.find(([, options]) => options?.method === 'POST')
    expect(post).toBeDefined()
    expect(post[0]).toBe('http://localhost:8081/api/groups/42/settlements')
    expect(JSON.parse(post[1].body).fromUserId).toBe(9)
    expect(JSON.parse(post[1].body).requestId).toBe(requestId)
    expect(await screen.findByText((_, element) => (
      element.tagName === 'P' && element.textContent === 'Alex Morgan repaid Samira Patel'
    ))).toBeInTheDocument()
    expect(view.state.settlementResponse).toHaveLength(1)
    expect(view.state.balanceResponse.balances.every((item) => item.netBalance === '0.00')).toBe(true)
    expect(view.state.suggestionResponse.suggestions).toHaveLength(0)
    expect(fetch.mock.calls.filter(([url, options]) => (
      url.endsWith('/balances') && options?.method === 'GET'
    )).length).toBeGreaterThanOrEqual(2)
  })

  it('keeps the submit action locked while a settlement request is unresolved', async () => {
    const user = userEvent.setup()
    let resolvePost
    let resolveRefresh
    let balanceRequests = 0
    const pendingPost = new Promise((resolve) => { resolvePost = resolve })
    const pendingRefresh = new Promise((resolve) => { resolveRefresh = resolve })
    renderFinance(`/groups/${group.id}/settlements`, {
      user: { id: 9, name: 'Alex Morgan', email: 'alex@example.com' },
      onCreateSettlement: () => pendingPost,
      responseOverrides: {
        [`/api/groups/${group.id}/balances`]: () => {
          balanceRequests += 1
          return balanceRequests === 1 ? jsonResponse(balances()) : pendingRefresh
        },
      },
    })
    vi.stubGlobal('crypto', { randomUUID: () => requestId })
    await screen.findByRole('heading', { name: 'Settlement history' })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Record repayment' })).toBeEnabled())
    await user.selectOptions(screen.getByLabelText(/Recipient/), '8')
    await user.type(screen.getByLabelText(/Amount \(INR\)/), '300.00')
    const submit = screen.getByRole('button', { name: 'Record repayment' })

    await user.click(submit)
    expect(submit).toBeDisabled()
    await user.click(submit)
    expect(fetch.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)

    resolvePost(jsonResponse(settlement({ requestId }), 201))
    expect(await screen.findByRole('status')).toHaveTextContent('was saved')
    expect(submit).toBeDisabled()
    resolveRefresh(jsonResponse(balances()))
    await waitFor(() => expect(submit).toBeEnabled())
  })

  it('shows a safe server validation message without rendering internal response details', async () => {
    const user = userEvent.setup()
    renderFinance(`/groups/${group.id}/settlements`, {
      user: { id: 9, name: 'Alex Morgan', email: 'alex@example.com' },
      onCreateSettlement: async () => jsonResponse({ message: 'database password and SQL internals' }, 400),
    })
    vi.stubGlobal('crypto', { randomUUID: () => requestId })
    await screen.findByRole('heading', { name: 'Settlement history' })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Record repayment' })).toBeEnabled())
    await user.selectOptions(screen.getByLabelText(/Recipient/), '8')
    await user.type(screen.getByLabelText(/Amount \(INR\)/), '300')
    await user.click(screen.getByRole('button', { name: 'Record repayment' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('may exceed the current outstanding balance')
    expect(screen.queryByText(/database password|SQL internals/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Record repayment' })).toBeEnabled()
  })

  it('reconciles an idempotency conflict and retries only the same request ID', async () => {
    const user = userEvent.setup()
    const created = settlement()
    const attempts = []
    renderFinance(`/groups/${group.id}/settlements`, {
      user: { id: 9, name: 'Alex Morgan', email: 'alex@example.com' },
      onCreateSettlement: async (payload) => {
        attempts.push(payload)
        return attempts.length === 1
          ? jsonResponse({ code: 'conflict', message: 'Sensitive conflict detail' }, 409)
          : jsonResponse(created, 201)
      },
    })
    vi.stubGlobal('crypto', { randomUUID: () => requestId })
    await screen.findByRole('heading', { name: 'Settlement history' })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Record repayment' })).toBeEnabled())
    await user.selectOptions(screen.getByLabelText(/Recipient/), '8')
    await user.type(screen.getByLabelText(/Amount \(INR\)/), '300')
    await user.click(screen.getByRole('button', { name: 'Record repayment' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('conflicts with an existing record')
    expect(screen.queryByText('Sensitive conflict detail')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Retry same request' }))

    expect(await screen.findByRole('status')).toHaveTextContent('was saved')
    expect(attempts).toHaveLength(2)
    expect(attempts[1]).toEqual(attempts[0])
    expect(attempts[1].requestId).toBe(requestId)
  })

  it('expires the authenticated session after a finance endpoint returns 401', async () => {
    renderFinance(`/groups/${group.id}/balances`, {
      responseOverrides: {
        [`/api/groups/${group.id}/balances`]: jsonResponse({ message: 'private server detail' }, 401),
      },
    })

    expect(await screen.findByRole('heading', { name: 'Sign in to SplitSmart' })).toBeInTheDocument()
    expect(window.sessionStorage.getItem('splitsmart.accessToken')).toBeNull()
    expect(screen.queryByText('private server detail')).not.toBeInTheDocument()
  })

  it('keeps inaccessible groups private and does not request their financial data', async () => {
    renderFinance(`/groups/${group.id}/balances`, { groupList: [] })

    expect(await screen.findByRole('heading', { name: 'Group not found or unavailable' })).toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalledWith(
      expect.stringContaining(`/api/groups/${group.id}/balances`),
      expect.anything(),
    )
  })
})

describe('bounded repayment history', () => {
  const paged = (items, before = null) => ({
    ...jsonResponse(items),
    headers: new Headers(before ? { 'X-Next-Before': before } : {}),
  })

  it('loads the next page once without recomputing authoritative balances', async () => {
    const user = userEvent.setup()
    let finishPage
    let reads = 0
    renderFinance('/groups/42/settlements', {
      responseOverrides: {
        '/api/groups/42/settlements': () => {
          reads++
          return reads === 1 ? paged([settlement({ id: 902 })], '902')
            : new Promise(resolve => { finishPage = resolve })
        },
      },
    })
    const more = await screen.findByRole('button', { name: 'Load more repayments' })
    await user.click(more)
    expect(more).toBeDisabled()
    await user.click(more)
    expect(reads).toBe(2)
    await act(async () => finishPage(paged([settlement({
      id: 901, amount: '125.00', requestId: '11111111-1111-4111-8111-111111111111',
    })])))
    expect(document.querySelectorAll('.settlement-history__item')).toHaveLength(2)
    expect(screen.queryByRole('button', { name: 'Load more repayments' })).not.toBeInTheDocument()
    expect(fetch.mock.calls.some(([url]) => new URL(url).searchParams.get('before') === '902')).toBe(true)
    expect(fetch.mock.calls.filter(([url]) => new URL(url).pathname.endsWith('/balances'))).toHaveLength(1)
  })

  it('recovers an uncertain older repayment using the request-ID filter without a second POST', async () => {
    const user = userEvent.setup()
    let posts = 0
    renderFinance('/groups/42/settlements', {
      user: { id: 9, name: 'Alex Morgan', email: 'alex@example.com' },
      responseOverrides: {
        '/api/groups/42/settlements': (options, url) => {
          if (options.method === 'POST') {
            posts++
            return Promise.reject(new TypeError('Synthetic lost response'))
          }
          if (url.searchParams.get('requestId') === requestId) {
            return paged([settlement({ id: 901, requestId, amount: '1.00' })])
          }
          return paged([settlement({ id: 999, requestId: '11111111-1111-4111-8111-111111111111' })], '999')
        },
      },
    })
    vi.stubGlobal('crypto', { randomUUID: () => requestId })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Record repayment' })).toBeEnabled())
    await user.selectOptions(screen.getByLabelText(/Recipient/), '8')
    await user.type(screen.getByLabelText(/Amount \(INR\)/), '1.00')
    await user.click(screen.getByRole('button', { name: 'Record repayment' }))
    await screen.findByRole('button', { name: 'Refresh settlement history' })
    await user.click(screen.getByRole('button', { name: 'Refresh settlement history' }))
    expect(await screen.findByRole('status')).toHaveTextContent('was found in settlement history')
    expect(posts).toBe(1)
    expect(fetch.mock.calls.some(([url]) => new URL(url).searchParams.get('requestId') === requestId)).toBe(true)
  })
})
